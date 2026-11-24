package com.teaplayer.teahome.admin;

import java.io.Console;
import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Creates or resets the owner account; only active when the console profile is on. */
@Component
@Profile("admin")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final JdbcClient jdbc;
    private final AdminPasswordService passwords;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    public AdminBootstrapRunner(JdbcClient jdbc, AdminPasswordService passwords,
                                Environment environment, ConfigurableApplicationContext context) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        long users = jdbc.sql("SELECT count(*) FROM teaplayer_admin_user").query(Long.class).single();
        if (!args.containsOption("reset-admin-password")) {
            if (users == 0) {
                log.warn("管理端尚无账号，请运行 deploy.sh --reset-admin-password 初始化");
            }
            return;
        }
        String username = environment.getProperty("TEAHOME_ADMIN_USER", "admin");
        String raw = environment.getProperty("TEAHOME_ADMIN_PASSWORD");
        if (raw == null || raw.isBlank()) {
            Console console = System.console();
            if (console == null) {
                log.error("TEAHOME_ADMIN_PASSWORD 未设置且无交互终端，无法重置密码");
                System.exit(SpringApplication.exit(context, () -> 2));
                return;
            }
            char[] first = console.readPassword("新密码（至少 12 位）：");
            char[] second = console.readPassword("再次输入：");
            if (first == null || second == null || !java.util.Arrays.equals(first, second)) {
                log.error("两次密码不一致");
                System.exit(SpringApplication.exit(context, () -> 2));
                return;
            }
            raw = new String(first);
        }
        if (raw.length() < 12) {
            log.error("密码至少 12 位");
            System.exit(SpringApplication.exit(context, () -> 2));
            return;
        }
        String hash = passwords.hash(raw);
        jdbc.sql("""
                INSERT INTO teaplayer_admin_user
                    (username, display_name, password_hash, role, status, must_change_password,
                     password_changed_at, created_by)
                VALUES (:username, 'Owner', :hash, 'owner', 'active', false, now(), 'system')
                ON CONFLICT (username) DO UPDATE
                   SET password_hash = EXCLUDED.password_hash,
                       role = 'owner',
                       status = 'active',
                       must_change_password = false,
                       password_changed_at = now(),
                       updated_at = now(),
                       revision = teaplayer_admin_user.revision + 1
                """)
                .param("username", username)
                .param("hash", hash)
                .update();
        jdbc.sql("""
                INSERT INTO teaplayer_audit_log
                    (actor_type, actor_id, action, target_type, target_id, result, reason)
                VALUES ('system', 'bootstrap', 'admin.password.reset', 'admin_user', :username,
                        'ok', 'command line --reset-admin-password')
                """)
                .param("username", username)
                .update();
        log.info("管理端账号 {} 已就绪（密码只在本次输入中使用，未落日志）", username);
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
