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

    static boolean postAwardHeading(String line) {
        return compact(line).matches("기술료.*(?:감면|납부유예).*");
    }

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
