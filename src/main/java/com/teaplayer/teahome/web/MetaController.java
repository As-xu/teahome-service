package com.teaplayer.teahome.web;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/meta：客户端启动自检。字段是契约的一部分，新增只能加不能改语义。
 */
@RestController
@RequestMapping("/v1")
public class MetaController {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> CATEGORY_KEYS = List.of(
            "movie", "tv", "anime", "variety", "documentary", "short", "other");

    private final SchemaVersionProvider schemaVersionProvider;

    public MetaController(SchemaVersionProvider schemaVersionProvider) {
        this.schemaVersionProvider = schemaVersionProvider;
    }

    @GetMapping("/meta")
    public MetaResponse meta() {
        return new MetaResponse(
                List.of("v1"),
                true,
                CATEGORY_KEYS,
                OffsetDateTime.now(DISPLAY_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                schemaVersionProvider.schemaVersion());
    }

    public record MetaResponse(
            List<String> apiVersions,
            boolean tokenRequired,
            List<String> categoryKeys,
            String serverTime,
            String schemaVersion) {
    }
}
