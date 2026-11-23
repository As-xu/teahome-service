#!/usr/bin/env bash
set -euo pipefail
IPT=/usr/sbin/iptables
$IPT -N DOCKER-USER 2>/dev/null || true
# 本脚本独占 DOCKER-USER：先清空再按固定顺序重建，保证重启/重跑不叠加
$IPT -F DOCKER-USER
$IPT -A DOCKER-USER -p tcp --dport 5432 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
$IPT -A DOCKER-USER -p tcp --dport 5432 -s 172.16.0.0/12 -m comment --comment teahome-5432-allow-c1 -j ACCEPT
$IPT -A DOCKER-USER -p tcp --dport 5432 -s 192.168.5.0/24 -m comment --comment teahome-5432-allow-lan -j ACCEPT
$IPT -A DOCKER-USER -p tcp --dport 5432 -m comment --comment teahome-5432-drop -j DROP

# 18080 只对局域网开放（手机/电视在 192.168.5.0/24）
$IPT -A DOCKER-USER -p tcp --dport 18080 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
$IPT -A DOCKER-USER -p tcp --dport 18080 -s 192.168.5.0/24 -m comment --comment teahome-18080-allow-lan -j ACCEPT
$IPT -A DOCKER-USER -p tcp --dport 18080 -m comment --comment teahome-18080-drop -j DROP
