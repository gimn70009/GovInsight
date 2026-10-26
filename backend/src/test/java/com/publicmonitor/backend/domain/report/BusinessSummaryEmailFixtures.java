package com.publicmonitor.backend.domain.report;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse;
import tools.jackson.databind.json.JsonMapper;

public final class BusinessSummaryEmailFixtures {
    private BusinessSummaryEmailFixtures() {}
    public static DocumentDetectionDetailResponse jumpUp() {
        return parse("""
                {"detectionId":1,"organizationName":"중소벤처기업부","title":"2027년 도약(Jump-Up) 프로그램 참여기업 모집 공고",
                 "changeType":"NEW_DOCUMENT","originalUrl":"https://example.org/notice?a=1&b=2",
                 "analysis":{"summary":"이전의 긴 보고서용 요약","opportunity":{"totalScore":72},
                   "businessSummary":{"purpose":"성장역량이 높은 중소기업의 단계별 맞춤전략 수립과 이행을 패키지로 지원하여 중견기업으로 도약시키는 것입니다.",
                    "supportScale":"총 선정규모 220개사 이내. 점프업100 100개사·점프업500 100개사·점프업1000 20개사, 오픈바우처는 점프업100 연 2억원(3년), 점프업500 연 2.8억원(3년), 점프업1000 연 7.5억원(2년) 등으로 제시되어 있습니다."},
                   "applicationDeadline":"2026-11-13 17:00 (온라인 통합정보관리시스템 제출 완료 기준)."},
                 "businessType":{"status":"CLASSIFIED","code":"ADVISORY"},
                 "responsibleOrganizations":[
                   {"name":"중소벤처기업진흥공단","role":"문의처","sourceType":"NOTICE_BODY","excerpt":"문의처: 중소벤처기업진흥공단"},
                   {"name":"이노비즈협회","role":"문의처","sourceType":"ATTACHMENT","attachmentName":"공고문.pdf","excerpt":"문의처: 이노비즈협회"}],
                 "attachments":[{"fileName":"공고문.pdf","downloadUrl":"https://example.org/notice.pdf"}]}
                """);
    }

    public static DocumentDetectionDetailResponse parse(String json) {
        if (!json.contains("\"analysisPending\"")) json = "{\"analysisPending\":false," + json.strip().substring(1);
        return JsonMapper.builder().build().readValue(json, DocumentDetectionDetailResponse.class);
    }
}
