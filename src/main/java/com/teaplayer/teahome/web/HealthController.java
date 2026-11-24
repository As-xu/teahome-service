package com.teaplayer.teahome.web;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 存活 + 就绪检查：数据库可达且 schema_version 与代码期望一致。 */
@RestController
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> body = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(2);
            try (ResultSet rs = statement.executeQuery(
                    "SELECT meta_value FROM teaplayer_service_meta WHERE meta_key = 'schema_version'")) {
                if (!rs.next()) {
                    return down(body, "schema_missing");
                }
                String actual = rs.getString(1);
                if (!SchemaVersionProvider.CURRENT_SCHEMA_VERSION.equals(actual)) {
                    body.put("expectedSchemaVersion", SchemaVersionProvider.CURRENT_SCHEMA_VERSION);
                    body.put("actualSchemaVersion", actual);
                    return down(body, "schema_mismatch");
                }
                body.put("status", "UP");
                body.put("schemaVersion", actual);
                return ResponseEntity.ok(body);
            }
        } catch (Exception e) {
            return down(body, "db_unavailable");
        }
    }

    private static ResponseEntity<Map<String, String>> down(Map<String, String> body, String reason) {
        body.put("status", "DOWN");
        body.put("reason", reason);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
