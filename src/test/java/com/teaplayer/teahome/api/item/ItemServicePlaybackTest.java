package com.teaplayer.teahome.api.item;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** 播放元数据（`lines[].headers/hosts`）的推导规则。 */
class ItemServicePlaybackTest {

    private static ItemDtos.Episode ep(int i, String url) {
        return new ItemDtos.Episode(i, "第" + i + "集", url);
    }

    @Test
    void headersUseEpisodeOriginAsReferer() {
        var headers = ItemService.playbackHeaders(List.of(
                ep(1, "https://hn.bfvvs.com/play/e31jNr9b/index.m3u8")));
        assertThat(headers).containsEntry("referer", "https://hn.bfvvs.com/");
        assertThat(headers.get("user_agent")).contains("Mozilla/5.0");
    }

    @Test
    void headersKeepPortWhenPresent() {
        var headers = ItemService.playbackHeaders(List.of(ep(1, "http://cdn.example.com:8080/a.m3u8")));
        assertThat(headers).containsEntry("referer", "http://cdn.example.com:8080/");
    }

    @Test
    void emptyEpisodeListYieldsNoHeaders() {
        assertThat(ItemService.playbackHeaders(List.of())).isEmpty();
    }

    @Test
    void hostsAreDeduplicatedInOrder() {
        var hosts = ItemService.playbackHosts(List.of(
                ep(1, "https://a.example.com/1.m3u8"),
                ep(2, "https://A.EXAMPLE.COM/2.m3u8"),
                ep(3, "https://b.example.com/3.m3u8")));
        assertThat(hosts).containsExactly("a.example.com", "b.example.com");
    }

    @Test
    void malformedUrlsAreSkipped() {
        var hosts = ItemService.playbackHosts(List.of(ep(1, "not a url"), ep(2, "https://ok.example.com/1.m3u8")));
        assertThat(hosts).containsExactly("ok.example.com");
    }
}
