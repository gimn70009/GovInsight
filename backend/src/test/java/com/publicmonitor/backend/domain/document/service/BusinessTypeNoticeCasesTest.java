package com.publicmonitor.backend.domain.document.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class BusinessTypeNoticeCasesTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"notice-cases.json", "climate-cases.json", "molit-cases.json", "msit-cases.json", "moel-cases.json"})
    void 실제_공고의_성격과_지원_활동을_구별하고_정상_모집을_보존한다(String fileName) throws Exception {
        var cases = mapper.readTree(Files.readString(Path.of("src/test/resources/business-type", fileName)));
        for (var item : cases) {
            var files = item.has("attachmentText") ? List.of(DocumentAttachment.create(null,
                    item.path("attachmentName").asString("공고문.hwpx"), "https://example.com/notice", "hwpx", null, null, null,
                    item.path("attachmentText").asString(), AttachmentParseStatus.COMPLETED, null)) : List.<DocumentAttachment>of();
            var quote = new java.util.LinkedHashMap<String, Object>();
            quote.put("origin", item.path("quoteOrigin").asString("NOTICE_BODY"));
            quote.put("excerpt", item.path("quote").asString());
            if ("ATTACHMENT".equals(quote.get("origin"))) {
                quote.put("attachmentName", item.path("quoteAttachmentName").asString(
                        item.path("attachmentName").asString("공고문.hwpx")));
            }
            String saved = item.path("candidateType").isNull() ? null : mapper.writeValueAsString(Map.of("businessType", Map.of(
                    "primaryType", item.path("candidateType").asString(),
                    "explanation", item.path("explanation").asString("현재 지원 활동을 확인했습니다."), "details", List.of(),
                    "sources", List.of(quote))));
            var result = BusinessTypeResolver.diagnose(item.path("title").asString(), item.path("body").asString(),
                    files, saved, item.path("documentType").asString(null), mapper).summary();
            assertThat(result.status()).as(item.path("id").asString()).isEqualTo(item.path("expectedStatus").asString());
            assertThat(result.code()).as(item.path("id").asString()).isEqualTo(item.path("expectedCode").asString(null));
            assertThat(result.noticeNature() == null ? null : result.noticeNature().code()).as(item.path("id").asString())
                    .isEqualTo(item.path("expectedNature").asString(null));
            if (item.has("expectedLabel")) {
                assertThat(result.noticeNature().label()).isEqualTo(item.path("expectedLabel").asString());
            }
        }
    }
}
