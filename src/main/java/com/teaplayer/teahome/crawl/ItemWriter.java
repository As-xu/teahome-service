package com.teaplayer.teahome.crawl;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.teaplayer.teahome.auth.TokenHasher;

import tools.jackson.databind.ObjectMapper;

/**
 * 条目写入、线路/剧集替换、group 物化与过期清理。
 *
 * <p>幂等约定：只有内容 hash 或播放串 hash 变化时才重写行与线路；详情缺失时只推进
 * TTL，绝不用列表页的空字段覆盖已有元数据。group 永不物理删除，id 与 feed_seq 稳定。
 */
@Component
public class ItemWriter {

    private static final Logger log = LoggerFactory.getLogger(ItemWriter.class);
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final int PURGE_BATCH = 500;

    public record WriteResult(long itemId, Long groupId, boolean created, boolean renewed,
                              boolean changed, int lines) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;
    private final CategoryClassifier classifier;
    private final CrawlProperties props;

    public ItemWriter(JdbcClient jdbc, ObjectMapper mapper, CategoryClassifier classifier, CrawlProperties props) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.classifier = classifier;
        this.props = props;
    }

    /**
     * @param detailed true 表示这一条来自 ac=detail，携带封面/年份/播放串；false 表示只有列表字段。
     */
    @Transactional
    public WriteResult write(SourceRow source, RawItem raw, boolean detailed) {
        Instant now = Instant.now();
        int ttlDays = Math.min(14, Math.max(1, props.getTtlDays()));
        Optional<Existing> found = loadExisting(source.id(), raw.id());
        if (!detailed && found.isEmpty()) {
            return new WriteResult(0, null, false, false, false, 0);
        }

        if (!detailed) {
            Existing old = found.get();
            boolean vodForward = raw.vodTime().isAfter(old.vodTime());
            Instant expires = vodForward
                    ? TtlPolicy.renewedExpiry(old.expiresAt(), now, ttlDays)
                    : old.expiresAt();
            Instant vod = vodForward ? raw.vodTime() : old.vodTime();
            jdbc.sql("""
                            UPDATE teaplayer_source_item
                               SET vod_time = :vodTime, expires_at = :expiresAt, ttl_days_applied = :ttlDays
                             WHERE id = :id
                            """)
                    .param("vodTime", ts(vod))
                    .param("expiresAt", ts(expires))
                    .param("ttlDays", ttlDays)
                    .param("id", old.id())
                    .update();
            if (vodForward && old.groupId() != null) {
                rebuildGroup(old.groupId(), false);
            }
            return new WriteResult(old.id(), old.groupId(), false, vodForward, false, 0);
        }

        String titleNorm = TitleNormalizer.normalize(raw.title());
        String mergeKey = TitleNormalizer.mergeKey(raw.title(), raw.year());
        Short year = parseYear(raw.year());
        CategoryClassifier.Result category = classifier.classify(raw.category());
        String contentHash = contentHash(raw);
        String playHash = playHash(raw);

        Long groupId = ensureGroup(raw, category, titleNorm, mergeKey, year, now);
        Long oldGroupId = found.map(Existing::groupId).orElse(null);
        boolean created = found.isEmpty();
        boolean changed;
        boolean renewed;
        long itemId;
        int lines = 0;

        if (found.isEmpty()) {
            itemId = insert(source, raw, titleNorm, mergeKey, year, category, contentHash, playHash,
                    TtlPolicy.expiry(raw.vodTime(), now, ttlDays), ttlDays, groupId, now);
            changed = true;
            renewed = true;
            lines = replaceLines(itemId, source, raw);
        } else {
            Existing old = found.get();
            changed = !contentHash.equals(old.contentHash()) || !nullSafe(playHash).equals(nullSafe(old.playHash()));
            renewed = TtlPolicy.shouldRenew(raw.vodTime(), old.vodTime(), contentHash, old.contentHash());
            itemId = old.id();
            if (changed || renewed) {
                Instant expires = renewed
                        ? TtlPolicy.renewedExpiry(old.expiresAt(), now, ttlDays)
                        : old.expiresAt();
                Instant vod = raw.vodTime().isAfter(old.vodTime()) ? raw.vodTime() : old.vodTime();
                update(itemId, source, raw, titleNorm, mergeKey, year, category, contentHash, playHash,
                        vod, expires, ttlDays, groupId, changed, now);
                if (changed && !nullSafe(playHash).equals(nullSafe(old.playHash()))) {
                    lines = replaceLines(itemId, source, raw);
                } else if (changed) {
                    lines = countLines(itemId);
                }
            }
        }

        Set<Long> touched = new LinkedHashSet<>();
        if (oldGroupId != null) {
            touched.add(oldGroupId);
        }
        if (groupId != null) {
            touched.add(groupId);
        }
        boolean groupChanged = !java.util.Objects.equals(oldGroupId, groupId);
        if (changed || renewed || groupChanged) {
            // 统一按 id 升序加锁，避免两个条目互换 group 时反向等待。
            touched.stream().sorted().forEach(id -> rebuildGroup(id, changed));
        }
        return new WriteResult(itemId, groupId, created, renewed, changed, lines);
    }

    private Optional<Existing> loadExisting(String sourceId, String itemId) {
        return jdbc.sql("""
                        SELECT id, group_id, vod_time, content_hash, play_hash, expires_at
                          FROM teaplayer_source_item
                         WHERE source_id = :sourceId AND item_id = :itemId
                         FOR UPDATE
                        """)
                .param("sourceId", sourceId)
                .param("itemId", itemId)
                .query((rs, rowNum) -> new Existing(
                        rs.getLong("id"),
                        rs.getObject("group_id") == null ? null : rs.getLong("group_id"),
                        rs.getObject("vod_time", OffsetDateTime.class).toInstant(),
                        rs.getString("content_hash"),
                        rs.getString("play_hash"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    private long insert(SourceRow source, RawItem raw, String titleNorm, String mergeKey, Short year,
                        CategoryClassifier.Result category, String contentHash, String playHash,
                        Instant expires, int ttlDays, Long groupId, Instant now) {
        return jdbc.sql("""
                        INSERT INTO teaplayer_source_item
                            (source_id, item_id, title, title_norm, merge_key, year,
                             category_codes, primary_category, category_raw, category_source,
                             classifier_version, content_hash, play_hash, vod_time, expires_at,
                             ttl_days_applied, status, group_id, cover_url, remark, area, lang,
                             extra, first_seen_at, updated_at)
                        VALUES
                            (:sourceId, :itemId, :title, :titleNorm, :mergeKey, :year,
                             CAST(:codes AS text[]), :primary, :rawCategory, :categorySource,
                             :classifierVersion, :hash, :playHash, :vodTime, :expiresAt,
                             :ttlDays, 'active', :groupId, :cover, :remark, :area, :lang,
                             CAST(:extra AS jsonb), :now, :now)
                        RETURNING id
                        """)
                .param("sourceId", source.id())
                .param("itemId", raw.id())
                .param("title", raw.title())
                .param("titleNorm", titleNorm)
                .param("mergeKey", mergeKey)
                .param("year", year)
                .param("codes", arrayLiteral(category.codes()))
                .param("primary", category.primary())
                .param("rawCategory", raw.category())
                .param("categorySource", category.source())
                .param("classifierVersion", classifier.version())
                .param("hash", contentHash)
                .param("playHash", playHash)
                .param("vodTime", ts(raw.vodTime()))
                .param("expiresAt", ts(expires))
                .param("ttlDays", ttlDays)
                .param("groupId", groupId)
                .param("cover", blankToNull(raw.cover()))
                .param("remark", blankToNull(raw.remark()))
                .param("area", blankToNull(raw.area()))
                .param("lang", blankToNull(raw.lang()))
                .param("extra", extra(raw))
                .param("now", ts(now))
                .query(Long.class)
                .single();
    }

    private void update(long itemId, SourceRow source, RawItem raw, String titleNorm, String mergeKey,
                        Short year, CategoryClassifier.Result category, String contentHash, String playHash,
                        Instant vodTime, Instant expires, int ttlDays, Long groupId, boolean changed,
                        Instant now) {
        jdbc.sql("""
                        UPDATE teaplayer_source_item
                           SET title = :title, title_norm = :titleNorm, merge_key = :mergeKey,
                               year = :year, category_codes = CAST(:codes AS text[]),
                               primary_category = :primary, category_raw = :rawCategory,
                               category_source = :categorySource, classifier_version = :classifierVersion,
                               content_hash = :hash, play_hash = :playHash, vod_time = :vodTime,
                               expires_at = :expiresAt, ttl_days_applied = :ttlDays, status = 'active',
                               group_id = :groupId, cover_url = :cover, remark = :remark,
                               area = :area, lang = :lang, extra = CAST(:extra AS jsonb),
                               updated_at = CASE WHEN :changed THEN :now ELSE updated_at END
                         WHERE id = :id
                        """)
                .param("title", raw.title())
                .param("titleNorm", titleNorm)
                .param("mergeKey", mergeKey)
                .param("year", year)
                .param("codes", arrayLiteral(category.codes()))
                .param("primary", category.primary())
                .param("rawCategory", raw.category())
                .param("categorySource", category.source())
                .param("classifierVersion", classifier.version())
                .param("hash", contentHash)
                .param("playHash", playHash)
                .param("vodTime", ts(vodTime))
                .param("expiresAt", ts(expires))
                .param("ttlDays", ttlDays)
                .param("groupId", groupId)
                .param("cover", blankToNull(raw.cover()))
                .param("remark", blankToNull(raw.remark()))
                .param("area", blankToNull(raw.area()))
                .param("lang", blankToNull(raw.lang()))
                .param("extra", extra(raw))
                .param("changed", changed)
                .param("now", ts(now))
                .param("id", itemId)
                .update();
    }

    private Long ensureGroup(RawItem raw, CategoryClassifier.Result category, String titleNorm,
                             String mergeKey, Short year, Instant now) {
        if (category.primary() == null) {
            return null;
        }
        int ttlDays = Math.min(14, Math.max(1, props.getTtlDays()));
        return jdbc.sql("""
                        INSERT INTO teaplayer_title_group
                            (merge_key, merge_key_version, title, title_norm, year, category_codes,
                             primary_category, category_source, cover_url, remark,
                             vod_time_max, first_seen_at, last_seen_at, expires_at,
                             classifier_version, revision)
                        VALUES
                            (:mergeKey, 'title-year-v1', :title, :titleNorm, :year, CAST(:codes AS text[]),
                             :primary, 'primary', :cover, :remark,
                             :vodTime, :now, :now, :expiresAt,
                             :classifierVersion, 1)
                        ON CONFLICT (merge_key) DO UPDATE SET last_seen_at = now()
                        RETURNING id
                        """)
                .param("mergeKey", mergeKey)
                .param("title", raw.title())
                .param("titleNorm", titleNorm)
                .param("year", year)
                .param("codes", arrayLiteral(category.codes()))
                .param("primary", category.primary())
                .param("cover", blankToNull(raw.cover()))
                .param("remark", blankToNull(raw.remark()))
                .param("vodTime", ts(raw.vodTime()))
                .param("now", ts(now))
                .param("expiresAt", ts(TtlPolicy.expiry(raw.vodTime(), now, ttlDays)))
                .param("classifierVersion", classifier.version())
                .query(Long.class)
                .single();
    }

    private int countLines(long itemId) {
        return jdbc.sql("SELECT count(*) FROM teaplayer_play_line WHERE item_id = :itemId")
                .param("itemId", itemId)
                .query(Integer.class)
                .single();
    }

    private int replaceLines(long itemId, SourceRow source, RawItem raw) {
        List<PlayUrlParser.ParsedLine> lines = PlayUrlParser.parse(
                raw.playFrom(), raw.playUrl(), source.parsedSpec().groupSep(),
                source.parsedSpec().episodeSep(), source.parsedSpec().nameSep());
        if (lines.isEmpty()) {
            return 0;
        }
        jdbc.sql("DELETE FROM teaplayer_play_line WHERE item_id = :itemId")
                .param("itemId", itemId)
                .update();
        int primaryIndex = 0;
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).episodes().size() > lines.get(primaryIndex).episodes().size()) {
                primaryIndex = i;
            }
        }
        for (int i = 0; i < lines.size(); i++) {
            PlayUrlParser.ParsedLine line = lines.get(i);
            String firstUrl = line.episodes().getFirst().url();
            long lineId = jdbc.sql("""
                            INSERT INTO teaplayer_play_line
                                (item_id, play_from, line_index, is_primary, protocol,
                                 episode_count, primary_url, raw)
                            VALUES
                                (:itemId, :playFrom, :lineIndex, :isPrimary, :protocol,
                                 :count, :primaryUrl, CAST(:raw AS jsonb))
                            RETURNING id
                            """)
                    .param("itemId", itemId)
                    .param("playFrom", line.from())
                    .param("lineIndex", i)
                    .param("isPrimary", i == primaryIndex)
                    .param("protocol", PlayUrlParser.protocol(firstUrl))
                    .param("count", line.episodes().size())
                    .param("primaryUrl", firstUrl)
                    .param("raw", "{}")
                    .query(Long.class)
                    .single();
            insertEpisodes(lineId, line.episodes());
        }
        return lines.size();
    }

    private void insertEpisodes(long lineId, List<PlayUrlParser.ParsedEpisode> episodes) {
        List<Map<String, Object>> payload = new ArrayList<>(episodes.size());
        int index = 0;
        for (PlayUrlParser.ParsedEpisode episode : episodes) {
            index++;
            payload.add(Map.of(
                    "idx", index,
                    "name", episode.name(),
                    "url", episode.url(),
                    "host", nullToEmpty(urlHost(episode.url()))));
        }
        String json;
        try {
            json = mapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            throw new IllegalStateException("剧集序列化失败", e);
        }
        jdbc.sql("""
                        INSERT INTO teaplayer_episode
                            (line_id, episode_index, name, url, url_host, created_at, updated_at)
                        SELECT :lineId, x.idx, x.name, x.url, NULLIF(x.host, ''), now(), now()
                          FROM jsonb_to_recordset(CAST(:eps AS jsonb))
                               AS x(idx int, name text, url text, host text)
                        """)
                .param("lineId", lineId)
                .param("eps", json)
                .update();
    }

    /** 按 active 条目重算 group 的计数、排序键与主条目；空组保留，不删除。 */
    @Transactional
    public void rebuildGroup(long groupId, boolean bumpFeedSeq) {
        Long locked = jdbc.sql("SELECT id FROM teaplayer_title_group WHERE id = :id FOR UPDATE")
                .param("id", groupId)
                .query(Long.class)
                .optional()
                .orElse(null);
        if (locked == null) {
            return;
        }
        Aggregate aggregate = jdbc.sql("""
                        SELECT count(*)                    AS item_count,
                               count(DISTINCT source_id)   AS source_count,
                               max(vod_time)               AS vod_time_max,
                               max(expires_at)             AS expires_at
                          FROM teaplayer_source_item
                         WHERE group_id = :groupId AND status = 'active'
                        """)
                .param("groupId", groupId)
                .query((rs, rowNum) -> new Aggregate(
                        rs.getInt("item_count"),
                        rs.getInt("source_count"),
                        rs.getObject("vod_time_max", OffsetDateTime.class),
                        rs.getObject("expires_at", OffsetDateTime.class)))
                .single();
        if (aggregate.itemCount() == 0) {
            jdbc.sql("""
                            UPDATE teaplayer_title_group
                               SET item_count = 0, source_count = 0,
                                   primary_item_id = NULL, primary_source_id = NULL,
                                   expires_at = :now, revision = revision + 1, updated_at = now()
                             WHERE id = :groupId
                            """)
                    .param("now", ts(Instant.now()))
                    .param("groupId", groupId)
                    .update();
            return;
        }
        Primary primary = jdbc.sql("""
                        SELECT i.id, i.source_id, i.title, i.title_norm, i.year, i.category_codes,
                               i.primary_category, i.cover_url, i.remark, i.classifier_version
                          FROM teaplayer_source_item i
                          JOIN teaplayer_source s ON s.id = i.source_id
                         WHERE i.group_id = :groupId AND i.status = 'active'
                         ORDER BY s.priority ASC, i.first_seen_at ASC, i.id ASC
                         LIMIT 1
                        """)
                .param("groupId", groupId)
                .query((rs, rowNum) -> new Primary(
                        rs.getLong("id"),
                        rs.getString("source_id"),
                        rs.getString("title"),
                        rs.getString("title_norm"),
                        rs.getObject("year") == null ? null : ((Number) rs.getObject("year")).shortValue(),
                        codesOf(rs.getArray("category_codes")),
                        rs.getString("primary_category"),
                        rs.getString("cover_url"),
                        rs.getString("remark"),
                        rs.getString("classifier_version")))
                .single();
        jdbc.sql("""
                        UPDATE teaplayer_title_group
                           SET title = :title, title_norm = :titleNorm, year = :year,
                               category_codes = CASE WHEN category_source = 'manual'
                                                     THEN category_codes ELSE CAST(:codes AS text[]) END,
                               primary_category = CASE WHEN category_source = 'manual'
                                                       THEN primary_category ELSE :primary END,
                               cover_url = :cover, remark = :remark,
                               primary_item_id = :primaryItemId,
                               primary_source_id = :primarySourceId,
                               item_count = :itemCount, source_count = :sourceCount,
                               vod_time_max = :vodTimeMax, last_seen_at = now(),
                               expires_at = :expiresAt,
                               classifier_version = :classifierVersion,
                               feed_seq = CASE WHEN :bump THEN nextval('teaplayer_title_group_feed_seq')
                                               ELSE feed_seq END,
                               revision = revision + 1, updated_at = now()
                         WHERE id = :groupId
                        """)
                .param("title", primary.title())
                .param("titleNorm", primary.titleNorm())
                .param("year", primary.year())
                .param("codes", arrayLiteral(primary.codes()))
                .param("primary", primary.primaryCategory())
                .param("cover", primary.cover())
                .param("remark", primary.remark())
                .param("primaryItemId", primary.itemId())
                .param("primarySourceId", primary.sourceId())
                .param("itemCount", aggregate.itemCount())
                .param("sourceCount", aggregate.sourceCount())
                .param("vodTimeMax", aggregate.vodTimeMax())
                .param("expiresAt", aggregate.expiresAt())
                .param("classifierVersion", primary.classifierVersion())
                .param("bump", bumpFeedSeq)
                .param("groupId", groupId)
                .update();
    }

    /** 分批删除过期条目并重算受影响的 group；返回删除条数。group 永不删除。 */
    @Transactional
    public int purgeExpired(Instant now) {
        int deletedTotal = 0;
        while (true) {
            List<Expired> batch = jdbc.sql("""
                            SELECT id, group_id
                              FROM teaplayer_source_item
                             WHERE expires_at < :now
                             ORDER BY id
                             LIMIT :limit
                             FOR UPDATE SKIP LOCKED
                            """)
                    .param("now", ts(now))
                    .param("limit", PURGE_BATCH)
                    .query((rs, rowNum) -> new Expired(
                            rs.getLong("id"),
                            rs.getObject("group_id") == null ? null : rs.getLong("group_id")))
                    .list();
            if (batch.isEmpty()) {
                break;
            }
            List<Long> ids = batch.stream().map(Expired::id).toList();
            Set<Long> groups = new LinkedHashSet<>();
            batch.forEach(row -> {
                if (row.groupId() != null) {
                    groups.add(row.groupId());
                }
            });
            deletedTotal += jdbc.sql("DELETE FROM teaplayer_source_item WHERE id IN (:ids)")
                    .param("ids", ids)
                    .update();
            groups.stream().sorted().forEach(id -> rebuildGroup(id, false));
            if (batch.size() < PURGE_BATCH) {
                break;
            }
        }
        log.info("过期清理 items={}", deletedTotal);
        return deletedTotal;
    }

    /** 元数据 hash：不含 vod_time，避免“重挂时间”导致线路重写。 */
    private String contentHash(RawItem raw) {
        String payload = String.join("\\u0001",
                nullToEmpty(raw.title()),
                nullToEmpty(raw.year()),
                nullToEmpty(raw.category()),
                nullToEmpty(raw.cover()),
                nullToEmpty(raw.remark()),
                nullToEmpty(raw.playFrom()),
                nullToEmpty(raw.playUrl()),
                nullToEmpty(raw.area()),
                nullToEmpty(raw.lang()));
        return TokenHasher.sha256Hex(payload);
    }

    private String playHash(RawItem raw) {
        String play = nullToEmpty(raw.playFrom()) + "\\u0001" + nullToEmpty(raw.playUrl());
        return play.isBlank() ? null : TokenHasher.sha256Hex(play);
    }

    private String extra(RawItem raw) {
        try {
            return mapper.writeValueAsString(raw.raw());
        } catch (RuntimeException e) {
            return "{}";
        }
    }

    private static Short parseYear(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int year = Integer.parseInt(raw.trim());
            return year >= 1900 && year <= 2100 ? (short) year : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String arrayLiteral(List<String> codes) {
        return "{" + String.join(",", codes) + "}";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String urlHost(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(UTC);
    }

    private static List<String> codesOf(java.sql.Array array) {
        try {
            if (array == null) {
                return List.of();
            }
            Object value = array.getArray();
            if (value instanceof String[] values) {
                return List.of(values);
            }
            return List.of();
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }

    private record Existing(long id, Long groupId, Instant vodTime, String contentHash,
                            String playHash, Instant expiresAt) {
    }

    private record Aggregate(int itemCount, int sourceCount,
                             OffsetDateTime vodTimeMax, OffsetDateTime expiresAt) {
    }

    private record Primary(long itemId, String sourceId, String title, String titleNorm, Short year,
                           List<String> codes, String primaryCategory, String cover,
                           String remark, String classifierVersion) {
    }

    private record Expired(long id, Long groupId) {
    }
}
