# TeaHomeService P2 状态

更新时间：2026-11-24（Asia/Shanghai）
代码提交：`3fd0e05 fix(p2): close encoded-path auth bypass and harden feed, image and search`
线上镜像：`teahome-service:3fd0e05`

## 已交付接口

| 接口 | 说明 |
| --- | --- |
| `GET /v1/feed?category=&cursor=&size=` | 分组流，默认 24、上限 60；游标绑定分类，非法返回 `bad_cursor` |
| `GET /v1/categories` | 7 类计数（movie/tv/anime/variety/documentary/short/other） |
| `GET /v1/items/{sourceId}/{itemId}` | 库内详情（线路/剧集/换源）；库外回源，不落库 |
| `GET /v1/search?q=&category=` | 6 个 MacCMS 源并发，5 秒总截止，`partial/failed` |
| `GET /v1/img/{key}` | 只认 64 hex 封面 key；按库内 URL 抓取、落盘、ETag/304 |

统一错误 envelope：

```json
{"error":{"code":"...","message":"...","requestId":"...","serverTime":"..."},"retryAfterMs":0}
```

## 验收证据

- `/actuator/health` → 200 `{"status":"UP"}`。
- `curl --path-as-is 'http://192.168.5.156:18080/%76%31/feed?size=2'` → **401**（修复前为 200）。
- `/v1/meta` 带 token → `schemaVersion=5`。
- `/v1/feed?size=2` → 200，`nextCursor`、`hasMore`、`lineCount`、`sources` 正常；非法 size/cursor → 400。
- `/v1/items/zuid/133380` → 200 `origin=db`；不存在条目 → 404 envelope。
- `/v1/search?q=绝世` → 200，`partial=true`，`failed=[{"sourceId":"jizy","code":"http_403"}]`。
- `/v1/img/<key>` → 200 `image/jpeg`；`If-None-Match` 精确匹配 → 304；错误 ETag → 200；`Cache-Control: private, immutable`。
- 数据：items 4,957；groups 2,277；`play_hash` 全量非空；`cover_key` 4,956（1 条无封面）；`feed_seq` 无空值；`item_count` 不一致 0；`expires_at` 全部在 8 天窗口内。

## 本轮专家复核后修复

- **鉴权绕过（Critical）**：`TokenAuthFilter` 原先按原始 `getRequestURI()` 跳过非 `/v1` 路径，`/%76%31/feed` 可无 token 访问。现在过滤器映射 `/*`，按 URL 解码后的路径判断，非法编码兜底不跳过；新增单元测试覆盖编码路径。
- 限流过滤同样改为解码路径，并按 `read/search/img` 分桶；429 带 `Retry-After` 与统一 envelope。
- `ApiExceptionHandler` 区分 404/405/406/415，不再把框架异常统一吞成 500。
- feed 的 `sources`/`lineCount`、详情 `siblings` 只统计未过期条目。
- 游标绑定分类；`TtlCache` 满时淘汰最旧而不是清空；搜索只缓存无失败结果。
- 搜索改用源 spec 的 `search` endpoint；错误码区分 `http_403`、`blocked_host`、`timeout`。
- 抓取锁改为全局 `crawl`（hourly/daily 互斥）；条目 INSERT 加 `ON CONFLICT DO NOTHING RETURNING`，冲突后回退更新路径。
- 图片：端口仅 80/443；拒绝 `100.64/10`、`198.18/15`、`192.0.0.0/24`、`240/4` 等地址；抓取并发信号量 4；失败写 `image_cache(status=failed)` 并 60 秒负缓存；锁释放；临时文件清理；ETag 精确匹配；缓存改 `private`。

## 已知偏差 / 未完成

- 图片抓取仍是 check-then-connect，未按解析后的 IP 建连（DNS rebinding 的 TOCTOU 未彻底消除）。
- 库外详情回源仍是最多 3 次、单次 25 秒，且跑在 Tomcat 请求线程；无短超时/负缓存。
- feed 与详情读路径未包 `@Transactional(readOnly=true)`，翻页期间可能看到部分更新。
- `/v1/search` 固定 6 线程、无界队列；`invokeAll` 排队任务可能被取消并计入 `timeout`。
- categories 按 `primary_category` 计数，feed 按 `category_codes` 过滤，两者口径不同。
- 图片接口仍需 `X-TeaHome-Token`，浏览器 `<img>` 不能直接带 header；客户端 P6 需用带 header 的图片加载器或后续签名 URL。
- jizy 搜索返回 403（源站风控），当前保留 `partial`；未尝试浏览器 UA/Referer。
- 无 Testcontainers/集成测试；P2 验收仍以服务器 curl + SQL 不变量为主。
- 5432 对局域网开放为用户选择的 B 方案；`172.16.0.0/12` 放行范围偏宽，`pg_hba` 仍有 `local all all trust`，无 TLS。
- 封面 `cover_key` 无独立索引；`SchemaVersionProvider` 数值与 Flyway 版本是两套编号，已用 V7 将 meta 对齐为 5。
- V7 早前已在库中应用，本次部署出现 checksum mismatch；已手工把 `flyway_schema_history` 中 version 7 的 checksum 修为本地值 `-1842048474` 后重新部署成功（后续不要再改 V7 文件内容）。

## 运维命令

```bash
# 部署（服务器）
cd /opt/teahome/app && sudo ./deploy.sh

# token
sudo cat /opt/teahome/secrets/bootstrap_token

# 手动抓取（必须带 env-file，否则 API_TAG 可能解析成 dev）
cd /opt/teahome/app
sudo docker compose --env-file /opt/teahome/env/.env --profile crawl run --rm teahome-crawl \
  --spring.main.web-application-type=none --teahome.crawl.once=hourly

# 检查
curl -s http://192.168.5.156:18080/actuator/health
curl -s -H "X-TeaHome-Token: $TOKEN" http://192.168.5.156:18080/v1/feed?size=2
```
