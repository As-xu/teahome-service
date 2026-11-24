# P0–P3.1 专家复核与修复记录

日期：2026-11-24
复核者：Software Architect / Security Architect / Backend Architect（只读）
处理：高优先级问题已在本分支修复并部署；其余列入 P3.2 / P4 清单。

## 已修复并线上验证

| 级别 | 问题 | 修复 | 验证 |
| --- | --- | --- | --- |
| P0 安全 | 分号 matrix 路径 `/{v1};x/feed` 绕过 Token 与限流（`%76%31` 只修了一类） | 新增 `config/PathGate`，用 `UrlPathHelper.getPathWithinApplication` 与 MVC 同一套规范化；两个过滤器都改走它；只对 `/v1` 鉴权 | `/v1;x/meta`、`/v1;x/feed`、`/v1;x/img/...` 全部 401 |
| P0 正确性 | 配置审计 `before` 用字符串拼 JSON，字符串/URL 类配置第二次 PUT 必 500 | 改为 `jsonb_build_object('value', CAST(:before AS jsonb))`；参数始终传合法 JSON（无旧值时传 `null`） | `logging.level` 连续 PUT INFO→DEBUG 均 200，审计行 `{"value":"INFO"} → {"value":"DEBUG"}` |
| P0 数据 | detail 缺封面/备注/地区时无条件写 NULL，清空已有元数据 | UPDATE 改 `COALESCE(:cover, cover_url)` 等 5 列 | 代码审查 + 单测；详细回归待集成测试 |
| P0 数据 | 新记录遇到旧 `vod_time` 时 `expires_at=now+5m`，条目 5 分钟后消失，抓取反复插入删除 | `TtlPolicy.expiry` 改为 `max(vod+ttl, now+ttl)`，删除 5 分钟 floor；更新测试 | `TtlPolicyTest` 通过（全量 28 个测试） |
| P0 安全 | V3 种子把稀饭源 Supabase `apikey/Bearer` 明文写入 Git 与 Flyway | V10 将 `xifan.spec.headers` 置空；schema_version=8；**密钥仍必须由你在源站轮换/吊销** | DB 中 `headers={}`；`/v1/meta` schema 8 |
| P1 可用性 | api 关闭抓取调度后配置只依赖 LISTEN，漏通知就永久陈旧 | 新增独立 `ConfigPoller` 守护线程（30s，role=migrate 跳过） | api 日志「配置轮询已启动，间隔 30000ms，role=api」；删除行后 30s 内生效 |

## 未修复，按优先级进入 P3.2

1. **审计与配置表完整性**：`teahome_app` 仍有 `audit_log/config_revision` 的 UPDATE/DELETE；触发器允许应用自己 `SET teahome.audit_purge='on'` 后删除。需收回权限 + 改触发器校验 `current_user`。
2. **图片 SSRF DNS rebinding**：`ImageFetcher` 先校验域名再按域名连接，第二次解析可指向内网；需一次解析后按 IP 连接（自定义 Dns/HTTP 客户端）。
3. **401 退避按 IP**：NAT/反代后共享桶，可被用来打同网段其他客户端；应按 token 前缀/组合键并改进清空策略。
4. **purge 单事务**：一次 retention 把所有批次包在一个事务，`statement_timeout=120s` 下大批量会整段回滚；需每批独立事务（REQUIRES_NEW/TransactionTemplate）。
5. **配置写路径**：跨键约束只在 reload 时检查（单键合法但整表冻结）；`notify/reload` 在提交前执行；并发 PUT 的 revision 号仍需 DB CAS/序列。
6. **22 个配置键只接了 6 个**：`crawl.*`、`search.deadline`、`image.max_bytes`、`image.cache_max_bytes` 等仍是硬编码，故障时无法用它止血。
7. **图片缓存无 GC**：`image.cache_max_bytes/cache_ttl_days` 未执行，`/data/images` 可无限增长；`hit_count/last_hit_at` 未写。
8. **实时详情无并发上限/负缓存**：未命中条目会在线程内同步回源（8s），限流 30/s 仍可打满 Tomcat；建议信号量 + 60s 负缓存。
9. **无集成测试/CI**：`ItemWriter`、`FeedService`、`ConfigController` 等无 DB 级测试；建议 Testcontainers + 真实容器路径用例（含 `;matrix` 路径）。
10. **健康检查**：`/health` 不查 DB/不校验 schema，迁移缺失时仍 UP；需 fail-fast + DB 探针。

## 复核结论

- 架构方向（模块化单体、配置中心、crawl/read 分离）成立，P0–P3.1 的阻断性缺陷集中在**输入路径规范化、审计 JSON、详情写空、TTL 边界**，已处理。
- 当前不建议把服务暴露到公网；局域网内也应先完成第 1/2/3 项。
- 泄密的 Supabase key 无法靠改代码撤回，请立即在源站轮换；轮换后再决定 xifan 是否启用。
