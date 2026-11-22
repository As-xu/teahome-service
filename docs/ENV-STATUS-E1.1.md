# TeaHomeService 环境执行状态（E1.1 实际结果）

- 执行日期：2026-11-22 23:32 — 2026-11-23 00:20（Asia/Shanghai）
- 服务器：`xs@192.168.5.156 -p 30022`，Ubuntu 24.04.5，Docker 29.9.3，Compose v5.7.0
- 代码版本：`cb15539`（`teahome-service` main）
- 结论：**E0–E4、E6–E8 已落地并逐项验证；E5 只差「把服务器 deploy key 加进 GitHub 仓库」这一步**。真实 GitHub `git pull` 目前走不通（HTTPS 到 github.com 的 git 端点持续超时/重置），所以 `deploy.sh` 的拉取路径用本地 bare mirror 做了等价验证，真实拉取待 key 加好后复测。

## 阶段状态

| 阶段 | 状态 | 证据 / 备注 |
|---|---|---|
| E0 前置 | 完成 | Java 21.0.9；Docker 29.9.3；410G 可用；sudo 免密；18080/5432/18081 空闲 |
| E1 目录 | 完成 | `/opt/teahome` 755 xs；env/secrets/backups 700；images 10001:10001 |
| E2 镜像/源 | 完成 | daocloud+1ms 镜像；安装 iptables-persistent、age、psql 16；拉取 pg16/jre21/maven 镜像 |
| E3 数据库 | 完成 | 四角色 + 两库；app 可连不可建表；dev 跨库被 pg_hba 拒；仅监听 192.168.5.156:5432 |
| E4 防火墙 | 完成 | DOCKER-USER 四条规则；`systemctl restart docker` 后仍只有四条（脚本已改为先 flush 再建）；reboot 后仍生效 |
| E5 仓库/证书 | **部分** | 服务器已生成 deploy key、pin host key、清失效代理；**待用户把公钥加到仓库 Deploy keys**；服务器当前用 HTTPS clone |
| E6 骨架 | 完成 | Spring Boot 4.2.0 + Java 21；Flyway V1；`mvnw package` 本地成功（2m04s） |
| E7 镜像/发布 | 完成（真实 pull 待 key） | 镜像 `teahome-service:cb15539/dev`；migrate 非 Web 退出；api healthy；deploy.sh 连跑两次通过（本地 mirror）；重启自愈通过 |
| E8 备份 | 完成 | age 公钥 `/opt/teahome/secrets/backup.pub`，私钥 `/root/teahome-backup.age.key`（400）；备份可解密；`pg_restore` 到 `teahome_restore` 成功；root cron 03:30 |

## 实测结果

- `curl http://192.168.5.156:18080/actuator/health` → `{"groups":["liveness","readiness"],"status":"UP"}`
- `flyway_schema_history`：version=1, description=service meta, success=t
- `teaplayer_service_meta`：schema_version=1
- `ss -lntp`：只有 `192.168.5.156:5432` 与 `192.168.5.156:18080`
- app 连接用户：`teahome_app`（2 条 Hikari 连接）
- `create table` as teahome_app → `ERROR: permission denied for schema public`
- `teahome_dev` 连 `teahome` → `no pg_hba.conf entry ...`
- 备份首字节 `50 47 44 4d 50`（PGDMP）
- reboot 后：容器自动起、端口在、DOCKER-USER 四条规则在、health UP
- `systemctl restart docker` 后：四条规则不叠加、容器自愈
- deploy.sh 连跑两次（origin 临时指向 `/opt/teahome/mirror.git`）：两次均 `Healthy`，`last_good=cb15539`

## 与 E1.1 计划的偏差（需记录）

1. **Spring Boot 4.2.0**：start.spring.io 已不提供 3.5，实际使用 4.2.0 + Java 21。
2. **secrets 权限 0444 而非 0400**：compose 文件型 secret 是 bind mount 宿主文件，0400 时容器内 postgres(uid 70)/app(uid 10001) 读不到；目录仍 700。
3. **ufw 被 apt 卸载**：安装 iptables-persistent 时 apt 移除了未启用的 ufw；防火墙改由 DOCKER-USER + netfilter-persistent 承担。
4. **migrate 容器加 `--spring.main.web-application-type=none`**：否则 Flyway 跑完后 Web 进程不退，`compose run` 永久阻塞。
5. **Maven 使用阿里云镜像**：Maven Central 在服务器上拉取极慢（>15min 未完成），Dockerfile 增加 `deploy/maven/settings.xml` 指向 `https://maven.aliyun.com/repository/public`，构建约 2 分钟。
6. **pg_hba 之外还补了 schema 授权**：`GRANT USAGE, CREATE ON SCHEMA public TO teahome_migrate`（init 脚本已含）。
7. **DOCKER-USER 脚本先 flush 再建**：原按 comment 删除的写法不匹配含 `-p tcp --dport` 的完整规则，重启后会叠加；已改为 `iptables -F DOCKER-USER` 后按固定顺序重建。
8. **服务器 git 远端暂用 HTTPS**：`origin=https://github.com/As-xu/teahome-service.git`；deploy key 加好后应改为 `git@github-teahome:As-xu/teahome-service.git`。

## 待用户完成的一步（E5）

仓库 `As-xu/teahome-service` → Settings → Deploy keys → Add deploy key，粘贴（只读，不要勾 write）：

```
ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIMSkYNFTnWq7UvsJj0FGdd6zlwOYPJ1n85JXN6Krh5E4 teahome-deploy@192.168.5.156
```

加好后在服务器执行：

```bash
ssh -T git@github-teahome          # 期望 Hi As-xu!
git -C /opt/teahome/app remote set-url origin git@github-teahome:As-xu/teahome-service.git
git -C /opt/teahome/app pull --ff-only
/opt/teahome/app/deploy.sh         # 真实 GitHub 拉取 + 构建 + migrate + 起服 + health
```

## 未验证项

- 坏版本自动回滚（deploy.sh 的 rollback 分支）未做破坏性演练。
- GitHub 真实 `git pull` 未通（待 key）。
- TV/手机端访问 18080 未在真机验证。
- 非白名单来源的 5432 DROP 未从外部主机实测（规则顺序已确认）。
