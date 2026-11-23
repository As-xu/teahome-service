# TeaHomeService 环境执行状态（E1.1 实际结果）

- 执行日期：2026-11-22 23:32 — 2026-11-23 23:25（Asia/Shanghai）
- 服务器：`xs@192.168.5.156 -p 30022`，Ubuntu 24.04.5，Docker 29.9.3，Compose v5.7.0
- 代码版本：`670fcb9`（`teahome-service` main）
- 结论：**E0–E8 全部完成并验证，包括真实 GitHub SSH 拉取、`deploy.sh` 连跑、坏版本自动回滚、reboot 自愈、备份恢复。**

## 阶段状态

| 阶段 | 状态 | 证据 / 备注 |
|---|---|---|
| E0 前置 | 完成 | Java 21.0.9；Docker 29.9.3；410G 可用；sudo 免密；18080/5432/18081 空闲 |
| E1 目录 | 完成 | `/opt/teahome` 755 xs；env/secrets/backups 700；images 10001:10001 |
| E2 镜像/源 | 完成 | daocloud+1ms 镜像；安装 iptables-persistent、age、psql 16；拉取 pg16/jre21/maven 镜像 |
| E3 数据库 | 完成 | 四角色 + 两库；app 可连不可建表；dev 跨库被 pg_hba 拒；仅监听 192.168.5.156:5432 |
| E4 防火墙 | 完成 | DOCKER-USER 四条规则；`systemctl restart docker` 后仍只有四条；reboot 后仍生效 |
| E5 仓库/证书 | 完成 | deploy key 已由用户加入；`ssh -T git@github-teahome` → `Hi As-xu!`；origin 已切 SSH；`git pull --ff-only` 成功 |
| E6 骨架 | 完成 | Spring Boot 4.2.0 + Java 21；Flyway V1；`mvnw package` 本地成功 |
| E7 镜像/发布 | 完成 | 真实 `deploy.sh` 首次 17s、后续约 10s；migrate 非 Web 退出；api healthy；坏版本回滚演练通过 |
| E8 备份 | 完成 | age 公钥 `/opt/teahome/secrets/backup.pub`，私钥 `/root/teahome-backup.age.key`（400）；备份可解密；`pg_restore` 到 `teahome_restore` 成功；root cron 03:30 |

## 实测结果

- `curl http://192.168.5.156:18080/actuator/health` → `{"groups":["liveness","readiness"],"status":"UP"}`
- `flyway_schema_history`：version=1, description=service meta, success=t
- `teaplayer_service_meta`：schema_version=1
- `ss -lntp`：只有 `192.168.5.156:5432` 与 `192.168.5.156:18080`
- app 连接用户：`teahome_app`（Hikari 连接）
- `create table` as teahome_app → `ERROR: permission denied for schema public`
- `teahome_dev` 连 `teahome` → `no pg_hba.conf entry ...`
- 备份首字节 `50 47 44 4d 50`（PGDMP）
- reboot 后：容器自动起、端口在、DOCKER-USER 四条规则在、health UP
- `systemctl restart docker` 后：四条规则不叠加、容器自愈
- 真实 GitHub 发布：`deploy.sh` 两次成功，`last_good=670fcb9`，镜像 `teahome-service:670fcb9`
- 回滚演练：`drill/bad` 分支让应用以非 Web 方式启动，`deploy.sh` 退出 1、输出 `rollback to 670fcb9`、自动用旧镜像恢复，health 200；HEAD 仍停在 `drill/bad`（未脱离分支），随后切回 main 并重新发布成功

## 与 E1.1 计划的偏差（需记录）

1. **Spring Boot 4.2.0**：start.spring.io 已不提供 3.5，实际使用 4.2.0 + Java 21。
2. **secrets 权限 0444 而非 0400**：compose 文件型 secret 是 bind mount 宿主文件，0400 时容器内 postgres(uid 70)/app(uid 10001) 读不到；目录仍 700。
3. **ufw 被 apt 卸载**：安装 iptables-persistent 时 apt 移除了未启用的 ufw；防火墙改由 DOCKER-USER + netfilter-persistent 承担。
4. **migrate 容器加 `--spring.main.web-application-type=none`**：否则 Flyway 跑完后 Web 进程不退，`compose run` 永久阻塞。
5. **Maven 使用阿里云镜像**：Maven Central 在服务器上拉取极慢（>15min 未完成），Dockerfile 增加 `deploy/maven/settings.xml` 指向 `https://maven.aliyun.com/repository/public`，构建约 2 分钟。
6. **schema 授权**：`GRANT USAGE, CREATE ON SCHEMA public TO teahome_migrate`（init 脚本已含）。
7. **DOCKER-USER 脚本先 flush 再建**：原按 comment 删除的写法匹配不到含 `-p tcp --dport` 的完整规则，重启后会叠加；已改为 `iptables -F DOCKER-USER` 后按固定顺序重建。
8. **服务器远端为 SSH**：`origin=git@github-teahome:As-xu/teahome-service.git`；HTTPS 的 git 端点在服务器上不可用（超时/重置），SSH 正常。

## 发布方式（现状）

```bash
# 本地
cd D:\\Project\\TeaPlayer\\teahome-service
git add -A && git commit -m "..." && git push origin main

# 服务器
ssh xs@192.168.5.156 -p 30022
/opt/teahome/app/deploy.sh
```

`deploy.sh`：`compose config -q` → `git pull --ff-only` → 以 commit sha 作为镜像 tag → build → migrate 闸门 → `up -d --wait` → 写 `last_good`；失败用上一版镜像回滚，不改 git。

## 未验证项

- TV 端访问 18080 未验；手机（TAS-AN00 / 192.168.5.161）已用浏览器实测，返回 `{"groups":["liveness","readiness"],"status":"UP"}`。
- 非白名单来源的 5432 DROP 未从外部主机实测（规则顺序已确认）。
- 管理端（18081）本期未启动，属 P0 之后。
