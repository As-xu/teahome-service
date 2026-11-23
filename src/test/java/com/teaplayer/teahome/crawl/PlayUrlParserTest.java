package com.teaplayer.teahome.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PlayUrlParserTest {

    @Test
    void parsesTwoLinesAndEpisodeNames() {
        List<PlayUrlParser.ParsedLine> lines = PlayUrlParser.parse(
                "hnm3u8$$$ldm3u8",
                "第1集$https://a/1.m3u8#第2集$https://a/2.m3u8$$$第1集$https://b/1.m3u8",
                "$$$", "#", "$");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).from()).isEqualTo("hnm3u8");
        assertThat(lines.get(0).episodes()).hasSize(2);
        assertThat(lines.get(0).episodes().get(1).name()).isEqualTo("第2集");
        assertThat(lines.get(0).episodes().get(1).url()).isEqualTo("https://a/2.m3u8");
        assertThat(lines.get(1).episodes()).hasSize(1);
    }

    @Test
    void fallsBackToGeneratedNames() {
        List<PlayUrlParser.ParsedLine> lines = PlayUrlParser.parse(
                "线路A", "https://x/1.m3u8#https://x/2.m3u8", "$$$", "#", "$");
        assertThat(lines.getFirst().episodes().get(0).name()).isEqualTo("第 1 集");
        assertThat(lines.getFirst().episodes().get(1).name()).isEqualTo("第 2 集");
    }

    @Test
    void skipsEmptyLinesAndEmptyEpisodes() {
        List<PlayUrlParser.ParsedLine> lines = PlayUrlParser.parse(
                "A$$$B", "https://a/1.m3u8$$$", "$$$", "#", "$");
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().from()).isEqualTo("A");
    }

    @Test
    void detectsProtocols() {
        assertThat(PlayUrlParser.protocol("https://a/index.m3u8")).isEqualTo("hls");
        assertThat(PlayUrlParser.protocol("https://a/play/xyz/index.m3u8")).isEqualTo("hls");
        assertThat(PlayUrlParser.protocol("https://a/play/xyz")).isEqualTo("page");
        assertThat(PlayUrlParser.protocol("https://a/movie.mp4")).isEqualTo("direct");
        assertThat(PlayUrlParser.protocol("https://a/unknown")).isEqualTo("unknown");
    }
}
