package com.teaplayer.teahome.crawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 真实 PostgreSQL 上的抓取写库链路：解析 MacCMS JSON → ItemWriter 落库 → 分组聚合与续期。
 *
 * <p>仅当设置 {@code TEAHOME_IT_DB_URL} 时运行（默认跳过），库由 Flyway 现建；
 * 账号密码取 {@code TEAHOME_IT_DB_USER}/{@code TEAHOME_IT_DB_PASSWORD}。</p>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEAHOME_IT_DB_URL", matches = ".+")
class CrawlWriteIntegrationTest {

    private static final String SOURCE_ID = "it_maccms";
    private static final String MERGE_KEY_PREFIX = "集成测试剧";
    private static final String ITEM_1 = "9001";
    private static final String ITEM_2 = "9002";
    private static final DateTimeFormatter VOD_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("TEAHOME_IT_DB_URL"));
        registry.add("spring.datasource.username",
                () -> Optional.ofNullable(System.getenv("TEAHOME_IT_DB_USER")).orElse("teahome_dev"));
        registry.add("spring.datasource.password",
                () -> Optional.ofNullable(System.getenv("TEAHOME_IT_DB_PASSWORD")).orElse(""));
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("teahome.scheduling-enabled", () -> "false");
        registry.add("teahome.ops.scheduled", () -> "false");
        registry.add("teahome.crawl.enabled", () -> "false");
    }

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private MacCmsClient client;

    @Autowired
    private ItemWriter writer;

    @Autowired
    private SourceRepository sources;

    @BeforeEach
    @AfterEach
    void cleanDatabase() {
        jdbc.sql("""
                        DELETE FROM teaplayer_episode
                         WHERE line_id IN (SELECT id FROM teaplayer_play_line
                                            WHERE item_id IN (SELECT id FROM teaplayer_source_item
                                                               WHERE source_id = :source))
                        """).param("source", SOURCE_ID).update();
        jdbc.sql("""
                        DELETE FROM teaplayer_play_line
                         WHERE item_id IN (SELECT id FROM teaplayer_source_item WHERE source_id = :source)
                        """).param("source", SOURCE_ID).update();
        jdbc.sql("DELETE FROM teaplayer_source_item WHERE source_id = :source")
                .param("source", SOURCE_ID).update();
        jdbc.sql("DELETE FROM teaplayer_title_group WHERE merge_key LIKE :prefix")
                .param("prefix", MERGE_KEY_PREFIX + "%").update();
        jdbc.sql("DELETE FROM teaplayer_source WHERE id = :source")
                .param("source", SOURCE_ID).update();
    }

    private static final String SPEC = """
            {
              "endpoints": {"list": "/api.php/provide/vod?ac=list&pg={page}"},
              "fields": {
                "id": "vod_id", "title": "vod_name", "year": "vod_year",
                "category": "vod_class|type_name", "cover": "vod_pic", "remark": "vod_remarks",
                "playFrom": "vod_play_from", "playUrl": "vod_play_url"
              },
              "itemsPath": "list",
              "playUrl": {"groupSep": "$$$", "episodeSep": "#", "nameSep": "$"}
            }
            """;

    private SourceRow insertSource() {
        jdbc.sql("""
                        INSERT INTO teaplayer_source (id, name, kind, base_url, spec, allow_hosts, priority)
                        VALUES (:id, :name, 'maccms', 'http://example.com',
                                CAST(:spec AS jsonb), ARRAY['example.com'], 100)
                        """)
                .param("id", SOURCE_ID)
                .param("name", "集成测试源")
                .param("spec", SPEC)
                .update();
        List<SourceRow> rows = sources.enabledSources();
        return rows.stream().filter(s -> SOURCE_ID.equals(s.id())).findFirst()
                .orElseThrow(() -> new IllegalStateException("源未进入 enabledSources"));
    }

    private JsonNode listJson(String vodTime) {
        String json = """
                {
                  "code": 1,
                  "list": [
                    {
                      "vod_id": %s,
                      "vod_name": "集成测试剧 第一季",
                      "vod_year": "2024",
                      "vod_class": "电视剧",
                      "vod_pic": "http://example.com/cover1.jpg",
                      "vod_remarks": "更新至02集",
                      "vod_play_from": "line",
                      "vod_play_url": "第01集$http://cdn.example.com/1.m3u8#第02集$http://cdn.example.com/2.m3u8",
                      "vod_time": "%s",
                      "vod_area": "国产",
                      "vod_lang": "国语"
                    },
                    {
                      "vod_id": %s,
                      "vod_name": "集成测试剧 第二季",
                      "vod_year": "2024",
                      "vod_class": "电视剧",
                      "vod_pic": "http://example.com/cover2.jpg",
                      "vod_remarks": "已完结",
                      "vod_play_from": "line",
                      "vod_play_url": "第01集$http://cdn.example.com/s2-1.m3u8",
                      "vod_time": "%s"
                    }
                  ]
                }
                """.formatted(ITEM_1, vodTime, ITEM_2, vodTime);
        return mapper.readTree(json);
    }

    private String recentVodTime(long minusMinutes) {
        return VOD_TIME.format(Instant.now().minus(minusMinutes, ChronoUnit.MINUTES));
    }

    @Test
    void parsesFixtureAndWritesRealRowsWithGroupAggregation() {
        SourceRow source = insertSource();
        String vodTime = recentVodTime(90);
        List<RawItem> items = client.parseItems(listJson(vodTime), source.parsedSpec(), Instant.now());

        assertEquals(List.of(ITEM_1, ITEM_2), items.stream().map(RawItem::id).toList());
        assertEquals("集成测试剧 第一季", items.getFirst().title());
        assertEquals("电视剧", items.getFirst().category());
        assertTrue(items.getFirst().playUrl().contains("1.m3u8"));

        ItemWriter.WriteResult first = writer.write(source, items.get(0), true);
        ItemWriter.WriteResult second = writer.write(source, items.get(1), true);

        assertTrue(first.created());
        assertTrue(second.created());
        assertTrue(first.itemId() > 0);
        assertNotNull(first.groupId());

        Map<String, Object> itemRow = jdbc.sql("""
                        SELECT id, group_id, status, expires_at, cover_url, remark, area, lang,
                               title_norm, primary_category, category_codes
                          FROM teaplayer_source_item
                         WHERE source_id = :source AND item_id = :item
                        """)
                .param("source", SOURCE_ID).param("item", ITEM_1)
                .query().singleRow();
        assertEquals("active", itemRow.get("status"));
        assertEquals(first.groupId(), ((Number) itemRow.get("group_id")).longValue());
        assertEquals("http://example.com/cover1.jpg", itemRow.get("cover_url"));
        assertEquals("国产", itemRow.get("area"));
        assertTrue(((java.sql.Timestamp) itemRow.get("expires_at")).toInstant().isAfter(Instant.now()));

        Map<String, Object> line = jdbc.sql("""
                        SELECT id, play_from, protocol, episode_count
                          FROM teaplayer_play_line WHERE item_id = :item
                        """).param("item", first.itemId()).query().singleRow();
        assertEquals("line", line.get("play_from"));
        assertEquals("hls", line.get("protocol"));
        assertEquals(2, ((Number) line.get("episode_count")).intValue());

        List<Map<String, Object>> episodes = jdbc.sql("""
                        SELECT episode_index, name, url, url_host
                          FROM teaplayer_episode WHERE line_id = :line ORDER BY episode_index
                        """).param("line", line.get("id")).query().listOfRows();
        assertEquals(2, episodes.size());
        assertEquals("第01集", episodes.getFirst().get("name"));
        assertEquals("cdn.example.com", episodes.getFirst().get("url_host"));

        // TitleNormalizer 会剥掉“第X季”，两季同名剧 merge_key 相同 → 归入同一个分组，
        // 分组按条目数聚合，来源仍只有一个。
        assertEquals(first.groupId(), second.groupId());
        Map<String, Object> group = jdbc.sql("""
                        SELECT id, merge_key, item_count, source_count, primary_category, expires_at
                          FROM teaplayer_title_group WHERE id = :id
                        """).param("id", first.groupId()).query().singleRow();
        assertEquals(2, ((Number) group.get("item_count")).intValue());
        assertEquals(1, ((Number) group.get("source_count")).intValue());
        assertEquals("tv", group.get("primary_category"));
        assertTrue(((java.sql.Timestamp) group.get("expires_at")).toInstant().isAfter(Instant.now()));
    }

    @Test
    void listOnlyRefreshRenewsExpiryWithoutTouchingEpisodes() {
        SourceRow source = insertSource();
        String older = recentVodTime(180);
        RawItem first = client.parseItems(listJson(older), source.parsedSpec(), Instant.now()).getFirst();
        ItemWriter.WriteResult created = writer.write(source, first, true);
        assertTrue(created.created());
        Instant before = jdbc.sql("SELECT expires_at FROM teaplayer_source_item WHERE id = :id")
                .param("id", created.itemId()).query(java.sql.Timestamp.class).single().toInstant();

        // 列表刷新只带时间字段，只有 vod_time 前移时才续期。
        RawItem listing = client.parseItems(listJson(recentVodTime(10)), source.parsedSpec(), Instant.now())
                .getFirst();
        ItemWriter.WriteResult renewed = writer.write(source, listing, false);

        assertFalse(renewed.created());
        assertTrue(renewed.renewed());
        Instant after = jdbc.sql("SELECT expires_at FROM teaplayer_source_item WHERE id = :id")
                .param("id", created.itemId()).query(java.sql.Timestamp.class).single().toInstant();
        assertTrue(after.isAfter(before), "expires_at 应随 vod_time 前移而延长: " + before + " -> " + after);

        // 旧 vod_time 不会缩短有效期。
        RawItem stale = client.parseItems(listJson(older), source.parsedSpec(), Instant.now()).getFirst();
        ItemWriter.WriteResult unchanged = writer.write(source, stale, false);
        assertFalse(unchanged.renewed());
        Instant still = jdbc.sql("SELECT expires_at FROM teaplayer_source_item WHERE id = :id")
                .param("id", created.itemId()).query(java.sql.Timestamp.class).single().toInstant();
        assertEquals(after, still);
    }
}
