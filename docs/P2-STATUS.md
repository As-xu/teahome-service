# P2 状态 — 只读 API 与封面代理

日期：2026-11-24
分支：`main`
基线：P1 整改 `9653d56`；P2 实现 `b04d18f`

## 交付内容

| 接口 | 说明 |
| --- | --- |
| `GET /v1/feed` | 分组游标分页，`category` 过滤，`size` 1–60（默认 24），keyset `(feed_seq,id)` |
| `GET /v1/categories` | 7 类计数（含 `other`），60 秒缓存 |
| `GET /v1/items/{sourceId}/{itemId}` | 库内详情（线路/剧集/同组换源）；库外回源不落库，`origin=live`；过期 410 |
| `GET /v1/search` | 6 个 MacCMS 源并发，单源 3.5 秒、整体 5 秒，`partial`/`failed[]`，10 分钟缓存 |
| `GET /v1/img/{key}` | 只接受 64 hex key；按 key 查库找 URL；落盘 `/data/images`，ETag/304，30 天 immutable |

支撑：`V6__cover_keys.sql`、`V7__schema_version_5.sql`、`ItemWriter.cover_key`、统一错误 envelope、`X-Request-Id`、滑动窗口限流。

## 验收证据（服务器 192.168.5.156）

- 部署 `b04d18f`：Flyway V6、容器 healthy。
- daily run=8：6/6 源、252 页、4,957 条、0 失败、742,424ms；`play_hash` 非空 4,957；`cover_key` 非空 4,956（1 条无封面）。
- 数据：groups 2,277、empty 0、null `feed_seq` 0、`sum(item_count)=4,957`、bad counts 0、lines 9,256、episodes 291,479、bad primary 0；`expiry >= now()` 与 `expiry <= now()+8d` 全满足。
- `/v1/feed?size=2`：`nextCursor`、`hasMore`、`lineCount`、`sources`、`schemaVersion=4`、`categoryRuleVersion=cat-v1` 均正确；第二页无重复。
- 非法 size → 400 `bad_size`；非法 cursor → 400 `bad_cursor`；无 token → 401。
- `/v1/items/zuid/133380`：`origin=db`，`zuidam3u8/hls/primary`，siblings 1；不存在 → 404 envelope。
- `/v1/img/<key>`：200 `image/jpeg`，`If-None-Match` → 304。
- `/v1/search?q=绝世`：合并 5 源结果；`partial=true`，jizy 失败。
- 限流：`/v1/search` 2/s、`/v1/img` 120/min、其余 30/s；短窗口内实测未触发。

## 已知偏差 / 限制

1. **jizy 搜索 403**：`https://jyzyapi.com/api.php/provide/vod/?ac=list&wd=...` 对当前 UA 返回 403；错误码已从 `blocked_host` 修正为 `http_403`，其它 5 源正常，接口 `partial=true`。
2. **feed 的 `schemaVersion` 仍为 4**：`b04d18f` 时 V6 未 bump `service_meta`；V7 已补 `schema_version=5`，下次部署后为 5。
3. **图片接口需 Token header**：客户端（P6）需为图片请求带 `X-TeaHome-Token`；浏览器直开不可用。
4. **live 详情无封面代理**：回源结果不落库，`coverProxy=null`；P6 可在本地模式直接显示源封面。
5. **无集成测试**：P2 仅 `CursorTest` + 既有 24 个单测；feed 分页、403 分类、图片 SSRF 依赖人工验收。
6. **无 feed ETag**：仅 `Cache-Control: private,max-age=30`；如需 304 需按 `max(feed_seq)` 生成 ETag。
7. **源级串行**：search 并发，但 crawler 仍逐源；daily 约 12 分钟。
8. **`vod_time` 解析失败回退 now**：老片可能被顶到 feed 前部，待 P2.1 修。

## 运维命令

```bash
# 部署
ssh -p 30022 xs@192.168.5.156
cd /opt/teahome/app && sudo ./deploy.sh

# 带 token 验收
T=$(cat /opt/teahome/secrets/bootstrap_token)
curl -s -H "X-TeaHome-Token: $T" "http://192.168.5.156:18080/v1/feed?size=2"
curl -s -H "X-TeaHome-Token: $T" "http://192.168.5.156:18080/v1/search?q=斗罗"
```

## 下一步

C 阶段：三位专家复核 P2 diff 与部署脚本；修完再上线。之后 P3 配置中心 / P4 管理端。
