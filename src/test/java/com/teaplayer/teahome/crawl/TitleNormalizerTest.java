package com.teaplayer.teahome.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TitleNormalizerTest {

    @Test
    void stripsBracketsSeasonAndPunctuation() {
        assertThat(TitleNormalizer.normalize("《庆余年》 第二季 (2024)")).isEqualTo("庆余年");
        assertThat(TitleNormalizer.normalize("The Last of Us S01E03")).isEqualTo("thelastofus");
        assertThat(TitleNormalizer.normalize("  疯狂动物城2  ")).isEqualTo("疯狂动物城2");
    }

    @Test
    void buildsMergeKeyWithYear() {
        assertThat(TitleNormalizer.mergeKey("庆余年 第二季", "2019")).isEqualTo("庆余年|2019");
        assertThat(TitleNormalizer.mergeKey("庆余年", "")).isEqualTo("庆余年");
    }

    @Test
    void categoryNormalizationKeepsWordsTogether() {
        assertThat(TitleNormalizer.forCategory(" 纪录 片 ")).isEqualTo("纪录片");
        assertThat(TitleNormalizer.forCategory("紀錄片")).isEqualTo("纪录片");
    }
}
