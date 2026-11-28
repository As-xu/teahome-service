package com.teaplayer.teahome.api.watch;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teaplayer.teahome.api.ApiException;

/**
 * 收藏 / 观看进度跨端的存储。
 *
 * <p>两个决定值得记下来：
 *
 * <ul>
 *   <li><b>按 token 归属</b>，不按设备。同一枚 token 的手机和电视就是同一份片库，
 *       这是家用场景（一台电视 + 一两台手机）最省事的口径。</li>
 *   <li><b>冲突按时间戳决胜</b>（`updatedAt` 由客户端在本地写入时打）。服务端只
 *       存最新那一份，不做合并——两端同时改同一条属于极端情况，真发生了就是
 *       「最后写的那次赢」，比引入向量时钟便宜得多。</li>
 * </ul>
 *
 * <p>删除写墓碑（`deleted_at`），不物理删：否则另一台设备下次拉取看不到这条，
 * 会误以为是新条目再推上来，删了又回来。
 */
@Service
public class WatchSyncService {

    /** 单个 token 一次拉取的上限。客户端本地也只留这么多（进度 200 / 收藏 500）。 */
    static final int MAX_ROWS = 1000;

    /** 墓碑保留期：够离线设备回来同步一次，再久就没意义了。 */
    static final int TOMBSTONE_DAYS = 30;

    private static final int MAX_KEY_CHARS = 300;
    private static final int MAX_TEXT_CHARS = 300;
    private static final int MAX_EPISODES = 5000;

    private final JdbcClient jdbc;

    public WatchSyncService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 进度

    public WatchDtos.ProgressList listProgress(long tokenId) {
        List<WatchDtos.ProgressItem> items = jdbc.sql("""
                        SELECT item_key, source_id, item_id, title, line_index, episode_index,
                               episode_name, position_ms, duration_ms, episodes, updated_at, deleted_at
                          FROM teaplayer_watch_progress
                         WHERE token_id = :token
                         ORDER BY updated_at DESC
                         LIMIT :limit
                        """)
                .param("token", tokenId)
                .param("limit", MAX_ROWS)
                .query((rs, rowNum) -> new WatchDtos.ProgressItem(
                        rs.getString("item_key"),
                        rs.getString("source_id"),
                        rs.getString("item_id"),
                        rs.getString("title"),
                        rs.getInt("line_index"),
                        rs.getInt("episode_index"),
                        rs.getString("episode_name"),
                        rs.getLong("position_ms"),
                        rs.getLong("duration_ms"),
                        decodeEpisodes(rs.getString("episodes")),
                        instant(rs, "updated_at"),
                        rs.getObject("deleted_at", OffsetDateTime.class) != null))
                .list();
        return new WatchDtos.ProgressList(items, Instant.now().toString());
    }

    @Transactional
    public WatchDtos.ProgressItem pushProgress(long tokenId, WatchDtos.ProgressItem in) {
        WatchDtos.ProgressItem clean = normalizeProgress(in);
        pruneTombstones();
        jdbc.sql("""
                        INSERT INTO teaplayer_watch_progress
                            (token_id, item_key, source_id, item_id, title, line_index, episode_index,
                             episode_name, position_ms, duration_ms, episodes, updated_at, deleted_at)
                        VALUES
                            (:token, :key, :sourceId, :itemId, :title, :lineIndex, :episodeIndex,
                             :episodeName, :position, :duration, :episodes, :updatedAt, NULL)
                        ON CONFLICT (token_id, item_key) DO UPDATE SET
                            source_id = EXCLUDED.source_id,
                            item_id = EXCLUDED.item_id,
                            title = EXCLUDED.title,
                            line_index = EXCLUDED.line_index,
                            episode_index = EXCLUDED.episode_index,
                            episode_name = EXCLUDED.episode_name,
                            position_ms = EXCLUDED.position_ms,
                            duration_ms = EXCLUDED.duration_ms,
                            episodes = EXCLUDED.episodes,
                            updated_at = EXCLUDED.updated_at,
                            deleted_at = NULL
                        """)
                .param("token", tokenId)
                .param("key", clean.key())
                .param("sourceId", clean.sourceId())
                .param("itemId", clean.itemId())
                .param("title", clean.title())
                .param("lineIndex", clean.lineIndex())
                .param("episodeIndex", clean.episodeIndex())
                .param("episodeName", clean.episodeName())
                .param("position", clean.position())
                .param("duration", clean.duration())
                .param("episodes", encodeEpisodes(clean.episodes()))
                .param("updatedAt", Instant.parse(clean.updatedAt()).atOffset(ZoneOffset.UTC))
                .update();
        return clean;
    }

    /** 删一条（写墓碑）。key 为空表示清空该 token 的全部进度。 */
    @Transactional
    public int deleteProgress(long tokenId, String key) {
        if (isBlankKey(key)) {
            return jdbc.sql("""
                            UPDATE teaplayer_watch_progress
                               SET deleted_at = now(), updated_at = now()
                             WHERE token_id = :token AND deleted_at IS NULL
                            """)
                    .param("token", tokenId)
                    .update();
        }
        return jdbc.sql("""
                        UPDATE teaplayer_watch_progress
                           SET deleted_at = now(), updated_at = now()
                         WHERE token_id = :token AND item_key = :key AND deleted_at IS NULL
                        """)
                .param("token", tokenId)
                .param("key", key.strip())
                .update();
    }

