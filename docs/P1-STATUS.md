# P1 状态 — 源注册、增量抓取、归一化/分类、TTL

日期：2026-11-24
分支：`main`
基线：P0 `47f6cd7`；P1 实现 `ed54f6d`，修复 `0f65bd3`、`b29d566`

## 交付内容

| 模块 | 文件 | 说明 |
| --- | --- | --- |
| 源种子 | `db/migration/V3__seed_sources_and_classifier.sql` | 7 源（6 MacCMS 启用 / xifan 禁用待适配）、分类器 `cat-v1`、6 条规则 |
| 源仓库 | `crawl/SourceRepository.java` | 启停、健康度、失败计数 |
| 抓取客户端 | `crawl/MacCmsClient.java` | `ac=list`（带 `h` 窗口）/ `ac=detail`（20/批）、UA、超时、请求间隔 |
| 播放串解析 | `crawl/PlayUrlParser.java` | `$$$` / `#` / 首个 `$`，空线路跳过，协议分类 hls/page/direct |
| 归一化 | `crawl/TitleNormalizer.java` | 端侧 `normalizeTitle` 复刻 + `mergeKey` |
| 分类 | `crawl/CategoryClassifier.java` | 6 类规则从 DB 加载，排除项与多标签 |
| TTL | `crawl/TtlPolicy.java` | `min(vod+ttl, now+ttl)`，5 分钟下限；仅前移或内容变化才续期 |
| 写库 | `crawl/ItemWriter.java` | 条目 upsert、线路/剧集替换、group 重建、过期清理 |
| 编排 | `crawl/CrawlService.java` | run/task 记账、分页、详情批、源健康、retention |
| 调度 | `crawl/CrawlScheduler.java` | 每小时 `:05`、每日 `04:10`、清理每小时 `:35`（Asia/Shanghai） |
| 一次性任务 | `crawl/CrawlOnceRunner.java` | Compose profile `crawl` 手工触发 |
| 常驻调度容器 | `compose.yaml` `teahome-crawler` | 非 Web 进程 + 调度线程，`restart: unless-stopped` |

## 验收证据（2026-11-23/24，服务器 192.168.5.156）

部署版本 `b29d566`，镜像 `teahome-service:b29d566`，API `Healthy`。

1. **一轮抓取入库**
   - daily（h=192）：run=4，`status=ok`，6/6 源，251 页，4,954 条，0 失败，17m48s。
   - 首次 hourly：run=1，64 条，`partial`（ffzy EOF 一次）；随后 hourly run=2 75 条 `ok`。
2. **8 天窗口**
   - `count(*) filter (where vod_time < now()-interval '8 days')` = **0**。
   - 最老 `vod_time` 2026-11-15 18:17Z，最新 2026-11-23 15:48Z。
   - 全部条目 `expires_at - vod_time = 8 days`（min=max），`ttl_days_applied=8`。
3. **只在更新时续期**
   - 连续两轮 hourly 后 `max(expires_at)` 保持 `2026-12-01 15:48:50Z` 不变，条目数 64→75（新增 11）。
   - 单测覆盖：`TtlPolicyTest` 的“时间前移才续期 / 仅哈希变化 / 续期不缩”。
4. **删除重算 group**
   - 插入一条 `expires_at=now()-1d` 的测试条目（run=5 前），执行 `--teahome.crawl.once=retention`：条目被删除，`items_skipped=1`，group 12 的 `item_count/source_count` 回到 1/1、`revision` +1，42ms。

## 数据形状（daily 后）

| 指标 | 值 |
| --- | --- |
| 条目 | 4,954 |
| 分组 | 1,724（3,976 条入组；973 组多源） |
| 未入组 | 978（分类 `unmapped`） |
| 线路 | 9,252 |
| 剧集 | 291,392（44 个 host） |
| 分类 | tv 1,571 / anime 1,178 / 未分类 978 / variety 560 / movie 318 / short 317 / documentary 32 |
| 不变量 | 分组计数不一致 0；每 item 恰好 1 条 primary 线路 |

## 已知偏差 / 限制

1. **ffzy 首轮 EOF**：`api.ffzyapi.com` 偶发 `EOF reached while reading`，当前无重试，整轮标记 `partial`；后续轮次成功。P1.1 加 2 次退避重试。
2. **978 条未分类（19.7%）**：源 `type_name` 是“现代都市/篮球/古装仙侠”等叶子名，端侧是按叶子菜单映射的；P1 只按关键词分类。P1.1 建 `teaplayer_source_category`（type_id→category）并用详情 `type_id` 兜底。
3. **xifan 动漫源未启用**：PostgREST 适配器未做，种子行 `is_enabled=false`。
4. **无并发互斥**：只有 `crawl_run.dedupe_key` 防调度重复；手工 CLI 可与调度并发。P1.1 加 `pg_try_advisory_lock`。
5. **`bytes_in` 未统计**（列存在，写 0）。
6. **分类 override 未接入**：`rebuildGroup` 会以条目聚合覆盖 group 分类；P2 有 override 后需保留人工值。
7. **daily 串行**：6 源顺序抓，单轮约 18 分钟；在 1 小时窗口内安全，但首屏数据新鲜度受限。P2 可并发源级。
8. **剧集解析未抽样对照原文**：依赖单测与 291k 条落库的成功率，P2 加 3 个真实源样本回归。

## 运维命令

```bash
# 手工 hourly / daily / retention（服务器）
cd /opt/teahome/app
sudo env API_TAG=$(git rev-parse --short HEAD) docker compose --env-file /opt/teahome/env/.env \
  --profile crawl run --rm teahome-crawl \
  --spring.main.web-application-type=none --teahome.crawl.once=daily

# 查看 run/task
sudo docker exec -i teahome-db psql -U postgres -d teahome -c \
  "select id,run_kind,status,items_upserted,failures,duration_ms from teaplayer_crawl_run order by id desc limit 5"
```

调度容器上线后，无需手工触发；此命令仅用于补跑与验收。

## 下一步

P2：`/v1/feed` 游标分页 + 分类筛选 + 封面代理，客户端接 Source；同时补 xifan、重试、source_category 映射。
