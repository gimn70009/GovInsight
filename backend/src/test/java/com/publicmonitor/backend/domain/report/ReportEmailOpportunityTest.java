package com.publicmonitor.backend.domain.report;

import com.publicmonitor.backend.domain.email.EmailBrand;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ReportEmailOpportunityTest {
    private static final String BUSINESS = """
            ▸ 2026년도 국제 공동기술개발사업 참여기업 모집
            기관: 예시 기술진흥원
            문서 유형: 일반 공지 │ 신규 │ 기회점수: 69점
            공고 성격: 사업 기회
            사업 유형: R&D·기술개발
            사업 방식: 연구개발비 지원
            요약: 해외 기업과 공동으로 기술을 개발할 국내 컨소시엄을 모집합니다. 선정 과제의 연구개발비를 지원합니다.
            • 금액·규모: 1-3. 지원규모 및 지원기간
              ↳ o 과제당 연 최대 5억 원, 최대 3년. 기업 규모별 자부담 비율 적용.
              ↳ * 정부 예산 사정에 따라 지원금액이 변경될 수 있습니다.
              ↳ o 과제당 연 최대 5억 원, 최대 3년. 기업 규모별 자부담 비율 적용.
            • 참여 역할: 국내 주관연구개발기관
            • 제출 주체·대상: 국내 주관기관: 중소·중견기업 / 필수 참여기관: 해외 기업 1개 이상 포함. 단, 채무불이행 기업 제외.
            • 제출·의견 기한: 영문 연구개발계획서 접수 마감: 2027년 1월 20일(수) / 국문 연구개발계획서 접수 마감: 2027년 1월 21일(목) 16:00(KST)
            • 제출처·방법: 영문 접수: [국제 공동접수](https://example.org/international) / 국내 접수: [K-PASS 온라인 접수](https://example.org/apply?a=1&b=2)
            • 문의 담당: 국내 접수: 기술사업팀 02-0000-0000 / 영문 접수: apply@example.org
            제출 준비 서류 ↓
            • [연구개발계획서 양식](https://example.org/form)
            • 주관 및 공동연구개발기관 대표의 참여의사 확인서
            • 외부기술도입비 현물산정 신청서 (해당 시에만 제출)
            원문: [게시글 보기](https://example.org/notice)
            """;

    @Test void businessHeaderSeparatesLargeScoreAndReadableClassificationRows() {
        String html = ReportEmailTemplate.render("보고서", BUSINESS);
        assertThat(html).containsSubsequence("data-report-score", "font-size:16px", "기회점수: 69점</span>", "신규</span>",
                "data-report-classification", "data-report-meta=\"type\"", "font-size:14px", "유형:</td>", "font-size:16px", "R&amp;D·기술개발</td>",
                "data-report-meta=\"method\"", "방식:</td>", "연구개발비 지원</td>");
        assertThat(html).doesNotContain("일반 공지", "유형 ·", "방식 ·", "공고 성격:", "사업 핵심");
        assertThat(html).contains("background:#e7effb;border-top:4px solid #357bd8",
                "background:#215fa8;color:#ffffff;font-weight:700");
    }

    @Test void summaryComesBeforeDecisionFieldsAndPreservesAmountsConditionsAndRoutes() {
        String html = ReportEmailTemplate.render("보고서", BUSINESS);
        assertThat(html).containsSubsequence("data-report-section=\"summary\"", "data-report-section=\"amount\"", "data-report-section=\"participation\"",
                "data-report-section=\"submission\"",
                "data-report-section=\"documents\"", "data-report-section=\"contact\"", "data-report-original");
        assertThat(section(html, "amount")).contains("과제당 연 최대 5억 원", "최대 3년", "자부담")
                .doesNotContain("15억");
        assertThat(section(html, "participation")).contains("국내 주관연구개발기관", "중소·중견기업", "해외 기업 1개 이상", "채무불이행 기업 제외");
        assertThat(section(html, "submission")).contains("영문 연구개발계획서 접수 마감", "국문 연구개발계획서 접수 마감",
                "2027년 1월 20일", "2027년 1월 21일", "16:00(KST)",
                "href=\"https://example.org/international\"", "href=\"https://example.org/apply?a=1&amp;b=2\"");
        assertThat(html.substring(html.indexOf("data-report-original"))).contains("https://example.org/notice")
                .doesNotContain("https://example.org/apply", "접수 사이트");
        assertThat(section(html, "documents")).contains("href=\"https://example.org/form\"", "해당 시에만 제출");
        assertThat(section(html, "summary")).contains("핵심 요약", "해외 기업과 공동으로 기술을 개발할 국내 컨소시엄을 모집합니다.");
    }

    @Test void generalNoticeLeadsWithSummaryAndDoesNotShowEmptyBusinessOrSubmissionSections() {
        String html = ReportEmailTemplate.render("보고서", """
                ▸ 기관 청사 이전 안내
                기관: 예시 기관
                문서 유형: 일반 공지 │ 수정 │ 기회점수: 12점
                공고 성격: 일반 공지
                요약: 기관 청사가 이전하여 방문 주소가 변경됩니다.
                • 금액·규모: 원문 확인 필요
                • 제출 주체·대상: 원문 확인 필요
                • 제출처·방법: 명시되지 않았습니다.
                • 문의 담당: 총무팀 02-0000-0000
                첨부파일 ↓
                • 등록된 첨부파일 없음
                원문: [게시글 보기](https://example.org/move)
                """);
        assertThat(html).containsSubsequence("기회점수: 12점</span>", "일반 공지</span>", "수정</span>",
                "data-report-section=\"summary\"", "data-report-section=\"contact\"");
        assertThat(html).contains("유형:", "방식:", "해당 없음 (일반 공지)");
        assertThat(html).doesNotContain("data-report-section=\"amount\"", "data-report-section=\"participation\"",
                "data-report-section=\"submission\"", "data-report-section=\"attachments\"", "원문 확인 필요");
    }

    @Test void generalNoticeKeepsKnownDatesAndParticipationFactsWithoutInventingBusinessLabels() {
        String html = ReportEmailTemplate.render("보고서", """
                ▸ 정책 설명회 안내
                문서 유형: 일반 공지 │ 신규 │ 기회점수: 20점
                공고 성격: 일반 공지
                요약: 정책 변경사항을 설명하는 공개 설명회입니다.
                • 제출 주체·대상: 누구나 참석 가능. 사전 등록자 우선.
                • 관련 일정: 설명회: 2026년 11월 1일 14:00
                • 제출처·방법: [사전 등록](https://example.org/event)
                • 문의 담당: 홈페이지 이용 안내 게시판
                """);
        assertThat(html).containsSubsequence("data-report-section=\"summary\"", "data-report-section=\"participation\"",
                "data-report-section=\"submission\"");
        assertThat(html).contains("누구나 참석 가능", "사전 등록자 우선", "홈페이지 이용 안내 게시판", "2026년 11월 1일 14:00", "href=\"https://example.org/event\"")
                .contains("해당 없음 (일반 공지)")
                .doesNotContain("data-report-section=\"amount\"");
    }

    @Test void oldReportKeepsItsDocumentTypeAndDoesNotInferAClassificationFromItsTitle() {
        String html = ReportEmailTemplate.render("보고서", """
                ▸ R&D 바우처 지원사업
                문서 유형: 일반 공지 │ 신규 │ 기회점수: 69점
                요약: 연구개발을 지원합니다.
                • 제출 주체·대상: 중소기업
                """);
        assertThat(html).containsSubsequence("기회점수: 69점</span>", "일반 공지</span>", "신규</span>");
        assertThat(html).contains("분류 정보 없음")
                .doesNotContain("data-report-section=\"amount\"");
    }

    @Test void metadataIsEscapedAndBodyTextCannotMasqueradeAsHeaderMetadata() {
        String html = ReportEmailTemplate.render("보고서", """
                ▸ 안전한 표시
                문서 유형: 일반 공지 │ 신규 │ 기회점수: 69점
                공고 성격: 사업 기회
                사업 유형: <img src=x onerror=alert(1)>
                사업 방식: <script>alert(1)</script>
                요약: 원문 값은 텍스트로 표시합니다.
                사업 유형: 거짓 분류
                문서 유형: 일반 공지 │ 신규 │ 기회점수: 99점
                • 금액·규모: <script>금액</script>
                • 제출처·방법: [접수](javascript:alert(1))
                """);
        assertThat(html.replace(EmailBrand.html(), "")).contains("유형:</td>", "&lt;img src=x", "방식:</td>", "&lt;script&gt;alert(1)", "&lt;script&gt;금액", "border-top:4px solid #357bd8")
                .doesNotContain("<script>", "<img ", "href=\"javascript:", "유형: 거짓 분류</span>", "border-top:4px solid #dc2626");
    }

    @Test void producesARepresentativeBusinessProcurementAndNoticePreview() throws Exception {
        String procurement = """
                ▸ 2026년 공공 데이터 분석 플랫폼 구축 용역
                기관: 예시 정보원
                문서 유형: 입찰 공고 │ 수정 │ 기회점수: 82점
                공고 성격: 사업 기회
                사업 유형: 용역·조달
                사업 방식: 용역·구매계약
                요약: 공공 데이터 수집과 분석 플랫폼을 구축할 수행사를 모집합니다.
                • 금액·규모: 사업 전체 추정예산 2억 4천만 원 (부가세 포함)
                • 참여 역할: 용역 수행사
                • 제출 주체·대상: 소프트웨어사업자 등록 및 중소기업 확인서 보유. 공동수급 허용, 하도급 불허.
                • 제출·의견 기한: 제안서 접수 마감: 2026년 11월 6일 17:00까지
                • 제출처·방법: [전자입찰 접수](https://example.org/bid)
                • 문의 담당: 계약 담당 02-0000-1111
                제출 준비 서류 ↓
                • [제안요청서 및 제출양식](https://example.org/rfp)
                • 가격제안서
                • 중소기업 확인서
                원문: [게시글 보기](https://example.org/procurement)
                """;
        String notice = """
                ▸ 공공기관 홈페이지 서비스 일시 중단 안내
                기관: 예시 공공기관
                문서 유형: 일반 공지 │ 신규 │ 기회점수: 12점
                공고 성격: 일반 공지
                요약: 시스템 정기 점검으로 홈페이지 서비스가 일시 중단됩니다. 점검 시간 이후 정상 이용할 수 있습니다.
                • 관련 일정: 시스템 점검: 2026년 11월 7일 00:00~06:00
                • 문의 담당: 운영지원팀 02-0000-2222
                원문: [게시글 보기](https://example.org/service)
                """;
        String html = ReportEmailTemplate.render("공공기관 동향 보고서", "신규 2건 │ 수정 1건\n\n" + BUSINESS + procurement + notice);
        Path path = Path.of("build/report-preview/email-opportunity.html");
        Files.createDirectories(path.getParent());
        Files.writeString(path, html);
        assertThat(html.split("data-report-card", -1)).hasSize(4);
    }

    @Test void writesTheFollowupPreviewWithDuplicateKpassAndLongGovernmentGrantTerms() throws Exception {
        String international = BUSINESS.replace("국내 접수: [K-PASS 온라인 접수](https://example.org/apply?a=1&b=2)",
                "국문 접수: [K-PASS 온라인 접수](https://k-pass.kr/)\n  ↳ K-PASS 온라인 접수: https://k-pass.kr/");
        String quantum = """
                ▸ 2026년도 양자공동연구실 신규과제 공고
                기관: 예시 연구원
                문서 유형: 사업 공고 │ 신규 │ 기회점수: 82점
                공고 성격: 사업 기회
                사업 유형: R&D·기술개발
                사업 방식: 연구개발비 지원
                요약: 산학연 공동 연구를 수행할 신규 과제를 모집합니다.
                • 금액·규모: 903백만원
                  ↳ □ 사업기간/예산(정부출연금): 2026년~2029년 / 과제당 총 9.03억원 이내이며 연차별 지급금액은 협약에 따라 정합니다. 선정평가와 정부 예산 사정에 따라 지원 규모가 변경될 수 있으므로 사업계획에 반영해야 합니다. 중소기업은 정부지원연구개발비 75% 이내이며 기관부담연구개발비는 25% 이상으로 현금 부담 조건을 반드시 충족해야 합니다. 신규 채용의 인건비 인정 여부와 부가가치세 지원 제외 조건은 협약 시 확인합니다.
                • 제출 주체·대상: 기업·대학·연구기관. 기업 참여 시 신규 채용 조건 충족 필수.
                • 제출·의견 기한: 접수 마감: 2026년 11월 5일 18:00까지
                • 제출처·방법: IRIS 온라인 접수: https://www.iris.go.kr/
                원문: [게시글 보기](https://example.org/quantum?notice=903&year=2026)
                """;
        String general = """
                ▸ 홈페이지 서비스 점검 안내
                기관: 예시 공공기관
                문서 유형: 일반 공지 │ 수정 │ 기회점수: 12점
                공고 성격: 일반 공지
                요약: 정기 점검으로 서비스가 일시 중단됩니다.
                • 관련 일정: 점검: 2026년 11월 7일 00:00~06:00
                원문: [게시글 보기](https://example.org/service)
                """;
        String html = ReportEmailTemplate.render("공공기관 동향 보고서", "신규 2건 │ 수정 1건\n" + international + quantum + general);
        Path path = Path.of("build/report-preview/email-followup-fixes.html");
        Files.createDirectories(path.getParent());
        Files.writeString(path, html);
        assertThat(html.split("data-report-card", -1)).hasSize(4);
        assertThat(html.split("href=\"https://k-pass.kr/\"", -1)).hasSize(2);
        assertThat(html).contains("903백만원", "9.03억원", "정부출연금", "75%", "25%", "공고 원문 보기</a>")
                .doesNotContain("□", "사업기간/예산");
    }
    private static String section(String html, String key) {
        int start = html.indexOf("data-report-section=\"" + key + "\"");
        assertThat(start).as("section %s exists", key).isGreaterThanOrEqualTo(0);
        int next = html.indexOf("data-report-section=", start + 1);
        if (next < 0) next = html.length();
        return html.substring(start, next);
    }
}
