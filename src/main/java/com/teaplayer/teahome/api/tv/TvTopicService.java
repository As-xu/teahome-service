package com.teaplayer.teahome.api.tv;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.teaplayer.teahome.api.ApiException;

/**
 * 手机 → TV 的搜索关键词单槽。
 *
 * <p>为什么放服务端：TV 上没有能用的输入法（P7-SPIKE §5.1，遥控器拉起第三方
 * IME 是死路），手机打字最顺手，于是把「想搜什么」变成一条服务端便签。
 * TV 每几秒问一次「有没有新的」，发现 `id` 变了就照它搜。
 *
 * <p>刻意不做历史：多端共用一个电视时，旧关键词没有意义，还会让 TV 重启后
 * 补搜一条几天前的内容。
 */
@Service
public class TvTopicService {

    /** 与 `ck_tv_search_topic__topic` 一致；按去空白后的 UTF-16 长度算。 */
    static final int MAX_TOPIC_CHARS = 40;

    /** 旧行保留窗口：够手机推完、TV 半天没开机再来看一眼就行。 */
    static final Duration RETENTION = Duration.ofHours(1);

    private static final Pattern BLANKS = Pattern.compile("[\\s\\u00a0\\u3000]+");

    private final JdbcClient jdbc;

    public TvTopicService(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** 手机推送：写一条新的单槽主题并返回落库后的事实。 */
    @Transactional
    public TvTopicDtos.Topic push(String rawTopic) {
        String topic = normalize(rawTopic);
        jdbc.sql("""
                        DELETE FROM teaplayer_tv_search_topic
                         WHERE created_at < now() - make_interval(secs => :secs)
                        """)
                .param("secs", (long) RETENTION.toSeconds())
                .update();
        return jdbc.sql("""
                        INSERT INTO teaplayer_tv_search_topic (topic)
                        VALUES (:topic)
                        RETURNING id, topic, created_at
                        """)
                .param("topic", topic)
                .query(TvTopicService::mapRow)
                .single();
    }

    /** TV 轮询：最新一条，没有就返回全 null 的空槽。 */
    public TvTopicDtos.Topic latest() {
        List<TvTopicDtos.Topic> rows = jdbc.sql("""
                        SELECT id, topic, created_at
                          FROM teaplayer_tv_search_topic
                         ORDER BY id DESC
                         LIMIT 1
                        """)
                .query(TvTopicService::mapRow)
                .list();
        return rows.isEmpty() ? TvTopicDtos.Topic.empty() : rows.getFirst();
    }

    private static TvTopicDtos.Topic mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TvTopicDtos.Topic(
                rs.getLong("id"),
                rs.getString("topic"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    /**
     * 折叠所有空白（含全角空格、换行）后校验长度。
     *
     * <p>先折叠再判空：`"   "` 应该报 `bad_topic` 而不是写进一行空格。
     */
    static String normalize(String rawTopic) {
        if (rawTopic == null) {
            throw ApiException.badRequest("bad_topic", "topic 不能为空");
        }
        String topic = BLANKS.matcher(rawTopic).replaceAll(" ").strip();
        if (topic.isEmpty()) {
            throw ApiException.badRequest("bad_topic", "topic 不能为空");
        }
        if (topic.length() > MAX_TOPIC_CHARS) {
            throw ApiException.badRequest("bad_topic", "topic 最长 " + MAX_TOPIC_CHARS + " 字");
        }
        return topic;
    }
}
