# P3 配置中心状态

日期：2026-11-24
版本：P3.1（配置中心 + 加密 secret + LISTEN/NOTIFY）

## 已交付

| 能力 | 实现 |
| --- | --- |
| 配置定义 | `R__10_seed_config_defs.sql` 可重复迁移，22 个键；只 upsert 定义，不碰值 |
| 配置值 | `teaplayer_config_value.value_json`，仅修改过的键才有行；删除行=恢复默认 |
| 优先级 | `TEAHOME_CFG_<KEY>` 环境变量 > 数据库 > `config_def.default_value` |
| 快照 | `ConfigService` 启动加载，不可变快照原子替换；失败保留上一版 |
| 刷新 | 管理 PUT 后立即 reload + `NOTIFY teaplayer_config`；`ConfigPoller` 独立守护线程每 30 秒兜底（与抓取调度解耦） |
| 校验 | 类型（int/long/bool/duration/bytes/url/string）+ min/max + URL 前缀 |
| 管理读取 | `GET /v1/admin/config`，read/admin scope，secret 掩码 |
| 管理写入 | `PUT /v1/admin/config/{key}`，admin scope；reason、expectedVersion、revision、audit 同事务；`revision_no = max+1` |
| secret | AES-256-GCM（`TEAHOME_CONFIG_KEYS` 密钥环、AAD 绑定 key、`key_id` 落库）；出口恒为 `******` |
| 热生效接入 | `retention.ttl_days`（ItemWriter）、`feed.page_size_default` / `page_size_max`、三个限流键 |

首批键：`retention.ttl_days`、`retention.cleanup_batch_size`、`crawl.hourly.enabled`、
`crawl.hourly.window_hours`、`crawl.daily.enabled`、`crawl.daily.window_hours`、
`crawl.detail_batch_size`、`crawl.request_interval_ms`、`feed.page_size_default`、
`feed.page_size_max`、`search.deadline`、`search.timeout_per_source`、`search.cache_ttl`、
`image.max_bytes`、`image.cache_max_bytes`、`image.cache_ttl_days`、
`api.rate_limit_read_per_sec`、`api.rate_limit_search_per_sec`、`api.rate_limit_img_per_min`、
`system.public_base_url`、`logging.level`。

## 验收记录

- 部署后 `/v1/meta` → `schemaVersion=7`；`GET /v1/admin/config` 返回 21 项，全部 `source=default`。
- 临时 admin token 写入 `feed.page_size_default=12`（reason/expectedVersion=0）：
  - PUT 返回 `source=db`、`version=1`；
  - `GET /v1/feed`（不带 size）实际返回 12 条，证明热生效；
  - 还原为 24（version=2），`/v1/feed?size=2` 返回 2 条。
- `/%76%31/feed?size=2` 仍返回 401（编码路径鉴权修复保持）。
- 测试 token 与临时配置行已删除；`feed.page_size_default` 回到 default。

## P3.1 验收记录

- secret：写入 `system.webhook_secret` 后 `value_json IS NULL`、`octet_length(value_cipher)=32`、`key_id=k2`、`value_hint=******`；GET 始终掩码；重启/重载解密成功。
- LISTEN：手工 `NOTIFY teaplayer_config` 后 api/crawler 均立即 reload；修复通知频道与监听频道不一致的 bug。
- 跨实例：PUT 后 crawler 日志出现「收到配置变更通知」，无需等 30 秒。
- 清理：测试 token、`feed.page_size_default` 临时值、`system.webhook_secret` 测试值已删除，配置回到默认 24。
- 注意：API 实例关闭抓取调度后，原 `@Scheduled` 不生效；已新增独立 `ConfigPoller`。

## 未完成 / 后续

1. `apply_mode` 的 `next_run` / `restart` 仍只记录，未实际延后。
2. secret revision 未保存密文，无法回滚 secret 历史值。
3. 启动时 def 漂移检查（缺键/未知键/类型变化）。
4. 跨键约束已实现基础检查，尚未覆盖全部 6 条。
5. 管理端页面/会话/回滚 UI 属 P4；当前只有 token API。
6. 无 ConfigService 集成测试（仅单测 + 手工验收）。

## 运维

```bash
# 读取（read 或 admin token）
curl -H "X-TeaHome-Token: $TOKEN" http://192.168.5.156:18080/v1/admin/config

# 修改（admin token；必须是 admin scope）
curl -X PUT -H "X-TeaHome-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"value":"12","reason":"验证","expectedVersion":0}' \
  http://192.168.5.156:18080/v1/admin/config/feed.page_size_default
```

