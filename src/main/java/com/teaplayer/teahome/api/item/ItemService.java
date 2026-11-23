package com.teaplayer.teahome.api.item;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.teaplayer.teahome.api.ApiException;
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

    private final JdbcClient jdbc;
    private final SourceRepository sources;
    private final MacCmsClient client;
    private final CategoryClassifier classifier;

    public ItemService(JdbcClient jdbc, SourceRepository sources, MacCmsClient client,
                       CategoryClassifier classifier) {
        this.jdbc = jdbc;
        this.sources = sources;
        this.client = client;
        this.classifier = classifier;
    }

    public ItemDtos.ItemDetail detail(String sourceId, String itemId) {
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
        return fromLive(sourceId, itemId);
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
            lines.add(new ItemDtos.Line(line.from(), line.protocol(), line.primary(),
                    episodes.getOrDefault(line.id(), List.of())));
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
        RawItem raw = client.details(source, spec, List.of(itemId)).get(itemId);
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
            lines.add(new ItemDtos.Line(line.from(), protocol, i == primaryIndex, episodes));
        }
        String key = TitleNormalizer.mergeKey(raw.title(), raw.year());
        return new ItemDtos.ItemDetail(key, raw.title(), parseYear(raw.year()), category.codes(),
                category.primary(), null, null, raw.remark(), "live",
                null, null, lines, List.of());
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
                         WHERE i.group_id = :group AND i.status = 'active'
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
