package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.CollectedAttachment;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentVersionHasherTest {

    private final DocumentVersionHasher hasher = new DocumentVersionHasher();

    @Test
    void 첨부파일_순서가_달라도_같은_해시를_생성한다() {
        CollectedAttachment first = new CollectedAttachment("공고.pdf", "https://example.com/1");
        CollectedAttachment second = new CollectedAttachment("서식.hwp", "https://example.com/2");

        String left = hasher.hash("제목", "본문", List.of(first, second));
        String right = hasher.hash("제목", "본문", List.of(second, first));

        assertThat(left).hasSize(64).isEqualTo(right);
    }

    @Test
    void 본문이_달라지면_다른_해시를_생성한다() {
        assertThat(hasher.hash("제목", "이전 본문", List.of()))
                .isNotEqualTo(hasher.hash("제목", "변경 본문", List.of()));
    }
}
