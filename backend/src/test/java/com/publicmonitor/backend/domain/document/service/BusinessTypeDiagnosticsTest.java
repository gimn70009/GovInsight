package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class BusinessTypeDiagnosticsTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private static final String QUOTE = "기업의 생산 시스템 구축을 지원합니다.";

    @ParameterizedTest
    @ValueSource(strings = {"붙임 1 공고문", "[붙임 2] 참여기업 모집공고", "별첨 제3호 사업 안내문"})
    void 번호가_붙은_실제_공고를_보존하고_뒤의_서식은_제외한다(String heading) {
        String body = heading + "\n□ 지원내용: " + QUOTE + "\n별첨 2 신청서\n□ 지원내용: 연구개발 지원";
        var result = diagnose(body, List.of(), saved("NOTICE_BODY", null, QUOTE));
        assertThat(result.summary().code()).isEqualTo("ADOPTION");
        assertThat(result.decisionReason()).isEqualTo("SAVED_CLASSIFICATION");
        assertThat(result.storedReason()).isEqualTo("VALIDATED");
        assertThat(result.summary().sources().getFirst().excerpt()).isEqualTo(QUOTE);
        var fallback = diagnose(body, List.of(), null);
        assertThat(fallback.summary().code()).isEqualTo("ADOPTION");
        assertThat(fallback.summary().details()).isEmpty();
        var rejected = diagnose(body, List.of(), saved("NOTICE_BODY", null, "연구개발 지원"));
        assertThat(rejected.storedReason()).isEqualTo("EXCLUDED_SOURCE_SECTION");
        assertThat(rejected.decisionReason()).isEqualTo("RULE_CLASSIFICATION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"붙임 1 공고문 작성 예시", "서식 1 공고문", "별첨 1 신청서 (모집공고 참고)",
            "붙임 1", "별지 2 사업계획서", "작성 예시 모집공고"})
    void 공고라는_단어가_있어도_작성_서식은_지원_근거가_아니다(String heading) {
        var result = diagnose(heading + "\n□ 지원내용: " + QUOTE, List.of(), saved("NOTICE_BODY", null, QUOTE));
        assertThat(result.summary().status()).isEqualTo("REVIEW_REQUIRED");
        assertThat(result.storedReason()).isEqualTo("EXCLUDED_SOURCE_SECTION");
        assertThat(result.decisionReason()).isEqualTo("NO_USABLE_SOURCE");
    }

    @Test
    void 첨부_파싱_실패와_정상_파싱된_빈_텍스트를_구분한다() {
        var failed = diagnose(null, List.of(file("공고.pdf", null, AttachmentParseStatus.FAILED)), saved("ATTACHMENT", "공고.pdf", QUOTE));
        assertThat(failed.storedReason()).isEqualTo("SOURCE_NOT_READY");
        assertThat(failed.failedAttachmentCount()).isEqualTo(1);
        assertThat(failed.emptyAttachmentCount()).isEqualTo(1);
        assertThat(failed.decisionReason()).isEqualTo("NO_USABLE_SOURCE");
        var empty = diagnose(null, List.of(file("공고.pdf", "", AttachmentParseStatus.COMPLETED)), saved("ATTACHMENT", "공고.pdf", QUOTE));
        assertThat(empty.storedReason()).isEqualTo("SOURCE_TEXT_UNAVAILABLE");
        assertThat(empty.failedAttachmentCount()).isZero();
    }

    @Test
    void 출처_누락_중복_제외와_인용_불일치를_구분한다() {
        var good = file("공고.pdf", QUOTE, AttachmentParseStatus.COMPLETED);
        assertThat(diagnose(null, List.of(good), saved("ATTACHMENT", "다른공고.pdf", QUOTE)).storedReason()).isEqualTo("SOURCE_NOT_FOUND");
        assertThat(diagnose(null, List.of(good, good), saved("ATTACHMENT", "공고.pdf", QUOTE)).storedReason()).isEqualTo("AMBIGUOUS_SOURCE");
        var form = file("신청서.pdf", QUOTE, AttachmentParseStatus.COMPLETED);
        assertThat(diagnose(null, List.of(form), saved("ATTACHMENT", "신청서.pdf", QUOTE)).storedReason()).isEqualTo("EXCLUDED_SOURCE");
        var mismatch = diagnose("다른 안내 내용입니다.", List.of(), saved("NOTICE_BODY", null, QUOTE));
        assertThat(mismatch.storedReason()).isEqualTo("QUOTE_MISMATCH");
        assertThat(mismatch.rejectedSourceIndex()).isZero();
        assertThat(mismatch.summary().status()).isEqualTo("REVIEW_REQUIRED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"향후 설비 도입으로 연계 가능합니다.", "연구개발 지원 불가 안내입니다."})
    void 미래_연계와_지원불가_문장은_여전히_제외한다(String quote) {
        assertThat(diagnose(quote, List.of(), saved("NOTICE_BODY", null, quote)).storedReason()).isEqualTo("UNSUPPORTED_EVIDENCE");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "{}|NO_SAVED_CLASSIFICATION", "{\"businessType\":null}|NO_SAVED_CLASSIFICATION",
            "{broken|INVALID_SAVED_PAYLOAD", "null|INVALID_SAVED_PAYLOAD",
            "{\"businessType\":42}|INVALID_SAVED_PAYLOAD"
    })
    void 저장값의_부재와_손상을_구분한다(String saved, String reason) {
        assertThat(diagnose("원문 안내입니다.", List.of(), saved).storedReason()).isEqualTo(reason);
        assertThat(diagnose("원문 안내입니다.", List.of(), null).storedReason()).isEqualTo("NO_SAVED_ANALYSIS");
    }

    private BusinessTypeResolver.Resolution diagnose(String body, List<DocumentAttachment> files, String saved) {
        return BusinessTypeResolver.diagnose("사업 안내", body, files, saved, null, mapper);
    }

    private String saved(String origin, String name, String quote) {
        var source = new LinkedHashMap<String, Object>();
        source.put("origin", origin);
        source.put("attachmentName", name);
        source.put("excerpt", quote);
        return mapper.writeValueAsString(Map.of("businessType", Map.of("primaryType", "ADOPTION",
                "explanation", "생산 시스템 도입을 지원합니다.", "details", List.of(), "sources", List.of(source))));
    }

    private DocumentAttachment file(String name, String text, AttachmentParseStatus status) {
        return DocumentAttachment.create(null, name, "https://example.com/file", "pdf", "application/pdf",
                100L, "a".repeat(64), text, status, null);
    }
}
