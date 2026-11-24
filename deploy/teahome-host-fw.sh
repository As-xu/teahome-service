#!/usr/bin/env bash
# TeaHomeService host firewall: SSH 只允许内网 IPv4，禁止 IPv6 SSH。
# 幂等：重复执行不会叠加规则；由 netfilter-persistent 持久化。
set -euo pipefail
IPT=/usr/sbin/iptables
IP6=/usr/sbin/ip6tables
LAN=192.168.5.0/24
SSH_PORT=30022

$IPT -C INPUT -p tcp --dport "$SSH_PORT" -s "$LAN" \
  -m comment --comment teahome-ssh-allow-lan -j ACCEPT 2>/dev/null || \
  $IPT -I INPUT 1 -p tcp --dport "$SSH_PORT" -s "$LAN" \
    -m comment --comment teahome-ssh-allow-lan -j ACCEPT

$IPT -C INPUT -p tcp --dport "$SSH_PORT" \
  -m comment --comment teahome-ssh-drop-other -j DROP 2>/dev/null || \
  $IPT -I INPUT 2 -p tcp --dport "$SSH_PORT" \
    -m comment --comment teahome-ssh-drop-other -j DROP

$IP6 -C INPUT -p tcp --dport "$SSH_PORT" \
  -m comment --comment teahome-ssh-drop-v6 -j DROP 2>/dev/null || \
  $IP6 -I INPUT 1 -p tcp --dport "$SSH_PORT" \
    -m comment --comment teahome-ssh-drop-v6 -j DROP
