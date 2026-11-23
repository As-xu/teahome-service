package com.teaplayer.teahome.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 首次启动时把 TEAHOME_BOOTSTRAP_TOKEN 的哈希写入 teaplayer_api_token。
 * 只允许一次（token_hash 唯一 + ON CONFLICT DO NOTHING），明文不落库。
 */
@Component
public class BootstrapTokenInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapTokenInitializer.class);

    private final JdbcClient jdbc;
    private final String bootstrapToken;

    public BootstrapTokenInitializer(
            JdbcClient jdbc,
            @Value("${teahome.token.bootstrap:}") String bootstrapToken) {
        this.jdbc = jdbc;
        this.bootstrapToken = bootstrapToken;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (bootstrapToken == null || bootstrapToken.isBlank()) {
            log.info("未提供 TEAHOME_BOOTSTRAP_TOKEN，跳过引导令牌写入");
            return;
        }
        String token = bootstrapToken.trim();
        if (token.length() < 32) {
            throw new IllegalStateException("TEAHOME_BOOTSTRAP_TOKEN 至少需要 32 个字符");
        }
        String hash = TokenHasher.sha256Hex(token);
        String prefix = hash.substring(0, 8);
        try {
            int inserted = jdbc.sql("""
                            INSERT INTO teaplayer_api_token
                                (label, token_hash, token_prefix, scopes, status, created_by)
                            VALUES ('bootstrap', :hash, :prefix, ARRAY['read']::text[], 'active', 'system')
                            ON CONFLICT (token_hash) DO NOTHING
                            """)
                    .param("hash", hash)
                    .param("prefix", prefix)
                    .update();
            if (inserted > 0) {
                log.info("引导令牌已注册 prefix={}", prefix);
            } else {
                log.info("引导令牌已存在 prefix={}", prefix);
            }
        } catch (DataAccessException e) {
            log.error("写入引导令牌失败（schema 是否已迁移？）", e);
            throw e;
        }
    }
}
