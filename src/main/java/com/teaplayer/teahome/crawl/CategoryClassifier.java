package com.teaplayer.teahome.crawl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** 分类器：按 teaplayer_category_rule 的 active 版本打分；默认规则与 V3 种子一致。 */
@Component
public class CategoryClassifier {

    private static final Logger log = LoggerFactory.getLogger(CategoryClassifier.class);
    private static final List<String> ORDER =
            List.of("movie", "tv", "anime", "variety", "documentary", "short");

    public record Result(List<String> codes, String primary, String source) {
    }

    record Rule(int ordinal, String target, String matchKind, String pattern,
                List<String> excludes, Pattern compiled) {

        static Rule of(int ordinal, String target, String kind, String pattern, List<String> excludes) {
            Pattern compiled = null;
            if ("regex".equals(kind)) {
                try {
                    compiled = Pattern.compile(pattern, Pattern.UNICODE_CHARACTER_CLASS);
                } catch (PatternSyntaxException e) {
                    compiled = null;
                }
            }
            return new Rule(ordinal, target, kind, pattern, excludes, compiled);
        }

        boolean matches(String name) {
            for (String exclude : excludes) {
                if (name.contains(exclude)) {
                    return false;
                }
            }
            return switch (matchKind) {
                case "contains" -> name.contains(pattern);
                case "prefix" -> name.startsWith(pattern);
                case "suffix" -> name.endsWith(pattern);
                case "equals" -> name.equals(pattern);
                case "regex" -> compiled != null && compiled.matcher(name).find();
                default -> false;
            };
        }
    }

    static final List<Rule> DEFAULT_RULES = List.of(
            Rule.of(10, "documentary", "regex", "纪录|记录", List.of()),
            Rule.of(20, "anime", "regex", "动漫|动画", List.of()),
            Rule.of(30, "variety", "contains", "综艺", List.of()),
            Rule.of(40, "short", "contains", "短剧", List.of()),
            Rule.of(50, "tv", "regex", "连续剧|剧$", List.of("短剧")),
            Rule.of(60, "movie", "regex", "^(电影|电影片)$|片$",
                    List.of("纪录", "记录", "动漫", "动画", "短剧", "预告", "资讯", "解说")));

    private volatile List<Rule> rules = DEFAULT_RULES;
    private volatile String version = "cat-v1";

    public Result classify(String rawCategory) {
        String name = TitleNormalizer.forCategory(rawCategory);
        if (name.isEmpty()) {
            return new Result(List.of("other"), "other", "unmapped");
        }
        Set<String> hits = new LinkedHashSet<>();
        for (Rule rule : rules) {
            if (rule.matches(name)) {
                hits.add(rule.target());
            }
        }
        if (hits.isEmpty()) {
            return new Result(List.of("other"), "other", "unmapped");
        }
        List<String> codes = new ArrayList<>(hits);
        codes.sort(Comparator.comparingInt(ORDER::indexOf));
        return new Result(List.copyOf(codes), codes.getFirst(), "rule");
    }

    public String version() {
        return version;
    }

    /** 运行时从库里刷新 active 规则；失败保留默认规则，不阻塞抓取。 */
    public void reloadFromDb(JdbcClient jdbc) {
        try {
            var release = jdbc.sql("""
                            SELECT version FROM teaplayer_classifier_release
                             WHERE status = 'active'
                             LIMIT 1
                            """)
                    .query(String.class)
                    .optional();
            if (release.isEmpty()) {
                return;
            }
            String v = release.get();
            List<Rule> loaded = jdbc.sql("""
                            SELECT ordinal, target_category, match_kind, pattern,
                                   exclude_patterns, applies_to
                              FROM teaplayer_category_rule
                             WHERE classifier_version = :version
                             ORDER BY ordinal
                            """)
                    .param("version", v)
                    .query((rs, rowNum) -> {
                        List<String> excludes = new ArrayList<>();
                        java.sql.Array array = rs.getArray("exclude_patterns");
                        if (array != null) {
                            Object value = array.getArray();
                            if (value instanceof String[] strings) {
                                excludes = List.of(strings);
                            }
                        }
                        return Rule.of(
                                rs.getInt("ordinal"),
                                rs.getString("target_category"),
                                rs.getString("match_kind"),
                                rs.getString("pattern"),
                                excludes);
                    })
                    .list();
            if (!loaded.isEmpty()) {
                this.rules = List.copyOf(loaded);
                this.version = v;
                log.info("分类规则已加载 version={} rules={}", v, loaded.size());
            }
        } catch (RuntimeException e) {
            log.warn("分类规则加载失败，继续使用内置规则: {}", e.getMessage());
        }
    }
}
