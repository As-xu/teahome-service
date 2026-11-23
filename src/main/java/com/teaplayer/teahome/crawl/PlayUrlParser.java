package com.teaplayer.teahome.crawl;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** MacCMS vod_play_from / vod_play_url 拆解，规则与客户端 Dart/Kotlin 版一致。 */
public final class PlayUrlParser {

    public record ParsedEpisode(String name, String url) {
    }

    public record ParsedLine(String from, List<ParsedEpisode> episodes) {
    }

    private PlayUrlParser() {
    }

    public static List<ParsedLine> parse(
            String from, String url, String groupSep, String episodeSep, String nameSep) {
        List<String> froms = splitGroups(from, groupSep);
        List<String> urls = splitGroups(url, groupSep);
        int n = Math.min(froms.size(), urls.size());
        List<ParsedLine> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String name = froms.get(i).trim();
            List<ParsedEpisode> episodes = parseEpisodes(urls.get(i), episodeSep, nameSep);
            if (episodes.isEmpty()) {
                continue;
            }
            out.add(new ParsedLine(name.isEmpty() ? "线路 " + (i + 1) : name, episodes));
        }
        return out;
    }

    private static List<String> splitGroups(String raw, String sep) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(Pattern.quote(sep), -1));
    }

    private static List<ParsedEpisode> parseEpisodes(String block, String sep, String nameSep) {
        if (block == null || block.isBlank()) {
            return List.of();
        }
        String[] items = block.split(Pattern.quote(sep), -1);
        List<ParsedEpisode> out = new ArrayList<>();
        for (int idx = 0; idx < items.length; idx++) {
            String s = items[idx].trim();
            if (s.isEmpty()) {
                continue;
            }
            int at = s.indexOf(nameSep);
            String name = at < 0 ? "" : s.substring(0, at).trim();
            String link = at < 0 ? s : s.substring(at + nameSep.length()).trim();
            if (link.isEmpty()) {
                continue;
            }
            out.add(new ParsedEpisode(name.isEmpty() ? "第 " + (idx + 1) + " 集" : name, link));
        }
        return out;
    }

    public static String protocol(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".m3u8")) {
            return "hls";
        }
        if (lower.contains("/play/")) {
            return "page";
        }
        if (lower.endsWith(".mp4")) {
            return "direct";
        }
        return "unknown";
    }
}
