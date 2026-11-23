# P0 状态：骨架、schema、Token 闸门

更新：2026-11-23 · 提交 `21c6f20` 起（部署以服务器 `last_good` 为准）

## 验收对照

| 验收项（v2 路线图 P0） | 结果 | 证据 |
| --- | --- | --- |
| 环境 E0–E8 通过 | 通过 | `ENV-STATUS-E1.1.md`；reboot / restart 自愈已验证 |
| `compose up` 后健康端点通 | 通过 | `curl /actuator/health` → `{"groups":["liveness","readiness"],"status":"UP"}`；新增 `/health` 轻量别名 |
| `migrate` 可重复跑 | 通过 | 首次 V2 applied；二次 deploy 输出 `Current version of schema "public": 2`，无新迁移 |
| Token 401 正确 | 通过 | 无 Token → 401 `{"error":"unauthorized",...}`；错误 Token → 401；正确 Token → 200 |
| 21 表迁移 | 通过 | `information_schema` 计数 22（21 核心 + `teaplayer_service_meta`） |

## 已交付

- `V2__core_schema.sql`：21 张表、主/唯一/外键/CHECK、关键索引、`teaplayer_audit_log` append-only 触发器（`teahome.audit_purge=on` 才允许清理）、`schema_version=2`。
- 分类字段按架构评审改为 `category_codes text[]` + `primary_category`，不再用单值 `category_code`（`source_category` 与 `override` 仍保留单值语义）。
- Token 闸门：`/v1/**` 强制 `X-TeaHome-Token`（兼容 `Authorization: Bearer`）；未通过返回 401 JSON + `WWW-Authenticate`；`/health`、`/actuator/**` 不受影响。
- 引导 Token：`TEAHOME_BOOTSTRAP_TOKEN`（来自 `/run/secrets/bootstrap_token`）启动时哈希入库，`ON CONFLICT DO NOTHING` 幂等；明文不落库、不写日志，仅记录 `token_prefix`（当前 `5bad1276`）。
- `/v1/meta`：`apiVersions`、`tokenRequired`、`categoryKeys`（7 类）、`serverTime`（+08:00）、`schemaVersion`。
- 测试 8 个：哈希、上下文、401/200、Bearer、`/health` 不设闸。

## 运行参数

- API：`192.168.5.156:18080`，容器内 8080；健康检查 `/actuator/health`。
- Token：服务器 `/opt/teahome/secrets/bootstrap_token`（444），客户端请求头 `X-TeaHome-Token`；本轮未引入 `ths_` 前缀与 pepper，P1 Token 管理页再统一。
- 库：`teahome`，`teahome_app` 只 DML，`teahome_migrate` 只 DDL；`schema_version=2`。

## 已知边界（P0 明确不做）

- 管理端、配置中心、源注册、抓取、feed、搜索、图片代理均未开始。
- `pg_trgm` 索引、`teaplayer_feed_snapshot` 未建；`feed_snapshot` 属可选表，等首版 feed 时加。
- 令牌尚未限流、未做 scope 细分（当前只有 `read`）、未实现轮换/吊销 API。
- `/health` 只表示进程存活，不代替 Actuator 的 liveness/readiness。
