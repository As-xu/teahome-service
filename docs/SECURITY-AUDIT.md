# 安全审计与加固记录（2026-11-25）

范围：`192.168.5.156` 上的 TeaHomeService（api/crawler/db）、SSH、Docker 发布端口、备份与仓库。
本次只做只读检查 + 两项可逆的防火墙加固；未改 SSH 配置、未动数据、未重写 Git 历史。

## 结论

| 级别 | 问题 | 状态 |
|---|---|---|
| 高 | sshd 监听公网 IPv6 `[::]:30022`，且 `PasswordAuthentication yes`；已实测从全局 IPv6 地址用 key 登录成功 | 已阻断 IPv6 与公网 IPv4；密码登录仍开，待你决定是否关闭 |
| 中高 | 5432 对整段 LAN `192.168.5.0/24` 与 `172.16.0.0/12`（所有 Docker 容器）开放，无 TLS | 已收窄到开发机 `192.168.5.155/32` + 本 compose 网络 `172.18.0.0/16` |
| 中 | 18080 线上没有 DOCKER-USER 规则 | 已只允许 `192.168.5.0/24`，其余 DROP |
| 中 | age 备份私钥曾与备份在同一台机器 | 已迁移到本机 `D:\Documents\TeaHome-backup`，服务器私钥已删除；建议再加一份离线副本 |
| 中 | `xs` 可读 `/opt/teahome/secrets` 与 `.env`，且有免密 sudo；SSH 账号被盗等于 root | 已知设计取舍；关闭密码登录可降低 |
| 低 | V3 中含 Supabase publishable key（非自家项目，非 service_role） | DB 中已由 V10 清空；Git 历史保留；无需轮换第三方项目 |
| 待确认 | GitHub 仓库可见性（HTTPS 访问超时，无法确认 public/private） | 待你确认；若 public 建议改 private |

未被入侵的旁证：`journalctl -u ssh` 近 7 天 `Failed password` 计数 0；`lastb` 只有 2 条 11 月 21 日的本地记录；DB 日志 `password authentication failed` 计数 0；`pg_stat_activity` 只有本机 app 连接；`teaplayer_api_token` 只有 bootstrap(read) 一条。

## 已执行的加固

1. `deploy/teahome-fw.sh`（装到 `/usr/local/sbin/teahome-fw.sh`，由 docker `ExecStartPost` 重放）：
   - 5432：仅 `172.18.0.0/16` 与 `192.168.5.155/32`，其余 DROP；
   - 18080：仅 `192.168.5.0/24`，其余 DROP。注意 DNAT 会把目标端口改成容器端口 8080，因此规则用 `conntrack --ctorigdstport 18080` 匹配原始端口；用 `--dport 18080` 会永远不命中（已实测纠正）。
2. `deploy/teahome-host-fw.sh`（装到 `/usr/local/sbin/teahome-host-fw.sh`）：
   - IPv4 INPUT：30022 只接受 `192.168.5.0/24`，其余 DROP；
   - IPv6 INPUT：30022 全部 DROP（当前没有 IPv6 SSH 需求）；
   - 幂等（`iptables -C` 检查），已 `netfilter-persistent save`。
3. 验证：
   - 本机（`192.168.5.155`）IPv4 SSH 正常；公网 IPv6 SSH 连接超时；
   - Windows 访问 `http://192.168.5.156:18080/actuator/health` 200；
   - Windows 连 `192.168.5.156:5432` 成功（开发机放行）；
   - `systemctl restart docker` 后 DOCKER-USER 仍为 4 条 5432 + 3 条 18080，INPUT 仍 2 条，health 200。

## 回滚

```bash
# 删除 SSH 主机规则
sudo iptables -D INPUT -s 192.168.5.0/24 -p tcp --dport 30022 -m comment --comment teahome-ssh-allow-lan -j ACCEPT
sudo iptables -D INPUT -p tcp --dport 30022 -m comment --comment teahome-ssh-drop-other -j DROP
sudo ip6tables -D INPUT -p tcp --dport 30022 -m comment --comment teahome-ssh-drop-v6 -j DROP
sudo netfilter-persistent save

# 恢复旧的 DOCKER-USER（宽口径）
sudo /usr/local/sbin/teahome-fw.sh   # 仅当脚本已回退到旧版本
```

