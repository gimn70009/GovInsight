package com.publicmonitor.backend.domain.document.service;

import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;

/** Distinguishes applicant requirements from nearby titles and spending instructions. */
final class ParticipationEligibilityContext {
    private static final Pattern PROJECT_HEADING = Pattern.compile(
            "^(?:신청|지원|참여|모집)대상[:：]?(?:사업|과제|기술|품목)(?:목록|안내|현황|중복성제기)?[)）]?(?:[:：].*)?$");
    private static final Pattern EXPENDITURE = Pattern.compile(
            "(?:연구개발비|기술활용비|연구비|사업비|인건비|간접비)(?:로|에서)(?:집행|지급|계상|편성)(?:가능|할수있)");
    private static final Pattern ELIGIBILITY_EFFECT = Pattern.compile(
            "(?:신청|참여)(?:할수|이|가|는|를|가?제한)?(?:가능|불가|있|없|제한|필수|해야)"
                    + "|(?:기업|기관|법인|사업자)(?:이어야|여야|만가능|만신청)"
                    + "|(?:보유|등록|소재|이전).{0,25}(?:해야|하여야|필수|완료필요)"
                    + "|(?:자부담|민간부담금|기관부담연구개발비).{0,30}(?:의무|부담해야|매칭)");

    private ParticipationEligibilityContext() {}

    static boolean projectHeading(String line) {
        return PROJECT_HEADING.matcher(compact(line).replaceFirst("^[<\\[(（]", "")
                .replaceFirst("^\\d+(?:-\\d+)?[.)]?", "")).matches();
    }

    static boolean referenceFootnote(String line) {
        return line.strip().matches("^[*※].*(?:참고|참조|확인)(?:하세요|바랍니다|할것)?[.]?$");
    }

    static boolean spendingInstruction(String text) {
        String compact = compact(text);
        return EXPENDITURE.matcher(compact).find() && !applicantRequirement(text);
    }

    static boolean applicantRequirement(String text) {
        String compact = compact(text);
        return ELIGIBILITY_EFFECT.matcher(compact).find()
                || compact.matches(".*(?:기업|기관|법인|사업자|소상공인)[.]?$");
    }

    static boolean headingFragment(String text) {
        return compact(text).matches("(?:사업|과제|구분|자격|요건|조건|기관|대상)[.]?");
    }

    static boolean compositionHeading(String text) {
        return compact(text).matches("(?:연구개발기관|수행기관|참여기관|컨소시엄)의?구성(?:요건|기준)(?:[:：].*)?");
    }

    static boolean fundingCondition(String text) {
        String value = compact(text);
        boolean money = value.matches(".*(?:자부담|민간부담금|기관부담연구개발비|연구개발비|사업비|간접비|인건비).*" );
        boolean terms = value.matches(".*(?:부담|매칭|편성|집행|계상|재투자|사용|현금|현물).*" );
        // A financial prerequisite explicitly controlling application is still an applicant requirement.
        boolean applicantGate = value.matches(".*(?:능력|여력|확보가능|조달가능).*(?:기업|기관|사업자).*" )
                || value.matches(".*(?:신청|참여)(?:자격|요건|대상|가능|불가|할수|이제한|을제한).*" )
                || value.matches(".*(?:확보|조달|보유).*(?:기업|기관|사업자|자)(?:만|에한해|이어야|여야).*" )
                || value.matches(".*(?:신청|접수)(?:시|전|마감일).*(?:확보|조달|증빙).*" );
        return money && terms && !applicantGate;
    }

    /** Source excerpts stay untouched; only independent non-application clauses are removed. */
    static ParticipationCondition applicationOnly(ParticipationCondition row) {
        if (row.reviewReason() == ParticipationReviewReason.PREFERENCE_NEGATED
                || row.kind() == ParticipationKind.FUNDING || row.kind() == ParticipationKind.PREFERENCE
                || row.kind() == ParticipationKind.DEDUCTION) return row;
        var retained = new ArrayList<String>();
        var input = new ArrayList<String>();
        input.add(row.text());
        input.addAll(row.details());
        boolean removed = false;
        for (String paragraph : input) {
            var clauses = clauses(paragraph);
            // Preserve standalone cost data for the existing hidden FUNDING section.
            if (row.kind() != ParticipationKind.EXCLUSION && clauses.size() == 1 && fundingCondition(paragraph)) {
                retained.add(paragraph);
                removed = false;
                continue;
            }
            for (String clause : clauses) {
                if ((removed && dependentException(ParticipationConditionText.unmark(clause)))
                        || nonApplicationClause(clause, row.kind() == ParticipationKind.EXCLUSION)) {
                    removed = true;
                } else {
                    retained.add(clause);
                    removed = false;
                }
            }
        }
        if (retained.isEmpty()) return null;
        return new ParticipationCondition(row.kind(), row.category(), retained.getFirst(), row.scope(),
                List.copyOf(retained.subList(1, retained.size())), row.source(), row.reviewReason());
    }

