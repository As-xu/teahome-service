package com.teaplayer.teahome.api.item;

import java.util.List;
import java.util.Map;

public final class ItemDtos {

    private ItemDtos() {
    }

    public record ItemDetail(
            String key,
            String title,
            Integer year,
            List<String> categoryCodes,
            String primaryCategory,
            String coverProxy,
            String coverKey,
            String remark,
            String origin,
            String updatedAt,
            String expiresAt,
            List<Line> lines,
            List<Sibling> siblings) {
    }

    /**
     * 一条线路。除分集外还带两类「客户端没有本地规格时也要能播」的元数据：
     *
     * <ul>
     *   <li>{@code headers}：播放该线路时要带的请求头（当前按分集 URL 的
     *       origin 推 Referer + 一个桌面 UA；**分集跨 origin 时为空**，因为一条
     *       线路只能写一份 Referer，错配只会制造 403）；</li>
     *   <li>{@code hosts}：这条线路会碰到的全部主机（含 CDN）。它只用于按主机
     *       匹配播放头规则，**不是**客户端的出网白名单——客户端不得据此放行
     *       任意地址，否则上游内容就成了放行依据；</li>
     *   <li>{@code ttlSeconds}：签名地址的剩余有效期。当前源站下发的是长期有效
     *       的直链，故为 null；将来接入签名 CDN 时由这里给出。</li>
     * </ul>
     */
    public record Line(String from, String protocol, boolean primary, List<Episode> episodes,
                       Map<String, String> headers, List<String> hosts, Integer ttlSeconds) {
    }

    public record Episode(int index, String name, String url) {
    }

    public record Sibling(String sourceId, String itemId, boolean primary) {
    }
}