## 你的决定（2026-11-25）

1. **SSH 密码登录：保持开启**。30022 已只允许 `192.168.5.0/24` IPv4，IPv6 已封；风险从公网降到局域网。仍建议使用强密码，后续可再关闭。
2. **age 私钥：已迁移到本机**。服务器私钥已 `shred -u` 删除，只保留公钥；见下一节。
3. **GitHub 仓库：保持现状**。HTTPS 检测超时，无法确认 public/private；若为 public，代码可见，但仓库中没有 service_role/私钥。
4. **开发机 IP：已固定**。Windows 以太网 `Dhcp=Disabled`，地址 `192.168.5.155`；5432 规则与之匹配。

## age 备份私钥说明与迁移记录（2026-11-25）

- 服务器用 age **公钥**加密备份：`age -r "$(cat secrets/backup.pub)" -o ...`；公钥 `age1vft89f2kptf0hdm742dgskln90e58plzpc4832q0zyk2hdjpwp6qucngu2` 只能加密，不能解密；公钥仍在服务器 `/opt/teahome/secrets/backup.pub`。
- 私钥现只在本机：`D:\Documents\TeaHome-backup\teahome-backup.age.key`（189 字节，SHA-256 `C6C04F4FD41886D007DFE2B028949B1D9EDCCD0372B038C7B44751493D04038C`）；同目录保留 `age.exe`（v1.3.2，用于以后恢复）。
- 服务器 `/root/teahome-backup.age.key` 已 `shred -u` 删除并确认 `test -e` 为 false；服务器上不再有可解密备份的私钥。
- 迁移验证（全部通过）：
  1. 删除前，服务器用私钥解密 `teahome-2026-11-24_0330.dump.age`，`pg_restore --list` 正常，明文 dump SHA-256 `78C4B5FE4C60C83C433B7B16A24A1256E3A8B1569442A9C18EF2A1A35E8B31D1`。
  2. 私钥 `scp` 到本机，哈希与服务端一致；Windows `age.exe` 解密同一备份，得到相同 SHA-256。
  3. 删除服务器私钥后跑新备份 `teahome-2026-11-25_0034.dump.age`（13,522,916 字节）；本机私钥解密成功，明文头为 `PGDMP`，SHA-256 `869BCE7F93F272A5B3681E5BFA032FF86347F343D127E8F7A1139BE4F67837A6`。
- ACL：`D:\Documents\TeaHome-backup` 与其文件只保留 `NT AUTHORITY\SYSTEM`、`BUILTIN\Administrators`、`XSPC\xusong` 三个 ACE，已移除继承。
- 恢复命令（本机先解密，再上传到服务器）：

```powershell
cd D:\Documents\TeaHome-backup
.\age.exe -d -i .\teahome-backup.age.key -o .\restore.dump .\teahome-YYYY-MM-DD_HHMM.dump.age
scp -P 30022 .\restore.dump xs@192.168.5.156:/tmp/
```

```bash
# 在服务器上恢复到独立库，确认后再决定是否切换
sudo docker exec -i teahome-db psql -U postgres -c 'DROP DATABASE IF EXISTS teahome_restore;'
sudo docker exec -i teahome-db createdb -U postgres -O teahome_owner teahome_restore
sudo docker exec -i teahome-db pg_restore -U postgres -d teahome_restore --no-owner < /tmp/restore.dump
```

- 风险：目前私钥只有本机一份；建议再复制到 U 盘/密码管理器等离线介质并演练一次恢复。私钥丢失 = 所有 `.dump.age` 永久不可恢复。明文 dump 含业务与凭据哈希，恢复后立即删除，勿留在共享目录。
