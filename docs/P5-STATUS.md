# P5-STATUS：资源与运维加固

> 版本：P5（部署 `8ff29cd`，schema 11）
> 日期：2026-11-25

## 1. 交付内容

| 项 | 实现 | 位置 |
| --- | --- | --- |
| 封面缓存 GC | 每日 03:20 按 `image.cache_ttl_days` 与 `image.cache_max_bytes` 清理 DB 行与文件；按 `fetched_at` 从旧到新，容量降到 95% 目标 | `api/img/ImageCacheGc.java` |
| 审计保留 | `logging.audit_retention_days`（默认 180，30–3650）；每日 04:40 调 `teaplayer_purge_audit(days)` | `ops/AuditRetentionJob.java`、`V13__audit_purge.sql` |
| 审计清理函数 | `SECURITY DEFINER`，owner 为 migrate；仅允许 30–3650 天；app 只能 EXECUTE，不能直接 DELETE | `V13__audit_purge.sql` |
| live 详情背压 | 全局 4 permit、最多等 2s；超时返回 503；404/忙写入 60s 负缓存 | `api/item/ItemService.java` |
| 详情 ID 校验 | `sourceId` 仅 `[a-z0-9_-]{1,32}`、`itemId` 仅 1–20 位数字，阻断上游模板注入 | `api/item/ItemService.java` |
| GC 卷 | crawler 挂载 `/opt/teahome/data/images:/data/images` | `compose.yaml` |

配置键 `logging.audit_retention_days` 由可重复迁移 `R__10_seed_config_defs.sql` 幂等写入，可在管理端配置页热改。

## 2. 验收

- `API_TAG=8ff29cd`，四容器 healthy；`/health` → `{"status":"UP","schemaVersion":"11"}`。
- `teaplayer_service_meta.schema_version=11`；`SELECT teaplayer_purge_audit(180)` 返回 0。
- 插入一条 `occurred_at=now()-200 days` 的测试审计行，调用函数返回 1，测试行被删除（0 行剩余）。
- live 详情负缓存：`GET /v1/items/hongniu/999999999` 首次 2.88s / 404，第二次 0.017s / 404。
- 详情注入：`GET /v1/items/hongniu/123%26ac%3Dlist` → 404（不再拼进上游 query）。
- crawler 容器挂载 `/data/images`；32 个单测通过。

## 3. 已知缺口

- 图片 SSRF 仍是“解析后校验 + 立即请求”，未做 DNS pin；JDK HttpClient 无法直接 pin IP，需自建 socket 或代理解析器。风险面已由 allow_hosts、禁重定向、私网/环回/CIDR 拒绝、5MiB/10s、端口 80/443 收敛。
- 容器出站私网阻断（DOCKER-USER egress）未启用：crawler 需要访问宿主 `192.168.5.156:5432`，规则需为“仅放行 DB 端口 + 拒绝其余私网”，需单独演练，避免抓取断连。
- 封面 GC 未做“立即触发”入口，验证依赖每日 03:20 调度；可后续在管理端加“立即清理”按钮。
- 无 Testcontainers 集成测试；GC/审计任务只有手工验收。
- 管理端过期会话清理仍只在登录 issue 时执行（`purgeExpired`），没有独立定时器。

## 4. 后续建议

- P5.1：图片 DNS pin + 出站私网阻断演练（先 dry-run iptables，保留 SSH 回滚）。
- P5.1：管理端“立即清理封面缓存/审计”操作（sudo + 审计）。
- P6：Flutter/TV 接入 `TeaHomeSource`。
