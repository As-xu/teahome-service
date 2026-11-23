package com.teaplayer.teahome.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 不透明游标：v1:feedSeq:id。feedSeq 只在内容变化时前进，因此翻页不会因 vod_time 更新而漏条。 */
public record Cursor(long seq, long id) {

    public String encode() {
        String raw = "v1:" + seq + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length != 3 || !"v1".equals(parts[0])) {
                throw new IllegalArgumentException("bad cursor version");
            }
            return new Cursor(Long.parseLong(parts[1]), Long.parseLong(parts[2]));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("bad_cursor", "cursor 无效");
        }
    }
}
