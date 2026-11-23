package com.teaplayer.teahome.crawl;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 源注册表读取；P1 只读，管理端写入口在 P4。 */
@Repository
public class SourceRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper mapper;

    public SourceRepository(JdbcClient jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public List<SourceRow> enabledSources() {
        return jdbc.sql("""
                        SELECT id, name, kind, base_url, spec, priority, allow_hosts
                          FROM teaplayer_source
                         WHERE is_enabled
                           AND deleted_at IS NULL
                         ORDER BY priority, id
                        """)
                .query((rs, rowNum) -> new SourceRow(
                        rs.getString("id"),
                        rs.getString("name"),
                        rs.getString("kind"),
                        rs.getString("base_url"),
                        parseSpec(rs.getString("spec")),
                        rs.getInt("priority"),
                        strings(rs.getArray("allow_hosts"))))
                .list();
    }

    private JsonNode parseSpec(String json) {
        try {
            return mapper.readTree(json);
        } catch (RuntimeException e) {
            throw new IllegalStateException("源规格不是合法 JSON", e);
        }
    }

    private static List<String> strings(java.sql.Array array) {
        if (array == null) {
            return List.of();
        }
        try {
            Object value = array.getArray();
            if (value instanceof String[] values) {
                return List.of(values);
            }
            return List.of();
        } catch (java.sql.SQLException e) {
            return List.of();
        }
    }

    public void markSuccess(String sourceId, int latencyMs) {
        jdbc.sql("""
                        UPDATE teaplayer_source
                           SET last_ok_at = now(),
                               consecutive_failures = 0,
                               last_error_code = NULL,
                               last_error_detail = NULL,
                               avg_latency_ms = CASE
                                   WHEN avg_latency_ms IS NULL THEN :latency
                                   ELSE (avg_latency_ms * 4 + :latency) / 5
                               END,
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("latency", latencyMs)
                .param("id", sourceId)
                .update();
    }

    public void markFailure(String sourceId, String code, String detail) {
        jdbc.sql("""
                        UPDATE teaplayer_source
                           SET last_error_at = now(),
                               last_error_code = :code,
                               last_error_detail = :detail,
                               consecutive_failures = consecutive_failures + 1,
                               updated_at = now()
                         WHERE id = :id
                        """)
                .param("code", truncate(code, 64))
                .param("detail", truncate(detail, 500))
                .param("id", sourceId)
                .update();
    }

    public Optional<String> findSourceId(String sourceId) {
        return jdbc.sql("SELECT id FROM teaplayer_source WHERE id = :id AND deleted_at IS NULL")
                .param("id", sourceId)
                .query(String.class)
                .optional();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
