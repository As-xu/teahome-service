package com.teaplayer.teahome.api.tv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.teaplayer.teahome.api.ApiException;

/** 只测入参规整：落库行为靠集成环境，单测不下 DB。 */
class TvTopicServiceTest {

    private static String codeOf(Throwable e) {
        return ((ApiException) e).code();
    }

    @Test
    void collapsesEveryKindOfBlank() {
        assertThat(TvTopicService.normalize("  繁  花 ")).isEqualTo("繁 花");
        // 全角空格与不换行空格在中文输入里很常见，必须一起折叠。
        assertThat(TvTopicService.normalize("庆余年\u3000\u3000")).isEqualTo("庆余年");
        assertThat(TvTopicService.normalize("\u00a0三体\u00a0")).isEqualTo("三体");
        assertThat(TvTopicService.normalize("a\nb\tc")).isEqualTo("a b c");
    }

    @Test
    void rejectsNullAndBlank() {
        assertThatThrownBy(() -> TvTopicService.normalize(null))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("bad_topic"));
        assertThatThrownBy(() -> TvTopicService.normalize(" \u3000\t\n "))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("bad_topic"));
    }

    @Test
    void lengthLimitIsInclusive() {
        String forty = "长".repeat(TvTopicService.MAX_TOPIC_CHARS);
        assertThat(TvTopicService.normalize(forty)).hasSize(40);
        assertThatThrownBy(() -> TvTopicService.normalize(forty + "长"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("bad_topic"));
    }

    @Test
    void blankCollapsingHappensBeforeTheLengthCheck() {
        // 41 个字符被折叠掉 20 个空格后是合法的，不能因为原始长度报错。
        String padded = ("片".repeat(31) + " ".repeat(20)).strip();
        assertThat(TvTopicService.normalize(padded)).hasSize(31);
    }
}
