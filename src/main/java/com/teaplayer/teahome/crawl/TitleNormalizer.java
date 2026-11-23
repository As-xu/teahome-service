package com.teaplayer.teahome.crawl;

import java.util.Locale;
import java.util.regex.Pattern;

/** 标题规范化与 merge_key，规则与客户端 aggregated_item.dart 的 normalizeTitle 对齐。 */
public final class TitleNormalizer {

    private static final int U = Pattern.UNICODE_CHARACTER_CLASS;
    private static final Pattern BRACKETS = Pattern.compile("\\[[^\\]]*\\]|\\([^)]*\\)|（[^）]*）");
    private static final Pattern SEASON = Pattern.compile("第\\s*[0-9一二三四五六七八九十百]+\\s*[季集期]", U);
    private static final Pattern SXXEXX = Pattern.compile("\\bs\\d{1,2}e\\d{1,3}\\b", U);
    private static final Pattern NOISE = Pattern.compile("[\\s\\p{P}\\p{S}]", U);

    private TitleNormalizer() {
    }

    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.toLowerCase(Locale.ROOT);
        s = BRACKETS.matcher(s).replaceAll(" ");
        s = SEASON.matcher(s).replaceAll(" ");
        s = SXXEXX.matcher(s).replaceAll(" ");
        s = NOISE.matcher(s).replaceAll("");
        return s;
    }

    public static String mergeKey(String title, String year) {
        String normalized = normalize(title);
        String y = year == null ? "" : year.trim();
        return y.isEmpty() ? normalized : normalized + "|" + y;
    }

    /** 分类器用：只去空白并把「紀錄」统一成「纪录」。 */
    public static String forCategory(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\s+", "").replace("紀錄", "纪录");
    }
}