    // ------------------------------------------------------------------ 收藏

    public WatchDtos.FavoriteList listFavorites(long tokenId) {
        List<WatchDtos.FavoriteItem> items = jdbc.sql("""
                        SELECT item_key, source_id, source_name, item_id, title, year, category,
                               cover_url, remark, added_at, updated_at, deleted_at
                          FROM teaplayer_favorite
                         WHERE token_id = :token
                         ORDER BY added_at DESC
                         LIMIT :limit
                        """)
                .param("token", tokenId)
                .param("limit", MAX_ROWS)
                .query((rs, rowNum) -> new WatchDtos.FavoriteItem(
                        rs.getString("item_key"),
                        rs.getString("source_id"),
                        rs.getString("source_name"),
                        rs.getString("item_id"),
                        rs.getString("title"),
                        rs.getString("year"),
                        rs.getString("category"),
                        rs.getString("cover_url"),
                        rs.getString("remark"),
                        instant(rs, "added_at"),
                        instant(rs, "updated_at"),
                        rs.getObject("deleted_at", OffsetDateTime.class) != null))
                .list();
        return new WatchDtos.FavoriteList(items, Instant.now().toString());
    }

    @Transactional
    public WatchDtos.FavoriteItem pushFavorite(long tokenId, WatchDtos.FavoriteItem in) {
        WatchDtos.FavoriteItem clean = normalizeFavorite(in);
        pruneTombstones();
        jdbc.sql("""
                        INSERT INTO teaplayer_favorite
                            (token_id, item_key, source_id, source_name, item_id, title, year,
                             category, cover_url, remark, added_at, updated_at, deleted_at)
                        VALUES
                            (:token, :key, :sourceId, :sourceName, :itemId, :title, :year,
                             :category, :coverUrl, :remark, :addedAt, :updatedAt, NULL)
                        ON CONFLICT (token_id, item_key) DO UPDATE SET
                            source_id = EXCLUDED.source_id,
                            source_name = EXCLUDED.source_name,
                            item_id = EXCLUDED.item_id,
                            title = EXCLUDED.title,
                            year = EXCLUDED.year,
                            category = EXCLUDED.category,
                            cover_url = EXCLUDED.cover_url,
                            remark = EXCLUDED.remark,
                            added_at = EXCLUDED.added_at,
                            updated_at = EXCLUDED.updated_at,
                            deleted_at = NULL
                        """)
                .param("token", tokenId)
                .param("key", clean.key())
                .param("sourceId", clean.sourceId())
                .param("sourceName", clean.sourceName())
                .param("itemId", clean.itemId())
                .param("title", clean.title())
                .param("year", clean.year())
                .param("category", clean.category())
                .param("coverUrl", clean.coverUrl())
                .param("remark", clean.remark())
                .param("addedAt", Instant.parse(clean.addedAt()).atOffset(ZoneOffset.UTC))
                .param("updatedAt", Instant.parse(clean.updatedAt()).atOffset(ZoneOffset.UTC))
                .update();
        return clean;
    }

    @Transactional
    public int deleteFavorite(long tokenId, String key) {
        if (isBlankKey(key)) {
            return jdbc.sql("""
                            UPDATE teaplayer_favorite
                               SET deleted_at = now(), updated_at = now()
                             WHERE token_id = :token AND deleted_at IS NULL
                            """)
                    .param("token", tokenId)
                    .update();
        }
        return jdbc.sql("""
                        UPDATE teaplayer_favorite
                           SET deleted_at = now(), updated_at = now()
                         WHERE token_id = :token AND item_key = :key AND deleted_at IS NULL
                        """)
                .param("token", tokenId)
                .param("key", key.strip())
                .update();
    }

    // ------------------------------------------------------------------ 内部

    /** 顺手清过期墓碑：写入是低频繁路径，放这里比单开一个定时任务划算。 */
    private void pruneTombstones() {
        String interval = "now() - make_interval(days => " + TOMBSTONE_DAYS + ")";
        jdbc.sql("DELETE FROM teaplayer_watch_progress WHERE deleted_at IS NOT NULL"
                        + " AND deleted_at < " + interval)
                .update();
        jdbc.sql("DELETE FROM teaplayer_favorite WHERE deleted_at IS NOT NULL"
                        + " AND deleted_at < " + interval)
                .update();
    }

