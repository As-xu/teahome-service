package com.teaplayer.teahome.crawl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CategoryClassifierTest {

    private final CategoryClassifier classifier = new CategoryClassifier();

    @Test
    void classifiesSixCategories() {
        assertThat(classifier.classify("动作片").primary()).isEqualTo("movie");
        assertThat(classifier.classify("国产剧").primary()).isEqualTo("tv");
        assertThat(classifier.classify("日韩动漫").primary()).isEqualTo("anime");
        assertThat(classifier.classify("大陆综艺").primary()).isEqualTo("variety");
        assertThat(classifier.classify("记录片").primary()).isEqualTo("documentary");
        assertThat(classifier.classify("短剧").primary()).isEqualTo("short");
    }

    @Test
    void keepsMultipleCodesInDisplayOrder() {
        CategoryClassifier.Result result = classifier.classify("动漫剧");
        assertThat(result.codes()).containsExactly("tv", "anime");
        assertThat(result.primary()).isEqualTo("tv");
        assertThat(result.source()).isEqualTo("rule");
    }

    @Test
    void excludesDocumentaryFromMovie() {
        assertThat(classifier.classify("纪录片").codes()).containsExactly("documentary");
        assertThat(classifier.classify("电影解说").primary()).isEqualTo("other");
        assertThat(classifier.classify("电影解说").source()).isEqualTo("unmapped");
    }

    @Test
    void handlesUnknownAndBlank() {
        assertThat(classifier.classify("").primary()).isEqualTo("other");
        assertThat(classifier.classify("未分类").primary()).isEqualTo("other");
        assertThat(classifier.classify("连续剧").primary()).isEqualTo("tv");
    }
}