    private static List<String> clauses(String text) {
        // Only split complete sentences or an explicit post-selection obligation, never AND/OR lists.
        String separated = text.replaceAll("(?:이며|이고),?\\h*(?=선정\\h*후|선정된|협약\\h*체결\\h*후|사업\\h*수행\\h*중)", "입니다. ");
        separated = separated.replaceAll("(집행(?:할\\h*수\\h*있|\\h*가능))으나\\h*(?=신청기업|신청기관)", "$1. ");
        return List.of(separated.split("(?<=[.!?。])\\h+(?=[가-힣A-Za-z(*※])"));
    }

    private static boolean nonApplicationClause(String text, boolean exclusion) {
        String value = compact(ParticipationConditionText.unmark(text));
        boolean gate = exclusion || value.matches(".*(?:신청|참여)(?:할수없|불가|이제한|자격|대상|요건|가능|할수있).*" );
        boolean possession = value.matches(".*(?:보유|등록(?:되어|된|한|을|이|해야)|인정받|인증받).*(?:기업|기관|사업자|해야|하여야|필수).*" );
        if (value.matches(".*(?:선정후|선정된(?:기업|기관)|협약체결후|사업수행중|사업종료후|사후관리).*" )
                && value.matches(".*(?:제출|보고|납부|부담|관리|협조|유지|이행|정산|사용|집행|의무).*" )) return true;
        if (value.matches(".*(?:가점|우대점수|평가점수|평가배점).*(?:부여|적용|평가).*")) return true;
        if (gate || possession) return false;
        return fundingCondition(text)
                || value.matches(".*(?:연구개발비|연구비|사업비|인건비|간접비|기술활용비).*(?:집행|지급|계상|편성|사용)(?:가능|불가|할수|해야|하여야).*" )
                || value.matches("(?:정부지원금|지원금액|지원비율|지원기간|지원한도|지원규모|지원금|총사업비|정부지원연구개발비)(?:은|는|:|：|[0-9]).*(?:원|%|개월|년|지원|구성|부담).*" )
                || value.matches(".*(?:사업계획서|신청서|확약서|등기부등본|사업자등록증|증명서|확인서|보고서|제출서류).*(?:제출|첨부|업로드|작성)(?:해야|하여야|이필수|가필수|필수|함|한다|합니다|할것|서류|[.]?$).*");
    }

    static List<ParticipationCondition> separateFunding(ParticipationCondition row) {
        if (row.kind() == ParticipationKind.REVIEW_REQUIRED && fundingCondition(row.text()))
            return List.of(new ParticipationCondition(row.kind(), "자부담·사업비", row.text(), row.scope(), row.details(), row.source(), row.reviewReason()));
        if (row.kind() != ParticipationKind.MANDATORY) return List.of(row);
        if (fundingCondition(row.text())) return List.of(new ParticipationCondition(ParticipationKind.FUNDING,
                "자부담·사업비", row.text(), row.scope(), row.details(), row.source(), row.reviewReason()));
        var retained = new ArrayList<String>();
        var funding = new ArrayList<ParticipationCondition>();
        for (int i = 0; i < row.details().size(); i++) {
            String detail = row.details().get(i);
            if (fundingCondition(detail) && !dependentException(detail)) {
                var exceptions = new ArrayList<String>();
                while (i + 1 < row.details().size() && dependentException(row.details().get(i + 1)))
                    exceptions.add(row.details().get(++i));
                funding.add(new ParticipationCondition(ParticipationKind.FUNDING, "자부담·사업비", detail, row.scope(), exceptions, row.source()));
            } else retained.add(detail);
        }
        if (funding.isEmpty()) return List.of(row);
        funding.addFirst(new ParticipationCondition(row.kind(), row.category(), row.text(), row.scope(), retained, row.source(), row.reviewReason()));
        return List.copyOf(funding);
    }

    private static boolean dependentException(String text) {
        return text.matches("^(?:[*※]\\h*)?(?:단[,\\h]|다만|예외|면제).*" );
    }

    private static String compact(String text) {
        return text.replaceAll("\\s+", "");
    }
}
