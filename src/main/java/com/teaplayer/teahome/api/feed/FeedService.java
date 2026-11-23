package com.teaplayer.teahome.api.feed;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.teaplayer.teahome.api.ApiException;
import com.teaplayer.teahome.api.Cursor;
import com.teaplayer.teahome.crawl.CategoryClassifier;
import com.teaplayer.teahome.web.SchemaVersionProvider;

@Service
public class FeedService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> CATEGORY_KEYS =
            List.of("movie", "tv", "anime", "variety", "documentary", "short", "other");
    private static final Map<String, String> CATEGORY_NAMES = Map.of(
            "movie", "电影", "tv", "电视剧", "anime", "动漫", "variety", "综艺",
            "documentary", "纪录片", "short", "短剧", "other", "其他");
    private static final long CATEGORY_CACHE_MS = 60_000L;

    private final JdbcClient jdbc;
    private final SchemaVersionProvider schemaVersion;
    private final CategoryClassifier classifier;

    private volatile CategoriesCache categoriesCache;

    public FeedService(JdbcClient jdbc, SchemaVersionProvider schemaVersion, CategoryClassifier classifier) {
        this.jdbc = jdbc;
        this.schemaVersion = schemaVersion;
        this.classifier = classifier;
    }

    public FeedDtos.FeedResponse feed(String category, String cursorValue, int size) {
        if (size < 1 || size > 60) {
            throw ApiException.badRequest("bad_size", "size 必须在 1 到 60 之间");
        }
        String cat = normalizeCategory(category);
        String cursorCategory = cat == null ? "" : cat;
        Cursor cursor = Cursor.decode(cursorValue);
        if (cursor != null && !cursorCategory.equals(cursor.category())) {
            throw ApiException.badRequest("bad_cursor", "cursor 与筛选条件不匹配");
        }

        StringBuilder sql = new StringBuilder("""
                SELECT g.id, g.merge_key, g.title, g.year, g.category_codes, g.primary_category,
                       g.cover_key, g.remark, g.updated_at, g.expires_at, g.feed_seq
                  FROM teaplayer_title_group g
                 WHERE g.item_count > 0
                   AND g.expires_at > now()
                """);
        Map<String, Object> params = new LinkedHashMap<>();
        if (cat != null) {
            sql.append(" AND CAST(:category AS text) = ANY (g.category_codes)\n");
            params.put("category", cat);
        }
        if (cursor != null) {
            sql.append(" AND (g.feed_seq, g.id) < (:cursorSeq, :cursorId)\n");
            params.put("cursorSeq", cursor.seq());
            params.put("cursorId", cursor.id());
        }
        sql.append(" ORDER BY g.feed_seq DESC, g.id DESC LIMIT :limit");
        params.put("limit", size + 1);

        List<Row> rows = jdbc.sql(sql.toString()).params(params)
                .query((rs, n) -> new Row(
                        rs.getLong("id"),
                        rs.getString("merge_key"),
                        rs.getString("title"),
                        (Integer) rs.getObject("year"),
                        strings(rs.getArray("category_codes")),
                        rs.getString("primary_category"),
                        rs.getString("cover_key"),
                        rs.getString("remark"),
                        rs.getObject("updated_at", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getLong("feed_seq")))
                .list();

        boolean hasMore = rows.size() > size;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, size));
        }

        Map<Long, List<FeedDtos.SourceRef>> sources = sources(rows);
        Map<Long, Integer> lineCounts = lineCounts(rows);

        List<FeedDtos.Item> items = new ArrayList<>(rows.size());
        for (Row row : rows) {
            items.add(new FeedDtos.Item(
                    row.mergeKey(), row.title(), row.year(), row.categoryCodes(), row.primaryCategory(),
                    coverProxy(row.coverKey()), row.coverKey(), row.remark(),
                    iso(row.updatedAt()), iso(row.expiresAt()),
                    lineCounts.getOrDefault(row.id(), 0),
                    sources.getOrDefault(row.id(), List.of())));
        }

        String next = null;
        if (hasMore && !rows.isEmpty()) {
            Row last = rows.get(rows.size() - 1);
            next = new Cursor(cursorCategory, last.feedSeq(), last.id()).encode();
        }

        return new FeedDtos.FeedResponse(items, next, hasMore, now(),
                classifier.version(), schemaVersion.schemaVersion());
    }

    public FeedDtos.CategoriesResponse categories() {
        CategoriesCache cached = categoriesCache;
        long nowMillis = System.currentTimeMillis();
        if (cached != null && nowMillis - cached.at() < CATEGORY_CACHE_MS) {
            return new FeedDtos.CategoriesResponse(cached.counts(), now());
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : CATEGORY_KEYS) {
            counts.put(key, 0);
        }
        jdbc.sql("""
                SELECT primary_category, count(*) AS n
                  FROM teaplayer_title_group
                 WHERE item_count > 0 AND expires_at > now()
                 GROUP BY primary_category
                """).query((rs, n) -> {
            counts.put(rs.getString("primary_category"), rs.getInt("n"));
            return null;
        }).list();

        List<FeedDtos.CategoryCount> result = new ArrayList<>(CATEGORY_KEYS.size());
        for (String key : CATEGORY_KEYS) {
            result.add(new FeedDtos.CategoryCount(key, CATEGORY_NAMES.get(key), counts.getOrDefault(key, 0)));
        }
        categoriesCache = new CategoriesCache(nowMillis, result);
        return new FeedDtos.CategoriesResponse(result, now());
    }

    private Map<Long, List<FeedDtos.SourceRef>> sources(List<Row> rows) {
        Map<Long, List<FeedDtos.SourceRef>> result = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return result;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String in = placeholders("g", rows.size(), params, i -> rows.get(i).id());
        String sql = """
                SELECT i.group_id, i.source_id, i.item_id,
                       (i.id = g.primary_item_id) AS is_primary
                  FROM teaplayer_source_item i
                  JOIN teaplayer_title_group g ON g.id = i.group_id
                 WHERE i.group_id IN (%s)
                   AND i.status = 'active'
                   AND i.expires_at > now()
                 ORDER BY i.group_id, is_primary DESC, i.source_id
                """.formatted(in);
        jdbc.sql(sql).params(params).query((rs, n) -> {
            result.computeIfAbsent(rs.getLong("group_id"), k -> new ArrayList<>())
                    .add(new FeedDtos.SourceRef(rs.getString("source_id"), rs.getString("item_id"),
                            rs.getBoolean("is_primary")));
            return null;
        }).list();
        return result;
    }

    private Map<Long, Integer> lineCounts(List<Row> rows) {
        Map<Long, Integer> result = new LinkedHashMap<>();
        if (rows.isEmpty()) {
            return result;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String in = placeholders("l", rows.size(), params, i -> rows.get(i).id());
        jdbc.sql("""
                SELECT g.id, count(pl.id) AS n
                  FROM teaplayer_title_group g
                  LEFT JOIN teaplayer_source_item ps
                         ON ps.id = g.primary_item_id
                        AND ps.status = 'active'
                        AND ps.expires_at > now()
                  LEFT JOIN teaplayer_play_line pl ON pl.item_id = ps.id
                 WHERE g.id IN (%s)
                 GROUP BY g.id
                """.formatted(in)).params(params).query((rs, n) -> {
            result.put(rs.getLong("id"), rs.getInt("n"));
            return null;
        }).list();
        return result;
    }

    private static String placeholders(String prefix, int count, Map<String, Object> params,
                                       java.util.function.IntFunction<Long> value) {
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = prefix + i;
            names.add(":" + name);
            params.put(name, value.apply(i));
        }
        return String.join(",", names);
    }

    private static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String value = category.trim().toLowerCase();
        if ("all".equals(value)) {
            return null;
        }
        if (!CATEGORY_KEYS.contains(value)) {
            throw ApiException.badRequest("bad_category", "未知分类: " + category);
        }
        return value;
    }

    private static String coverProxy(String coverKey) {
        if (coverKey == null || coverKey.length() < 8) {
            return null;
        }
        return "/v1/img/" + coverKey + "?v=" + coverKey.substring(0, 8);
    }

    private static String iso(OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static String now() {
        return OffsetDateTime.now(ZONE).toString();
    }

    private static List<String> strings(Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            String[] values = (String[]) array.getArray();
            return values == null ? List.of() : List.of(values);
        } catch (SQLException e) {
            return List.of();
        }
    }

    private record Row(long id, String mergeKey, String title, Integer year, List<String> categoryCodes,
                       String primaryCategory, String coverKey, String remark,
                       OffsetDateTime updatedAt, OffsetDateTime expiresAt, long feedSeq) {
    }

    private record CategoriesCache(long at, List<FeedDtos.CategoryCount> counts) {
    }
}
