#!/usr/bin/env bash
set -euo pipefail
IPT=/usr/sbin/iptables
$IPT -N DOCKER-USER 2>/dev/null || true
# 本脚本独占 DOCKER-USER：先清空再按固定顺序重建，保证重启/重跑不叠加
$IPT -F DOCKER-USER
$IPT -A DOCKER-USER -p tcp --dport 5432 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
$IPT -A DOCKER-USER -p tcp --dport 5432 -s 172.18.0.0/16 -d 192.168.5.156/32 -m comment --comment teahome-5432-allow-compose -j ACCEPT
$IPT -A DOCKER-USER -p tcp --dport 5432 -s 192.168.5.155/32 -m comment --comment teahome-5432-allow-dev -j ACCEPT
$IPT -A DOCKER-USER -p tcp --dport 5432 -m comment --comment teahome-5432-drop -j DROP

# 18080 只对局域网开放（手机/电视在 192.168.5.0/24）
$IPT -A DOCKER-USER -p tcp -m conntrack --ctorigdstport 18080 --ctstate ESTABLISHED,RELATED -j ACCEPT
$IPT -A DOCKER-USER -p tcp -m conntrack --ctorigdstport 18080 -s 192.168.5.0/24 -m comment --comment teahome-18080-allow-lan -j ACCEPT
$IPT -A DOCKER-USER -p tcp -m conntrack --ctorigdstport 18080 -m comment --comment teahome-18080-drop -j DROP

# 容器出站：只放行公网、本 compose 网段与宿主数据库（上面已 ACCEPT），
# 其余私网/链路本地/元数据地址一律丢弃，避免被入库的恶意 URL 把容器当内网探测跳板。
$IPT -A DOCKER-USER -s 172.18.0.0/16 -m conntrack --ctstate ESTABLISHED,RELATED -m comment --comment teahome-egress-reply -j RETURN
$IPT -A DOCKER-USER -s 172.18.0.0/16 -d 172.18.0.0/16 -m comment --comment teahome-egress-compose -j RETURN
for net in 10.0.0.0/8 100.64.0.0/10 127.0.0.0/8 169.254.0.0/16 172.16.0.0/12 192.0.0.0/24 192.168.0.0/16; do
  $IPT -A DOCKER-USER -s 172.18.0.0/16 -d "$net" -m comment --comment teahome-egress-block-private -j DROP
done
