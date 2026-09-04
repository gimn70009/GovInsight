package com.publicmonitor.backend.domain.document.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

class DocumentDetectionRepositoryTest {

    @Test
    void 감지_문서_목록은_기회_점수가_높은_순서로_조회한다() throws NoSuchMethodException {
        Method method = DocumentDetectionRepository.class.getMethod(
                "findSummariesOrderByOpportunityScore",
                Long.class,
                LocalDateTime.class,
                LocalDateTime.class,
                Pageable.class
        );
        String query = method.getAnnotation(Query.class).value().replaceAll("\\s+", " ");

        assertThat(query).containsSubsequence(
                "case when analysis.opportunityScore is null then 1 else 0 end",
                "analysis.opportunityScore desc",
                "detection.detectedAt desc",
                "detection.id desc"
        );
    }
}
