# P4 状态：回环 Web 管理端

> 版本：P4 v1，2026-11-25
> 范围：7 页管理端（登录、概览、配置、源、抓取、Token/管理员、审计），只监听 `127.0.0.1:18081`，通过 SSH 隧道访问。

## 1. 交付内容

| 页面 | 路径 | 功能 |
| --- | --- | --- |
| 登录 | `/admin/login` | 账号密码（Argon2id）、失败退避、会话与 CSRF cookie |
| 概览 | `/admin` | 条目/分组/源/过期/最近抓取/最近审计/DB 大小；sudo 提权入口 |
| 配置 | `/admin/config` | 22 键列表、来源/版本、可直接编辑 hot 键；写入 revision + audit |
| 源 | `/admin/sources` | 启用/停用源（需 sudo），失败数与最近成功时间 |
| 抓取 | `/admin/crawl` | 最近 30 次 run/task，手动触发（10 分钟一次，异步） |
| Token/管理员 | `/admin/tokens` | 创建/吊销 API Token（只显示一次明文）；创建管理员、重置密码 |
| 审计 | `/admin/audit` | 最近 200 条，按 action/actor/result 过滤，只读 |

技术栈：Spring Boot 4.2.0 + Thymeleaf + JDBC；无 npm、无前端构建；服务器端渲染表单。

## 2. 安全模型

- **仅回环**：`server.address=127.0.0.1`、端口 18081；`ss` 只显示 `[::ffff:127.0.0.1]:18081`。
- **Host 校验**：只接受 `127.0.0.1` / `localhost` / `[::1]`，否则 421。
- **peer 校验**：`getRemoteAddr()` 必须是 loopback，否则 403（依赖 SSH 本地转发）。
- **会话**：DB 存储，`ths_sid`/`ths_csrf` 只存 SHA-256；HttpOnly + SameSite=Strict + Max-Age 12h；空闲 30 分钟、绝对 12 小时、最多 3 个会话。
- **CSRF**：双提交 cookie + Origin/Referer 必须 `http://127.0.0.1:18081`；登录 POST 只校验 Origin。
- **sudo**：配置/源/Token/管理员等写操作需 5 分钟内重新输入密码；服务端只认 `sudo_until`。
- **密码**：Argon2id（m=65536,t=3,p=1，信号量 2）；无默认密码；`--reset-admin-password` 初始化/重置。
- **审计**：写操作写 `teaplayer_audit_log`；P3.2 已限制 `teahome_app` 对审计/配置历史 UPDATE/DELETE。
- **Cookie 偏差**：因回环 HTTP 无法使用 `__Host-`/Secure，使用 `ths_sid` 与 `ths_csrf`，SameSite=Strict；若以后上 TLS，改 `__Host-` + Secure。

## 3. 部署

```bash
cd /opt/teahome/app
sudo ./deploy.sh
sudo ./deploy/teahome-admin-reset.sh   # 首次创建/重置 admin
ssh -N -L 18081:127.0.0.1:18081 xs@192.168.5.156 -p 30022
# 浏览器打开 http://127.0.0.1:18081/admin/login
```

compose 的 `teahome-admin`：`network_mode: host`、绑定 127.0.0.1、`TEAHOME_DB_URL=jdbc:postgresql://192.168.5.156:5432/teahome`、`teahome_app`、secrets `db_app`、read_only/tmpfs/cap_drop。

## 4. 验收记录（2026-11-25）

- `deploy.sh`：V12 applied，schema 10；api/crawler/admin/db 四容器 healthy；`/health` 返回 `schemaVersion=10`。
- `ss` 仅 `[::ffff:127.0.0.1]:18081`；从局域网直接访问 18081 不可达。
- `/admin/login` 200；错误 Host 421；静态资源 200；匿名 `/admin` 302 到登录；无 CSRF POST 403。
- 登录成功 302 `/admin`；概览/配置/源/抓取/Token/审计均 200，标题正确。
- Cookie：`HttpOnly; SameSite=Strict; Max-Age=43200`。
- sudo + 配置写：`feed.page_size_default=12` 写入成功（version 6），`/v1/feed` 默认返回 12 条；恢复 24（version 7）；审计 `config.update|ok`、`admin.sudo|ok`、`admin.login|ok`。
- admin 容器停止不影响 18080（未做破坏性实测，仅架构上独立）。

## 5. 已知缺口

