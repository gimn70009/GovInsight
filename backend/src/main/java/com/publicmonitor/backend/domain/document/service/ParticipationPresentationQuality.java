package com.publicmonitor.backend.domain.document.service;

import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;
import java.util.List;
import java.util.regex.Pattern;

/** Checks presentation, not semantic equivalence or legal eligibility. Originals remain available. */
final class ParticipationPresentationQuality {
    static final String DETAIL_REVIEW = "일부 세부 조건을 문장으로 정리하지 못했습니다. 원문 근거를 확인해 주세요.";
    private static final Pattern RAW_MARKERS = Pattern.compile("[\\r\\n\\t※⏎■□▪●•◦]|(?:^|\\s)[*①-⑳]|(?:^|\\s)(?:[-–—]|\\d+[.)])\\s");
    private static final Pattern PROCEDURE_HEADING = Pattern.compile("(?:신청|접수|제출)\\s*(?:방법|절차|안내)|사전\\s*준비|필수\\s*이행\\s*사항");
    private static final Pattern ADMIN_ACTION = Pattern.compile("회원가입|로그인|계정|권한(?:부여|설정)|(?:기관|대표자)(?:정보)?등록|전산등록|업로드|전자서명");
    private static final Pattern QUALIFICATION = Pattern.compile(
            "(?:신청|지원|참여)(?:자격|대상|요건)|(?:법률|법령|조례|법에|법상|법제\\d+조).*(?:등록|허가|지정)"
            + "|허가|사업자등록|법인등록|업력|인증|실적|매출|수출|인력|시설|소재지|설립|중소기업|중견기업|비영리|면허|납세|체납|참여제한|부채비율|자본잠식");

    private ParticipationPresentationQuality() {}

    static boolean completeSentence(String text) {
        if (text == null || text.isBlank() || RAW_MARKERS.matcher(text.strip()).find()) return false;
        for (String sentence : text.strip().split("(?<=[.!?。])\\s+(?=[가-힣])"))
            if (!sentence.matches("(?s).*(?:니다|십시오)[.!。]$")) return false;
        return true;
    }

    static boolean redundantDetail(Statement item, Detail detail) {
        if (item.reviewRequired() || !completeSentence(item.text())) return false;
        String main = compact(item.text()), extra = compact(detail.text());
        if (main.equals(extra)) return true;
        // Only complete sentence boundaries prove containment. Similar words or shared quotes do not.
        if (!completeSentence(detail.text())) return false;
        String[] sentences = item.text().strip().split("(?<=[.!?。])\\s+(?=[가-힣])");
        for (int start = 0; start < sentences.length; start++) {
            StringBuilder span = new StringBuilder();
            for (int end = start; end < sentences.length; end++) {
                span.append(compact(sentences[end]));
                if (span.toString().equals(extra)) return true;
            }
        }
        return false;
    }

    private static String compact(String value) { return value.replaceAll("\\s+", ""); }

    static boolean procedureOnly(Statement item, List<RequirementSource> sources) {
        if (!"ELIGIBILITY".equals(item.kind()) || !"BUSINESS_FORM".equals(item.criterion()) || sources.isEmpty()
                || sources.stream().anyMatch(source -> source.sectionTitle() == null
                    || !PROCEDURE_HEADING.matcher(source.sectionTitle()).find())) return false;
        String quote = String.join("\n", item.supportingQuotes()).replaceAll("\\s+", "");
        String qualifications = item.text() + item.details().stream()
                .map(detail -> detail.text() + String.join("", detail.supportingQuotes())).reduce("", String::concat);
        // A mixed paragraph (including attached definitions) stays with eligibility.
        return ADMIN_ACTION.matcher(quote).find() && !QUALIFICATION.matcher(quote).find()
                && !QUALIFICATION.matcher(qualifications.replaceAll("\\s+", "")).find();
    }
}
