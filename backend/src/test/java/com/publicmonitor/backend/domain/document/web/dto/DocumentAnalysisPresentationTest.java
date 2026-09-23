package com.publicmonitor.backend.domain.document.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DocumentAnalysisPresentationTest {
    @Test
    void expandsTheDateRangeInTheReportedAnalysisSummary() {
        String saved = """
                {"summary": "권역별 설명회(’26.10.13~10.14)에서 작성 방법을 안내합니다."}
                """;
        var analysis = new ObjectMapper().readValue(saved, DocumentDetectionDetailResponse.Analysis.class);
        assertThat(analysis.summary())
                .isEqualTo("권역별 설명회(2026년 10월 13일~10월 14일)에서 작성 방법을 안내합니다.");
    }

    @Test
    void formatsSavedAnalysisForDisplayAndKeepsQuotedEvidenceAndAttachmentNames() {
        String saved = """
                {
                  "summary": "특구 지정신청(’27.3월) 및 심의(’27.4월)",
                  "keyPoints": ["3개 회계연도 말(’23~’25) 결산 재무제표상"],
                  "reason": "’27년 사업",
                  "applicationDeadline": "’26.11.6 16:00",
                  "proposal": {
                    "draftReason": "’27년 사업 신청",
                    "sections": [{"title": "대응", "body": "’27.3월 제출"}],
                    "draftSections": [{"title": "계획", "body": "’27년 추진"}],
                    "sourceAttachmentNames": ["’27년 사업.pdf"],
                    "preparation": {
                      "meetingAgenda": ["’27.3월 제출 준비"],
                      "applicationDeadline": "’26.11.6",
                      "eligibilityChecklist": [{
                        "title": "재무제표",
                        "detail": "’23~’25년 기준",
                        "nextAction": "’26년 자료 확인",
                        "scoreBasis": ["’27.3월까지 준비"],
                        "source": {
                          "attachmentName": "’27년 사업.pdf",
                          "excerpt": "3개 회계연도 말(’23~’25) 결산 재무제표상"
                        }
                      }]
                    }
                  },
                  "opportunity": {"dimensions": [{"reason": "’27년 수행 가능"}]}
                }
                """;
        var analysis = new ObjectMapper().readValue(saved, DocumentDetectionDetailResponse.Analysis.class);

        assertThat(analysis.summary()).isEqualTo("특구 지정신청(2027년 3월) 및 심의(2027년 4월)");
        assertThat(analysis.keyPoints()).containsExactly("3개 회계연도 말(2023~2025) 결산 재무제표상");
        assertThat(analysis.reason()).isEqualTo("2027년 사업");
        assertThat(analysis.applicationDeadline()).isEqualTo("2026년 11월 6일 16:00");
        assertThat(analysis.proposal().draftReason()).isEqualTo("2027년 사업 신청");
        assertThat(analysis.proposal().sections().getFirst().body()).isEqualTo("2027년 3월 제출");
        assertThat(analysis.proposal().draftSections().getFirst().body()).isEqualTo("2027년 추진");
        assertThat(analysis.proposal().sourceAttachmentNames()).containsExactly("’27년 사업.pdf");
        var preparation = analysis.proposal().preparation();
        assertThat(preparation.meetingAgenda()).containsExactly("2027년 3월 제출 준비");
        assertThat(preparation.applicationDeadline()).isEqualTo("2026년 11월 6일");
        var item = preparation.eligibilityChecklist().getFirst();
        assertThat(item.detail()).isEqualTo("2023~2025년 기준");
        assertThat(item.nextAction()).isEqualTo("2026년 자료 확인");
        assertThat(item.scoreBasis()).containsExactly("2027년 3월까지 준비");
        assertThat(item.source().excerpt()).isEqualTo("3개 회계연도 말(’23~’25) 결산 재무제표상");
        assertThat(item.source().attachmentName()).isEqualTo("’27년 사업.pdf");
        assertThat(analysis.opportunity().dimensions().getFirst().reason()).isEqualTo("2027년 수행 가능");
        assertThat(saved).contains("특구 지정신청(’27.3월)");
    }
}
