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
| GC 卷 | P5.1 起由 admin 挂 `/opt/teahome/images:/data/images` 执行运维任务（原 crawler 挂载目录与 API 不一致，已修正） | `compose.yaml` |

配置键 `logging.audit_retention_days` 由可重复迁移 `R__10_seed_config_defs.sql` 幂等写入，可在管理端配置页热改。

## 2. 验收

- `API_TAG=8ff29cd`，四容器 healthy；`/health` → `{"status":"UP","schemaVersion":"11"}`。
- `teaplayer_service_meta.schema_version=11`；`SELECT teaplayer_purge_audit(180)` 返回 0。
- 插入一条 `occurred_at=now()-200 days` 的测试审计行，调用函数返回 1，测试行被删除（0 行剩余）。
- live 详情负缓存：`GET /v1/items/hongniu/999999999` 首次 2.88s / 404，第二次 0.017s / 404。
- 详情注入：`GET /v1/items/hongniu/123%26ac%3Dlist` → 404（不再拼进上游 query）。
- crawler 容器挂载 `/data/images`；32 个单测通过。

## 3. 已知缺口

P5 列出的五项缺口已由 P5.1 收口，见 `docs/P5.1-STATUS.md`：图片 DNS pin、容器出站私网阻断（含演练与回滚物）、管理端“立即清理”入口、过期会话每小时定时清理、抓取/写库集成测试。仍存在的限制：

- 集成测试不依赖 Testcontainers，需要外部可达 PostgreSQL（`TEAHOME_IT_DB_URL` 等），本地无该变量时跳过。
- 出站阻断只覆盖容器 → 私网方向；公网出站仍全放行（按需再收紧到 80/443 之外的目的端口）。
- SSH 密码登录保持开启（用户决定，见 `docs/SECURITY-AUDIT.md`）。

## 4. 后续建议

- P6：Flutter/TV 接入 `TeaHomeSource`（客户端需放行 192.168/16、cleartext、`/v1/img` 带 Token）。
