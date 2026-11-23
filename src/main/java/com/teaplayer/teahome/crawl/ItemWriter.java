package com.teaplayer.teahome.crawl;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.teaplayer.teahome.auth.TokenHasher;

import tools.jackson.databind.ObjectMapper;

/** 条目写入、线路/剧集替换、group 物化与过期清理。 */
@Component
public class ItemWriter {

    private static final Logger log = LoggerFactory.getLogger(ItemWriter.class);
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    public record WriteResult(long itemId, Long groupId, boolean created, boolean renewed, int lines) {
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

    @Transactional
    public WriteResult write(SourceRow source, RawItem raw) {
        Instant now = Instant.now();
        int ttlDays = Math.min(14, Math.max(1, props.getTtlDays()));
        String titleNorm = TitleNormalizer.normalize(raw.title());
        String mergeKey = TitleNormalizer.mergeKey(raw.title(), raw.year());
        Short year = parseYear(raw.year());
        CategoryClassifier.Result category = classifier.classify(raw.category());
        String hash = hash(raw);
        Instant expires = TtlPolicy.expiry(raw.vodTime(), now, ttlDays);

        Long groupId = ensureGroup(raw, category, titleNorm, mergeKey, year, expires, now);

        Optional<Existing> existing = jdbc.sql("""
                        SELECT id, vod_time, content_hash, expires_at
                          FROM teaplayer_source_item
                         WHERE source_id = :sourceId AND item_id = :itemId
                         FOR UPDATE
                        """)
                .param("sourceId", source.id())
                .param("itemId", raw.id())
                .query((rs, rowNum) -> new Existing(
                        rs.getLong("id"),
                        rs.getObject("vod_time", OffsetDateTime.class).toInstant(),
                        rs.getString("content_hash"),
                        rs.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();

        long itemId;
        boolean created = existing.isEmpty();
        boolean renewed = false;
        if (existing.isPresent()) {
            Existing old = existing.get();
            renewed = TtlPolicy.shouldRenew(raw.vodTime(), old.vodTime(), hash, old.contentHash());
            Instant newExpires = renewed ? TtlPolicy.renewedExpiry(old.expiresAt(), expires) : old.expiresAt();
            Instant vodTime = raw.vodTime().isAfter(old.vodTime()) ? raw.vodTime() : old.vodTime();
            itemId = old.id();
            jdbc.sql("""
                            UPDATE teaplayer_source_item
                               SET title = :title, title_norm = :titleNorm, merge_key = :mergeKey,
                                   year = :year, category_codes = CAST(:codes AS text[]),
                                   primary_category = :primary, category_raw = :rawCategory,
                                   category_source = :categorySource, classifier_version = :classifierVersion,
                                   content_hash = :hash, vod_time = :vodTime, expires_at = :expiresAt,
                                   ttl_days_applied = :ttlDays, status = 'active', group_id = :groupId,
                                   cover_url = :cover, remark = :remark, area = :area, lang = :lang,
                                   extra = CAST(:extra AS jsonb), updated_at = :now
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
                    .param("hash", hash)
                    .param("vodTime", ts(vodTime))
                    .param("expiresAt", ts(newExpires))
                    .param("ttlDays", ttlDays)
                    .param("groupId", groupId)
                    .param("cover", blankToNull(raw.cover()))
                    .param("remark", blankToNull(raw.remark()))
                    .param("area", blankToNull(raw.area()))
                    .param("lang", blankToNull(raw.lang()))
                    .param("extra", extra(raw))
                    .param("now", ts(now))
                    .param("id", itemId)
                    .update();
        } else {
            itemId = jdbc.sql("""
                            INSERT INTO teaplayer_source_item
                                (source_id, item_id, title, title_norm, merge_key, year,
                                 category_codes, primary_category, category_raw, category_source,
                                 classifier_version, content_hash, vod_time, expires_at, ttl_days_applied,
                                 status, group_id, cover_url, remark, area, lang, extra,
                                 first_seen_at, updated_at)
                            VALUES
                                (:sourceId, :itemId, :title, :titleNorm, :mergeKey, :year,
                                 CAST(:codes AS text[]), :primary, :rawCategory, :categorySource,
                                 :classifierVersion, :hash, :vodTime, :expiresAt, :ttlDays,
                                 'active', :groupId, :cover, :remark, :area, :lang, CAST(:extra AS jsonb),
                                 :now, :now)
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
                    .param("hash", hash)
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

        int lines = replaceLines(itemId, source, raw);
        if (groupId != null) {
            rebuildGroup(groupId);
        }
        return new WriteResult(itemId, groupId, created, renewed, lines);
    }

    private Long ensureGroup(RawItem raw, CategoryClassifier.Result category, String titleNorm,
                             String mergeKey, Short year, Instant expires, Instant now) {
        if (category.primary() == null) {
            return null;
        }
        Long id = jdbc.sql("""
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
                        ON CONFLICT (merge_key) DO NOTHING
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
                .param("expiresAt", ts(expires))
                .param("classifierVersion", classifier.version())
                .query(Long.class)
                .optional()
                .orElse(null);
        if (id != null) {
            return id;
        }
        return jdbc.sql("SELECT id FROM teaplayer_title_group WHERE merge_key = :mergeKey")
                .param("mergeKey", mergeKey)
                .query(Long.class)
                .single();
    }

    private int replaceLines(long itemId, SourceRow source, RawItem raw) {
        List<PlayUrlParser.ParsedLine> lines = PlayUrlParser.parse(
                raw.playFrom(), raw.playUrl(), source.parsedSpec().groupSep(),
                source.parsedSpec().episodeSep(), source.parsedSpec().nameSep());
        jdbc.sql("DELETE FROM teaplayer_play_line WHERE item_id = :itemId")
                .param("itemId", itemId)
                .update();
        if (lines.isEmpty()) {
            return 0;
        }
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
            int episodeIndex = 0;
            for (PlayUrlParser.ParsedEpisode episode : line.episodes()) {
                episodeIndex++;
                jdbc.sql("""
                                INSERT INTO teaplayer_episode
                                    (line_id, episode_index, name, url, url_host, created_at, updated_at)
                                VALUES
                                    (:lineId, :episodeIndex, :name, :url, :urlHost, now(), now())
                                """)
                        .param("lineId", lineId)
                        .param("episodeIndex", episodeIndex)
                        .param("name", episode.name())
                        .param("url", episode.url())
                        .param("urlHost", urlHost(episode.url()))
                        .update();
            }
        }
        return lines.size();
    }

    /** 按 active 条目重算 group 的计数、排序键与主条目。 */
    @Transactional
    public void rebuildGroup(long groupId) {
        Aggregate aggregate = jdbc.sql("""
                        SELECT count(*)          AS item_count,
                               count(DISTINCT source_id) AS source_count,
                               max(vod_time)     AS vod_time_max,
                               max(expires_at)   AS expires_at
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
            jdbc.sql("DELETE FROM teaplayer_title_group WHERE id = :groupId")
                    .param("groupId", groupId)
                    .update();
            return;
        }
        Primary primary = jdbc.sql("""
                        SELECT id, source_id, title, title_norm, year, category_codes,
                               primary_category, cover_url, remark, classifier_version
                          FROM teaplayer_source_item
                         WHERE group_id = :groupId AND status = 'active'
                         ORDER BY vod_time DESC, id DESC
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
                               category_codes = CAST(:codes AS text[]),
                               primary_category = :primary,
                               category_source = 'primary',
                               cover_url = :cover, remark = :remark,
                               primary_item_id = :primaryItemId,
                               primary_source_id = :primarySourceId,
                               item_count = :itemCount, source_count = :sourceCount,
                               vod_time_max = :vodTimeMax, last_seen_at = now(),
                               expires_at = :expiresAt,
                               classifier_version = :classifierVersion,
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
                .param("groupId", groupId)
                .update();
    }

    /** 删除过期条目并重算受影响的 group；返回删除条数。 */
    @Transactional
    public int purgeExpired(Instant now) {
        List<Long> groupIds = jdbc.sql("""
                        SELECT DISTINCT group_id
                          FROM teaplayer_source_item
                         WHERE expires_at < :now AND group_id IS NOT NULL
                        """)
                .param("now", ts(now))
                .query(Long.class)
                .list();
        int deleted = jdbc.sql("DELETE FROM teaplayer_source_item WHERE expires_at < :now")
                .param("now", ts(now))
                .update();
        for (Long groupId : groupIds) {
            rebuildGroup(groupId);
        }
        int groups = jdbc.sql("""
                        DELETE FROM teaplayer_title_group g
                         WHERE g.expires_at < :now
                           AND NOT EXISTS (SELECT 1 FROM teaplayer_source_item i WHERE i.group_id = g.id)
                        """)
                .param("now", ts(now))
                .update();
        log.info("过期清理 items={} groups={}", deleted, groups);
        return deleted;
    }

    private String hash(RawItem raw) {
        String payload = String.join("\u0001",
                raw.title(),
                nullToEmpty(raw.year()),
                nullToEmpty(raw.category()),
                nullToEmpty(raw.cover()),
                nullToEmpty(raw.remark()),
                nullToEmpty(raw.playFrom()),
                nullToEmpty(raw.playUrl()),
                nullToEmpty(raw.area()),
                nullToEmpty(raw.lang()),
                Long.toString(raw.vodTime().getEpochSecond()));
        return TokenHasher.sha256Hex(payload);
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

    private record Existing(long id, Instant vodTime, String contentHash, Instant expiresAt) {
    }

    private record Aggregate(int itemCount, int sourceCount,
                             OffsetDateTime vodTimeMax, OffsetDateTime expiresAt) {
    }

    private record Primary(long itemId, String sourceId, String title, String titleNorm, Short year,
                           List<String> codes, String primaryCategory, String cover,
                           String remark, String classifierVersion) {
    }
}
