package com.publicmonitor.backend.domain.document.service;

import java.util.List;
import java.util.regex.Pattern;

/** Distinguishes an affirmative general-notice decision from a missing business category. */
final class GeneralNoticeClassifier {
    private static final Pattern GENERAL_TITLE = Pattern.compile(
            "(?:선정|평가|심사|합격|입찰|모집|공모).{0,10}결과|보도자료|행정예고|입법예고|정책발표|설명회개최|시스템점검");
    private static final Pattern STAFF_RECRUITMENT = Pattern.compile("(?:기간제|공무직|직원|연구원)채용(?!지원|장려|보조|사업)");
    private static final Pattern BRIEFING_TITLE = Pattern.compile("설명회(?:개최|참석|참가)");
    private static final Pattern DESIGNATION_TITLE = Pattern.compile("지정.{0,12}(?:고시|공고)|고시.{0,12}지정");
    private static final Pattern DESIGNATION_ACTION = Pattern.compile(
            "지정(?:을)?(?:해제|변경)?(?:하여|하고|하고이를|하였음을|되었음을|사항을|내용을|및|·|ㆍ|,)?"
                    + "(?:(?:다음|붙임|아래)과같이)?고시(?:합니다|한다|함)"
                    + "|지정(?:하였음|되었음)을(?:알립니다|공고합니다)");
    private static final Pattern ADDITIONAL_RECRUITMENT = Pattern.compile("추가모집|재공모|신규모집");
    private static final Pattern RECRUITMENT_TITLE = Pattern.compile("모집|공모|신청|접수|입찰");
    private static final Pattern CLOSED_TITLE = Pattern.compile("결과|종료|마감|취소");
    private static final Pattern RECRUITMENT_ACTION = Pattern.compile(
            "(?:모집|공모)(?:합니다|한다|하고자|하오니|공고|안내|대상|기간)"
                    + "|(?:신청|접수)(?:기간|기한|방법|대상|마감|하세요|하시기|바랍니다)"
                    + "|신청(?:을)?받(?:습니다|고있)|신청(?:할수있|가능)"
                    + "|(?:제안서|사업계획서|신청서)(?:를|을)?(?:작성하여)?제출");
    private static final Pattern NON_CURRENT = Pattern.compile(
            "전년도|지난해|과거|예시|견본|참고자료|모집하지않|공모하지않"
                    + "|(?:신청|접수|제출).{0,25}(?:받지않|요구하지않|필요없|필요하지않|없습니다)"
                    + "|(?:모집|접수|신청).{0,20}(?:종료되|마감되|완료되|마감하였|종료하였)");
    private static final Pattern OPINION = Pattern.compile("(?:의견|이의)(?:서)?(?:제출|신청|접수)");

    private GeneralNoticeClassifier() {}

    static boolean matches(String title, List<String> sources, String documentType, boolean hasBusinessType) {
        String heading = compact(title);
        // Attendance and employee applications do not make these notices business support.
        if (!ADDITIONAL_RECRUITMENT.matcher(heading).find()
                && (STAFF_RECRUITMENT.matcher(heading).find()
                    || (!hasBusinessType && BRIEFING_TITLE.matcher(heading).find()))) return true;
        // Current recruitment wins over a general heading or an older model classification.
        if (recruitmentTitle(heading) || sources.stream().anyMatch(GeneralNoticeClassifier::recruitmentBody)) {
            return false;
        }
        if (GENERAL_TITLE.matcher(heading).find()) return true;
        if (DESIGNATION_TITLE.matcher(heading).find() && sources.stream().anyMatch(GeneralNoticeClassifier::designationBody)) {
            return true;
        }
        // A null businessType alone is never evidence of a general notice. Use the existing
        // semantic document classification only when current source text is available and
        // it does not contradict a grounded support activity.
        return "GENERAL_NOTICE".equals(documentType) && !hasBusinessType
                && sources.stream().anyMatch(text -> !text.isBlank());
    }

    private static boolean recruitmentTitle(String title) {
        return ADDITIONAL_RECRUITMENT.matcher(title).find()
                || (RECRUITMENT_TITLE.matcher(title).find() && !CLOSED_TITLE.matcher(title).find()
                    && !OPINION.matcher(title).find());
    }

    private static boolean recruitmentBody(String text) {
        for (String sentence : text.split("[\\r\\n。!?]|(?<=\\.)\\s+")) {
            String value = compact(sentence);
            if (NON_CURRENT.matcher(value).find() || OPINION.matcher(value).find()) continue;
            if (RECRUITMENT_ACTION.matcher(value).find()) return true;
        }
        return false;
    }

    private static boolean designationBody(String text) {
        for (String sentence : text.split("[。!?]|(?<=\\.)\\s+|\\n\\s*\\n")) {
            String value = compact(sentence);
            if (!NON_CURRENT.matcher(value).find() && DESIGNATION_ACTION.matcher(value).find()) return true;
        }
        return false;
    }

    private static String compact(String value) { return value == null ? "" : value.replaceAll("\\s+", ""); }
}