重启 api/crawler 会重新加载快照；crawler 常驻，30 秒内自动看到其他实例的修改。

## P3.2 验收记录（审计不可变 / 退避 / 清理分片）

- `V11__audit_hardening.sql`：`teahome_app` 对 `teaplayer_audit_log`、`teaplayer_config_revision` 只保留 `SELECT/INSERT`；审计触发器 DELETE 仅 `teahome_migrate/postgres` 放行，UPDATE 永远拒绝；`config_revision` 增加 UPDATE/DELETE 全拒触发器；`schema_version='9'`。
- 线上验证：`SET ROLE teahome_app; DELETE FROM teaplayer_audit_log` → `ERROR: permission denied for table teaplayer_audit_log`；对 `teaplayer_config_revision` UPDATE 同样被拒。
- 401 退避改为双桶：同一 token 60 秒 30 次、同一 IP 120 次；实测同一坏 token 连续请求 `30×401 + 1×429`。成功只清 token 桶，NAT 后不会因一个客户端拖累整个 IP。
- `RateLimitFilter` 超 1 万窗口时改为清理 5 分钟未用项，不再整体 `clear()`。
- `ItemWriter.purgeExpired` 改为每批 500（读 `retention.cleanup_batch_size`）独立 `TransactionTemplate` 提交，批间 20ms；线上注入一条过期条目，retention run=55 `deleted=1`、51ms，条目与分组计数正确。
- 路径绕过回归：`/v1;x/feed`、`/%76%31/feed` 均 401；`/health` 200。
- schema 9；api/crawler/db 三容器 healthy。

仍未做（P3.2 候选）：图片 DNS pin、图片 GC、live detail 并发/负缓存、Testcontainers。

## P3.2 收尾（配置校验、运行时接线、健康检查）

2026-11-25 部署 `4347171`，线上验证如下。

- **写入前跨键校验**：`ConfigService.validateChange(key,value)` 先做类型/范围规范化，再把候选值放进当前快照副本跑 `checkCrossKeys`；不合法抛 `IllegalArgumentException`，`ConfigController` 返回 400 `bad_value`，DB 不落值。
- **提交后刷新**：`ConfigController` 写 value/revision/audit 后，通过 `TransactionSynchronization.afterCommit` 执行 `config.reload()` + `notifyChange`；响应直接用本次写入的 `EntryView`，不再读旧快照。
- **运行时接线**：`search.timeout_per_source` / `search.deadline`（SearchService + MacCmsClient 单次超时）、`crawl.hourly.window_hours` / `crawl.daily.window_hours` / `crawl.detail_batch_size` / `retention.ttl_days`（CrawlService）、`image.max_bytes`（ImageFetcher）均从 ConfigService 读取。
- **健康检查**：`/health` 查询 `teaplayer_service_meta.schema_version`；缺失/不匹配/连不上返回 503 `{status:DOWN,reason:...}`，正常返回 200 `{status:UP,schemaVersion:"9"}`。
- **验收**：
  - `PUT /v1/admin/config/feed.page_size_max` = `10` → 400 `bad_value`（`feed.page_size_default` 24 会越界），`teaplayer_config_value` 无该行。
  - `PUT feed.page_size_default=12` → 200，`GET /v1/feed` 返回 12 条；恢复后返回 24、`source=default`。
  - `PUT logging.level=DEBUG` → 200，crawler 立即输出「收到配置变更通知：logging.level」，无需等 30 秒轮询。
  - 测试 token 与测试配置行已删除；`/v1/meta` schema 9，三容器 healthy。
  - `./mvnw -B -q test` 28 个通过。

仍未做（后续）：配置 NOTIFY 使用独立连接以完全避开事务边界、图片缓存 GC、live detail 负缓存/信号量、Testcontainers 集成测试。

## P3.2 补充接线（2026-11-25，`9ef992a`）

- `crawl.hourly.enabled` / `crawl.daily.enabled`：调度触发（`trigger=scheduler`）时若为 false 直接 `skipped("disabled by config")`；手工 CLI 不受影响。
- `crawl.request_interval_ms`：`MacCmsClient.pause()` 改为每次读取配置，默认回退 `CrawlProperties`。
- `search.cache_ttl`：`TtlCache` 支持按条 TTL，搜索缓存按配置时长写入（仍只缓存 `partial=false` 的结果）。
- 部署后 `/health` 200（schema 9），`last_good=9ef992a`，搜索 200（2.4s）；`./mvnw -B -q test` 28 个通过。