- 无管理员页面集成测试（仅 `AdminAccessFilterTest` 4 例 + 现有 32 个单测）。
- `AdminSessionService` 的会话 SQL 未做自动化契约测试；依赖线上手工验收。
- 源开关未实际切换生产源（避免抓取副作用），仅代码路径与审计。
- 手动抓取只允许一次/10 分钟，但无“取消运行中任务”入口。
- 无 TOTP/WebAuthn（P4 计划外）；SSH 私钥是第二因素。
- 审计表无自动归档/清理（180 天策略待运维任务）。
- 管理端登录页未做国际化；中文文案硬编码。
- `AdminAccessFilter` 对 `Host` 的 `[::1]` 形式仅做字符串匹配，未做完整 IPv6 规范化。

## 6. 后续

- P4 复核：请专家审阅本次 diff（`02f9d3c` 及后续修复），重点看 CSRF/会话、模板转义、审计一致性。
- P5 可做：图片缓存 GC、live detail 负缓存、Testcontainers 集成测试、审计归档。
- 恢复点：管理端无独立数据，回滚镜像即可；V12 只加列/索引，向后兼容。

## 7. 专家复核修复（`727768a` 及后续）

三专家复核发现并已修复：

- **手动抓取空转**：`AdminCrawlController` 改传 `trigger="manual"`；executor 拒绝返回页面错误；审计移到入队成功之后；10 分钟限流只统计 `triggered_by='manual'` 的 run（避免被每小时调度误判）。
- **admin 垂直提权**：创建 owner、重置 owner 密码都要求当前会话是 owner；重置密码会吊销目标用户全部会话；expired token 天数 clamp 0..3650；revoke 检查行数。
- **会话淘汰/事务**：`issue()` 改为事务内先 `purgeExpired()`，按 `last_seen_at DESC` 保留最新；新增 `revokeAllForUser`。
- **配置并发与 JSON**：`update()` 用 `pg_advisory_xact_lock(hashtext('cfg:<key>'))`，`expectedVersion` 从 DB 当前 version 校验，value version 与 revision 分开递增；`ConfigService.quote()` 转义换行/控制字符。
- **admin 进程暴露 `/v1`**：`AdminAccessFilter` 只放行静态资源与 `/health`、`/actuator/health`、`/error`，其余路径要求管理员会话；实测 `/v1/meta` 返回 302。
- **CSRF Referer**：无 Origin 时从 Referer 取 `scheme://authority` 再比较，带路径的 Referer 不再误拒。
- **登录/ sudo 时序与退避**：用户不存在也执行 dummy Argon2；`/admin/sudo` 接同一 IP 退避。
- **健康检查与初始化**：admin healthcheck 改 `/health`（校验 DB+schema）；`01-roles.sh` 用 `to_regclass` 保护 REVOKE/GRANT，空库初始化不再失败。
- **审计参数类型**：`target_id` 为 NULL 时显式 `CAST(:targetId AS text)`，手动抓取不再 500。

验证：32 个测试通过；部署 `727768a` 后四容器 healthy；`/v1;x/feed`、`/%76%31/feed` 均 401；错误 Host 421；admin `/v1/meta` 302；手动触发产生 `run=60 status=ok items=109`。

### 7.1 复核修复后的线上验证（`e7e01e3`）

- 部署 `e7e01e3`，四容器 healthy；`API_TAG=e7e01e3`；`/health` 200（schemaVersion=10）。
- 手动抓取：`POST /admin/crawl/trigger` 返回 `?saved=已触发 hourly`；`teaplayer_crawl_run` id=62 `status=ok`、`items_fetched=107`、`items_upserted=107`、`triggered_by=manual`；审计 `crawl.trigger|ok|manual hourly`。限流只统计 manual run，不再被每小时调度误判。
- 垂直提权：创建 admin 角色测试用户后，尝试创建 owner、重置 owner 密码均返回 **403**（此前 500），随后测试用户已删除。
- 会话吊销：viewer 会话登录后由 owner 重置其密码，旧 `ths_sid` 再访问 `/admin` 返回 302。
- CSRF Referer：仅带带路径 Referer、无 Origin 时登录与 sudo 均通过（302）。
- admin 进程：`/v1/meta` 返回 302（不再暴露 API），`/health` 200。
- 仍保留的偏差：HTTP 回环 Cookie 无 `__Host-`/Secure；无 TOTP；管理端无集成测试（仅 32 个单测 + 手工验收）。
