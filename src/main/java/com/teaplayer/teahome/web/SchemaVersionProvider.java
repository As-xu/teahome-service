package com.teaplayer.teahome.web;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 从 teaplayer_service_meta 读取 schema_version；迁移未跑时回退为当前代码期望值。 */
@Component
public class SchemaVersionProvider {

    static final String CURRENT_SCHEMA_VERSION = "2";

    private final JdbcClient jdbc;

    public SchemaVersionProvider(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public String schemaVersion() {
        return jdbc.sql("SELECT meta_value FROM teaplayer_service_meta WHERE meta_key = 'schema_version'")
                .query(String.class)
                .optional()
                .orElse(CURRENT_SCHEMA_VERSION);
    }
}
