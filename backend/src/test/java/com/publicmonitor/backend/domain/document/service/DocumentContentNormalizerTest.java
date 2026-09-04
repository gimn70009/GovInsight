package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DocumentContentNormalizerTest {

    private final DocumentContentNormalizer normalizer = new DocumentContentNormalizer();

    @Test
    void 공백과_개행을_하나의_공백으로_정규화한다() {
        assertThat(normalizer.normalize("  지원사업\n\n  신청   안내  "))
                .isEqualTo("지원사업 신청 안내");
    }

    @Test
    void null은_빈_문자열로_정규화한다() {
        assertThat(normalizer.normalize(null)).isEmpty();
    }
}
