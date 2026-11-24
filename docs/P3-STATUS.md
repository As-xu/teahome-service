# P3 配置中心状态

日期：2026-11-24
版本：P3 核心（配置定义 + 运行时快照 + 管理 API）

## 已交付

| 能力 | 实现 |
| --- | --- |
| 配置定义 | `R__10_seed_config_defs.sql` 可重复迁移，21 个键；只 upsert 定义，不碰值 |
| 配置值 | `teaplayer_config_value.value_json`，仅修改过的键才有行；删除行=恢复默认 |
| 优先级 | `TEAHOME_CFG_<KEY>` 环境变量 > 数据库 > `config_def.default_value` |
| 快照 | `ConfigService` 启动加载，不可变快照原子替换；失败保留上一版 |
| 刷新 | 管理 PUT 后立即 reload；带调度角色的实例每 30 秒 reload 一次 |
| 校验 | 类型（int/long/bool/duration/bytes/url/string）+ min/max + URL 前缀 |
| 管理读取 | `GET /v1/admin/config`，read/admin scope，secret 掩码 |
| 管理写入 | `PUT /v1/admin/config/{key}`，admin scope；reason、expectedVersion、revision、audit 同事务 |
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

## 未完成 / P3.1

1. secret 类型：AES-256-GCM 信封加密、`TEAHOME_CONFIG_KEYS` 密钥环、`key_id` 落库、掩码出口——当前 PUT 明确拒绝 `is_secret` 键。
2. `LISTEN/NOTIFY` 即时跨实例刷新（现为 30 秒轮询 + PUT 本地 reload）。
3. 启动时 def 漂移检查（缺键/未知键/类型变化）。
4. `apply_mode` 的 `next_run` / `restart` 仅记录，未阻止热更新；`next_run` 需在抓取轮次开始时取快照。
5. 跨键约束（如 `search.deadline >= timeout_per_source`）尚未强制。
6. 管理端页面、角色/会话/回滚 UI 属 P4；当前只有 token API。
7. 无 ConfigService 单测/集成测试（仅编译与手工验收）。

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