    private static String instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant().toString();
    }

    private static boolean isBlankKey(String key) {
        return key == null || key.isBlank();
    }

    /** key 由服务端用 sourceId/itemId 归一，免得两端拼法不一致。 */
    WatchDtos.ProgressItem normalizeProgress(WatchDtos.ProgressItem in) {
        if (in == null) {
            throw ApiException.badRequest("bad_progress", "body 不能为空");
        }
        String sourceId = text(in.sourceId(), "sourceId");
        String itemId = text(in.itemId(), "itemId");
        String title = text(in.title(), "title");
        String key = sourceId + '|' + itemId;
        if (key.length() > MAX_KEY_CHARS) {
            throw ApiException.badRequest("bad_progress", "key 过长");
        }
        long position = in.position() == null ? 0 : in.position();
        long duration = in.duration() == null ? 0 : in.duration();
        if (position < 0 || duration < 0) {
            throw ApiException.badRequest("bad_progress", "position/duration 不能为负");
        }
        return new WatchDtos.ProgressItem(
                key, sourceId, itemId, title,
                nonNegative(in.lineIndex()), nonNegative(in.episodeIndex()),
                in.episodeName() == null ? "" : in.episodeName(),
                position, duration,
                cleanEpisodes(in.episodes()),
                timestamp(in.updatedAt()),
                false);
    }

    WatchDtos.FavoriteItem normalizeFavorite(WatchDtos.FavoriteItem in) {
        if (in == null) {
            throw ApiException.badRequest("bad_favorite", "body 不能为空");
        }
        String sourceId = text(in.sourceId(), "sourceId");
        String itemId = text(in.itemId(), "itemId");
        String title = text(in.title(), "title");
        String key = sourceId + '|' + itemId;
        if (key.length() > MAX_KEY_CHARS) {
            throw ApiException.badRequest("bad_favorite", "key 过长");
        }
        String addedAt = timestamp(in.addedAt());
        String updatedAt = isBlank(in.updatedAt()) ? addedAt : timestamp(in.updatedAt());
        return new WatchDtos.FavoriteItem(
                key, sourceId,
                isBlank(in.sourceName()) ? sourceId : in.sourceName().strip(),
                itemId, title,
                blankToNull(in.year()), blankToNull(in.category()),
                blankToNull(in.coverUrl()), blankToNull(in.remark()),
                addedAt, updatedAt, false);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(String value, String field) {
        if (isBlank(value)) {
            throw ApiException.badRequest("bad_item", field + " 不能为空");
        }
        String trimmed = value.strip();
        if (trimmed.length() > MAX_TEXT_CHARS) {
            throw ApiException.badRequest("bad_item", field + " 过长");
        }
        return trimmed;
    }

    private static int nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.strip();
    }

    /** 缺省/坏掉的时间戳退化成「现在」：客户端时钟没对好不该让整条记录丢掉。 */
    private static String timestamp(String value) {
        if (!isBlank(value)) {
            try {
                return Instant.parse(value).toString();
            } catch (RuntimeException ignored) {
                // 落到 now()
            }
        }
        return Instant.now().toString();
    }

    Map<String, List<Long>> cleanEpisodes(Map<String, List<Long>> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        if (raw.size() > MAX_EPISODES) {
            throw ApiException.badRequest("bad_episodes", "集数过多");
        }
        Map<String, List<Long>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Long>> entry : raw.entrySet()) {
            if (isBlank(entry.getKey())) {
                continue;
            }
            List<Long> pair = entry.getValue();
            if (pair == null || pair.size() != 2) {
                throw ApiException.badRequest("bad_episodes", "episodes 的值必须是 [位置, 时长]");
            }
            long pos = pair.get(0) == null ? 0 : pair.get(0);
            long dur = pair.get(1) == null ? 0 : pair.get(1);
            if (pos < 0 || dur < 0) {
                throw ApiException.badRequest("bad_episodes", "位置/时长不能为负");
            }
            out.put(entry.getKey(), List.of(pos, dur));
        }
        return out;
    }

    /**
     * 按集记录编码成 `<集号>:<位置>:<时长>`，用 `;` 连接。
     *
     * <p>刻意不用 jsonb/JSON 库：这两个数字组成的结构没有转义需求，纯文本反而
     * 少一层依赖（本项目 Boot 4.2 的 JSON 栈是 Jackson 3，主代码里没有
     * `ObjectMapper` 可用），编解码也就十行，能单测。
     */
    String encodeEpisodes(Map<String, List<Long>> episodes) {
        if (episodes == null || episodes.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, List<Long>> entry : episodes.entrySet()) {
            List<Long> pair = entry.getValue();
            if (out.length() > 0) {
                out.append(';');
            }
            out.append(entry.getKey()).append(':')
                    .append(pair.get(0)).append(':')
                    .append(pair.get(1));
        }
        return out.toString();
    }

    /** 库里坏一条不该让整次拉取失败：解析不了就当没有按集记录。 */
    Map<String, List<Long>> decodeEpisodes(String raw) {
        if (isBlank(raw)) {
            return Map.of();
        }
        Map<String, List<Long>> out = new LinkedHashMap<>();
        for (String part : raw.split(";")) {
            String[] fields = part.split(":");
            if (fields.length != 3 || fields[0].isEmpty()
                    || !fields[0].chars().allMatch(Character::isDigit)) {
                return Map.of();
            }
            try {
                long pos = Long.parseLong(fields[1]);
                long dur = Long.parseLong(fields[2]);
                out.put(fields[0], List.of(pos, dur));
            } catch (NumberFormatException e) {
                return Map.of();
            }
        }
        return out;
    }
}
