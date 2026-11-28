package com.teaplayer.teahome.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 代码期望的 schema 版本必须等于最后一个 Flyway 迁移写进 {@code teaplayer_service_meta} 的值。
 *
 * <p>漏加这一处会很隐蔽：迁移跑完库已经是新版本，而 `HealthController` 拿
 * `CURRENT_SCHEMA_VERSION` 去比，对不上就 503 → 容器 unhealthy → `deploy.sh` 回滚，
 * 但应用自身日志显示启动成功、接口也能用（P7 部署时真踩到过）。
 *
 * <p>注意脚本序号与 schema 版本不是一回事（`V13__*` 写的是 11），所以这里从脚本
 * 正文里取它写入的值，而不是用文件名里的数字。
 */
class SchemaVersionGuardTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern WRITTEN_VERSION =
            Pattern.compile("meta_value\\s*=\\s*'(\\d+)'", Pattern.CASE_INSENSITIVE);

    @Test
    void currentSchemaVersionMatchesLatestMigration() throws IOException {
        final List<Path> scripts;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            scripts = files.filter(p -> p.getFileName().toString().startsWith("V"))
                    .sorted(Comparator.comparingInt(p -> scriptOrdinal(p.getFileName().toString())))
                    .toList();
        }
        assertThat(scripts).as("迁移脚本应存在").isNotEmpty();

        final Path latest = scripts.getLast();
        final String sql = Files.readString(latest);
        final Matcher m = WRITTEN_VERSION.matcher(sql);
        assertThat(m.find())
                .as("最新迁移 %s 应写入 schema_version", latest.getFileName())
                .isTrue();

        assertThat(SchemaVersionProvider.CURRENT_SCHEMA_VERSION)
                .as("最新迁移是 %s，它写下的 schema_version 必须与代码期望一致", latest.getFileName())
                .isEqualTo(m.group(1));
    }

    private static int scriptOrdinal(String fileName) {
        final int end = fileName.indexOf("__");
        assertThat(end).as("迁移文件名应是 V<n>__<desc>.sql：%s", fileName).isGreaterThan(1);
        return Integer.parseInt(fileName.substring(1, end));
    }
}
