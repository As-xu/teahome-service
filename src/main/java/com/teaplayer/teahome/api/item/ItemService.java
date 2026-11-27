package com.teaplayer.teahome.api.item;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.teaplayer.teahome.api.ApiException;
import com.teaplayer.teahome.api.TtlCache;
import com.teaplayer.teahome.crawl.CategoryClassifier;
import com.teaplayer.teahome.crawl.MacCmsClient;
import com.teaplayer.teahome.crawl.PlayUrlParser;
import com.teaplayer.teahome.crawl.RawItem;
import com.teaplayer.teahome.crawl.SourceRepository;
import com.teaplayer.teahome.crawl.SourceRow;
import com.teaplayer.teahome.crawl.SourceSpec;
import com.teaplayer.teahome.crawl.TitleNormalizer;

@Service
public class ItemService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 与客户端 `SafeHttpClient` 的默认 UA 保持一致：源站只认「像浏览器」的 UA。 */
    private static final String PLAYBACK_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private final JdbcClient jdbc;
    private final SourceRepository sources;
    private final MacCmsClient client;
    private final CategoryClassifier classifier;
    private final TtlCache<String, String> misses = new TtlCache<>(60_000, 2000);
    private final java.util.concurrent.Semaphore livePermits = new java.util.concurrent.Semaphore(4);

    public ItemService(JdbcClient jdbc, SourceRepository sources, MacCmsClient client,
                       CategoryClassifier classifier) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.client = client;
        this.classifier = classifier;
    }

    public ItemDtos.ItemDetail detail(String sourceId, String itemId) {
        if (sourceId == null || !sourceId.matches("[a-z0-9_-]{1,32}")
                || itemId == null || !itemId.matches("\\d{1,20}")) {
            throw ApiException.notFound("资源不存在");
        }
        ItemRow item = loadItem(sourceId, itemId);
        if (item != null) {
            if (!"active".equals(item.status())) {
                throw ApiException.gone("该资源已下线");
            }
            if (item.expiresAt() != null && item.expiresAt().isBefore(OffsetDateTime.now())) {
                throw ApiException.gone("该资源已过期");
            }
            return fromDb(item);
        }
        String cacheKey = sourceId + ":" + itemId;
        String miss = misses.get(cacheKey);
        if ("not_found".equals(miss)) {
            throw ApiException.notFound("未找到资源: " + itemId);
        }
        if ("busy".equals(miss)) {
            throw ApiException.upstream("详情服务繁忙");
        }
        boolean acquired;
        try {
            acquired = livePermits.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.upstream("详情服务中断");
        }
        if (!acquired) {
            misses.put(cacheKey, "busy");
            throw ApiException.upstream("详情服务繁忙");
        }
        try {
            return fromLive(sourceId, itemId);
        } catch (ApiException e) {
            if (e.status() == 404) {
                misses.put(cacheKey, "not_found");
            }
            throw e;
        } finally {
            livePermits.release();
        }
    }

    private ItemDtos.ItemDetail fromDb(ItemRow item) {
        List<LineRow> lineRows = jdbc.sql("""
                        SELECT id, play_from, is_primary, protocol
                          FROM teaplayer_play_line
                         WHERE item_id = :item
                         ORDER BY line_index
                        """)
                .param("item", item.id())
                .query((rs, n) -> new LineRow(rs.getLong("id"), rs.getString("play_from"),
                        rs.getBoolean("is_primary"), rs.getString("protocol")))
                .list();

        Map<Long, List<ItemDtos.Episode>> episodes = episodes(lineRows);
        List<ItemDtos.Line> lines = new ArrayList<>(lineRows.size());
        for (LineRow line : lineRows) {
            List<ItemDtos.Episode> eps = episodes.getOrDefault(line.id(), List.of());
            lines.add(new ItemDtos.Line(line.from(), line.protocol(), line.primary(), eps,
                    playbackHeaders(eps), playbackHosts(eps), null));
        }

        return new ItemDtos.ItemDetail(item.mergeKey(), item.title(), item.year(), item.categoryCodes(),
                item.primaryCategory(), coverProxy(item.coverKey()), item.coverKey(), item.remark(),
                "db", iso(item.updatedAt()), iso(item.expiresAt()), lines, siblings(item.groupId()));
    }

    private ItemDtos.ItemDetail fromLive(String sourceId, String itemId) {
        SourceRow source = sources.findById(sourceId)
                .orElseThrow(() -> ApiException.notFound("未找到源: " + sourceId));
        if (!source.isMacCms()) {
            throw ApiException.notFound("该源暂不支持实时详情");
        }
        SourceSpec spec = source.parsedSpec();
        RawItem raw = client.details(source, spec, List.of(itemId), 1, java.time.Duration.ofSeconds(8)).get(itemId);
        if (raw == null) {
            throw ApiException.notFound("未找到资源: " + itemId);
        }
        CategoryClassifier.Result category = classifier.classify(raw.category());
        List<ItemDtos.Line> lines = new ArrayList<>();
        List<PlayUrlParser.ParsedLine> parsed = PlayUrlParser.parse(raw.playFrom(), raw.playUrl(),
                spec.groupSep(), spec.episodeSep(), spec.nameSep());
        int primaryIndex = 0;
        for (int i = 1; i < parsed.size(); i++) {
            if (parsed.get(i).episodes().size() > parsed.get(primaryIndex).episodes().size()) {
                primaryIndex = i;
            }
        }
        for (int i = 0; i < parsed.size(); i++) {
            PlayUrlParser.ParsedLine line = parsed.get(i);
            List<ItemDtos.Episode> episodes = new ArrayList<>(line.episodes().size());
            for (int j = 0; j < line.episodes().size(); j++) {
                PlayUrlParser.ParsedEpisode episode = line.episodes().get(j);
                episodes.add(new ItemDtos.Episode(j + 1, episode.name(), episode.url()));
            }
            String protocol = line.episodes().isEmpty() ? "unknown"
                    : PlayUrlParser.protocol(line.episodes().get(0).url());
            lines.add(new ItemDtos.Line(line.from(), protocol, i == primaryIndex, episodes,
                    playbackHeaders(episodes), playbackHosts(episodes), null));
        }
        String key = TitleNormalizer.mergeKey(raw.title(), raw.year());
        return new ItemDtos.ItemDetail(key, raw.title(), parseYear(raw.year()), category.codes(),
                category.primary(), null, null, raw.remark(), "live",
                null, null, lines, List.of());
    }

    /**
     * 播放请求头：按分集地址的 origin 推一个 Referer（分享页型源站最常见的门槛），
     * 并盖上一个桌面 UA。**只做兜底**——客户端有源站规格时以规格为准，
     * 这里给的是「客户端没有本地规则也能起播」的最小集合。
     */
    static Map<String, String> playbackHeaders(List<ItemDtos.Episode> episodes) {
        if (episodes.isEmpty()) {
            return Map.of();
        }
        // 一条线路只能写一份 Referer。分集跨 origin 时（不同 CDN / 一部分是另一个
        // 站点的直链）没有「正确的那一个」，宁可不下发，让客户端沿用源站规则或
        // 自己从分享页学——错配的 Referer 只会制造 403。
        String referer = null;
        for (ItemDtos.Episode e : episodes) {
            String origin = originOf(e.url());
            if (origin == null) {
                return Map.of();
            }
            if (referer == null) {
                referer = origin;
            } else if (!referer.equals(origin)) {
                return Map.of();
            }
        }
        if (referer == null) {
            return Map.of();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("referer", referer + "/");
        headers.put("user_agent", PLAYBACK_UA);
        return Map.copyOf(headers);
    }

    /** 这条线路会碰到的全部主机（去重保序），客户端据此在会话内放行。 */
    static List<String> playbackHosts(List<ItemDtos.Episode> episodes) {
        Set<String> hosts = new LinkedHashSet<>();
        for (ItemDtos.Episode e : episodes) {
            String host = hostOf(e.url());
            if (host != null) {
                hosts.add(host);
            }
        }
        return List.copyOf(hosts);
    }

    private static String originOf(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return null;
            }
            return uri.getScheme() + "://" + uri.getHost()
                    + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null || host.isBlank() ? null : host.toLowerCase(java.util.Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ItemRow loadItem(String sourceId, String itemId) {
        return jdbc.sql("""
                        SELECT id, group_id, merge_key, title, year, category_codes, primary_category,
                               cover_key, remark, updated_at, expires_at, status
                          FROM teaplayer_source_item
                         WHERE source_id = :source AND item_id = :item
                        """)
                .param("source", sourceId)
                .param("item", itemId)
                .query((rs, n) -> new ItemRow(
                        rs.getLong("id"),
                        rs.getObject("group_id", Long.class),
                        rs.getString("merge_key"),
                        rs.getString("title"),
                        (Integer) rs.getObject("year"),
                        strings(rs.getArray("category_codes")),
                        rs.getString("primary_category"),
                        rs.getString("cover_key"),
                        rs.getString("remark"),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getString("status")))
                .optional()
                .orElse(null);
    }

    private Map<Long, List<ItemDtos.Episode>> episodes(List<LineRow> lines) {
        Map<Long, List<ItemDtos.Episode>> result = new LinkedHashMap<>();
        if (lines.isEmpty()) {
            return result;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            names.add(":l" + i);
            params.put("l" + i, lines.get(i).id());
        }
        jdbc.sql("""
                        SELECT line_id, episode_index, name, url
                          FROM teaplayer_episode
                         WHERE line_id IN (%s)
                         ORDER BY line_id, episode_index
                        """.formatted(String.join(",", names)))
                .params(params)
                .query((rs, n) -> {
                    result.computeIfAbsent(rs.getLong("line_id"), k -> new ArrayList<>())
                            .add(new ItemDtos.Episode(rs.getInt("episode_index"), rs.getString("name"),
                                    rs.getString("url")));
                    return null;
                }).list();
        return result;
    }

    private List<ItemDtos.Sibling> siblings(Long groupId) {
        if (groupId == null) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT i.source_id, i.item_id, (i.id = g.primary_item_id) AS is_primary
                          FROM teaplayer_source_item i
                          JOIN teaplayer_title_group g ON g.id = i.group_id
                         WHERE i.group_id = :group AND i.status = 'active' AND i.expires_at > now()
                         ORDER BY is_primary DESC, i.source_id
                        """)
                .param("group", groupId)
                .query((rs, n) -> new ItemDtos.Sibling(rs.getString("source_id"),
                        rs.getString("item_id"), rs.getBoolean("is_primary")))
                .list();
    }

    private static String coverProxy(String coverKey) {
        if (coverKey == null || coverKey.length() < 8) {
            return null;
        }
        return "/v1/img/" + coverKey + "?v=" + coverKey.substring(0, 8);
    }

    private static Integer parseYear(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            int year = Integer.parseInt(value.trim());
            return year >= 1900 && year <= 2100 ? year : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String iso(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static List<String> strings(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            String[] values = (String[]) array.getArray();
            return values == null ? List.of() : List.of(values);
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }

    private record ItemRow(long id, Long groupId, String mergeKey, String title, Integer year,
                           List<String> categoryCodes, String primaryCategory, String coverKey,
                           String remark, OffsetDateTime updatedAt, OffsetDateTime expiresAt, String status) {
    }

    private record LineRow(long id, String from, boolean primary, String protocol) {
    }
}
