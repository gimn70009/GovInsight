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
    private static final Pattern CLOSED_TITLE = Pattern.compile("결과|종료|마감|취소|(?:입상작|입상자|수상작|수상자|당선작|당선자)(?:발표|공고|명단)");
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


    enum Nature {
        RESULT("일반 공고 · 결과 발표"), REGULATION("일반 공고 · 규정 고시"),
        AWARD("포상·표창 모집"), CONTEST("공모전·경진대회"), DESIGNATION_RECRUITMENT("지정 모집"), GENERAL("일반 공고");
        private final String label;
        Nature(String label) { this.label = label; }
        String label() { return label; }
    }
    private static final Pattern RESULT_ACTION = Pattern.compile(
            "(?:선정|평가|심사|합격).{0,12}결과(?:를|을).{0,25}(?:공고|알립|안내|발표|통보)|(?:입상작|입상자|수상작|수상자|당선작|당선자)(?:을|를|은|는)?(?:다음|아래|붙임)?(?:(?:과|와)같이)?(?:발표합니다|공고합니다|알립니다|발표하였습니다|선정하였습니다)");
    private static final Pattern SELECTION_PUBLICATION_TITLE = Pattern.compile("(?:선정|선발|확정)(?:결과)?(?:공고|공개|알림|안내)$");
    private static final Pattern SELECTION_PUBLICATION_ACTION = Pattern.compile("(?:선정|선발|확정)결과(?:를|을).{0,25}(?:공고합니다|공고하였습니다|공개합니다|발표합니다|알려드립니다)");
    private static final Pattern REGULATION_TITLE = Pattern.compile("규정|규칙|지침|고시");
    private static final Pattern REGULATION_ACTION = Pattern.compile(
            "(?:규정|규칙|지침|고시).{0,40}(?:제정|개정)하여고시|(?:제정|개정)(?:하여)?고시합니다");
    private static final Pattern AWARD_TITLE = Pattern.compile("(?:포상|표창)(?:후보자|대상자|유공자)?(?:모집|신청|추천|안내|공고|계획|추가|수정|연장)");
    private static final Pattern AWARD_PROOF = Pattern.compile("장관표창|포상(?:대상|목적|계획|훈격|규모)|훈격");
    private static final Pattern DESIGNATION_RECRUITMENT_TITLE = Pattern.compile("선정계획|지정.{0,20}(?:모집|공모|신청)");
    private static final Pattern CONDITIONAL_DESIGNATION = Pattern.compile(
            "지정되더라도.{0,25}지원.{0,25}(?:확정되는것은아님|확정되지않)|"
                    + "지정신청(?:서)?(?:을|를)?(?:받습니다|접수합니다)|지정을희망하는.{0,20}(?:신청|제출)");

    private static final Pattern FILED_APPLICATION_TITLE = Pattern.compile("(?:지정|인증|허가|승인).{0,16}신청");
    private static final Pattern FILED_APPLICATION = Pattern.compile("(?:지정|인증|허가|승인)신청이(?:있어|있으므로|접수되어|접수되었)");
    private static final Pattern OPINION_REQUEST = Pattern.compile("(?:이해관계인|이해관계자|주민).{0,220}(?:의견서?|이의신청).{0,160}(?:제출|접수)");
    private static final Pattern NEW_APPLICATION_TITLE = Pattern.compile("모집|공모|접수|입찰|(?:신규|추가)신청");
    private static final Pattern NEW_APPLICATION_ACTION = Pattern.compile("(?:모집|공모)(?:합니다|한다|하고자|하오니|공고|안내|대상|기간)|(?:지정|인증|허가|승인|지원|참여)신청(?:서)?(?:을|를)?(?:받|접수)|(?:제안서|사업계획서|(?<!이의)(?<!의견)신청서)(?:를|을)?(?:작성하여)?제출|(?<!의견)(?<!이의)신청(?:기간|기한|방법|대상|하세요|하시기|바랍니다)");

    private static boolean filedApplicationOpinion(String title, List<String> sources, List<String> introductions) {
        if (!FILED_APPLICATION_TITLE.matcher(title).find() || NEW_APPLICATION_TITLE.matcher(title).find()) return false;
        // A notice about an already filed application may still request opinions, not new applications.
        // Check new applications separately so mixed opinion/recruitment paragraphs remain actionable.
        if (sources.stream().anyMatch(text -> NEW_APPLICATION_ACTION.matcher(compact(text)).find())) return false;
        return introductions.stream().map(GeneralNoticeClassifier::compact)
                .anyMatch(text -> !NON_CURRENT.matcher(text).find()
                        && FILED_APPLICATION.matcher(text).find() && OPINION_REQUEST.matcher(text).find());
    }

    private static final Pattern CONTEST_TITLE = Pattern.compile("(?:공모전|경진대회|챌린지)(?:참가자|참가팀|참여자|참여팀)?(?:모집|접수|공고|개최|안내)(?:수정|연장|추가)?(?:공고)?$");
    private static final Pattern CONTEST_ACTION = Pattern.compile("(?:공모전|경진대회|대회)(?:를|을)?(?:(?:다음|아래)과같이)?(?:개최합니다|개최하오니|개최하고자|공고하오니|진행합니다)");
    private static final Pattern CONTEST_PRIZE = Pattern.compile("시상(?:내역|계획|규모)|(?:상금|장관상|상품)(?:을|은|의|과|\\(|형태|지급)|상금\\s*$");

    private static boolean contestNotice(String title, List<String> introductions) {
        if (!CONTEST_TITLE.matcher(title).find()) return false;
        return introductions.stream().anyMatch(text -> affirmative(text, CONTEST_ACTION)
                && CONTEST_PRIZE.matcher(compact(text)).find());
    }

    private static final Pattern PLANNING_SURVEY_PROOF = Pattern.compile("(?:본|금번|이번)?수요조사는.{0,100}선정(?:하는것이|이)?(?:아닌|아니라).{0,100}의견(?:을)?수렴");
    private static final Pattern SURVEY_OPERATOR_TITLE = Pattern.compile("수요조사.{0,15}(?:용역|수행기관|조사기관|운영기관)");
    private static final Pattern SURVEY_MIXED_RECRUITMENT = Pattern.compile("(?:지원대상|수혜|수행)(?:기업|기관|과제)(?:을|를)?(?:신규|추가)?모집|(?:이번|본)수요조사(?:참여|응답)(?:기업|기관).{0,80}(?:제공|지원)");
    private static final Pattern NEGATED_OPINION_ONLY = Pattern.compile("의견(?:을)?수렴.{0,12}(?:아니|아님|아닙|않)");

    private static final Pattern HISTORICAL_SURVEY = Pattern.compile("전년도|지난해|과거|예시|견본");

    private static boolean planningDemandSurvey(String title, List<String> sources) {
        if (!title.contains("수요조사") || SURVEY_OPERATOR_TITLE.matcher(title).find()) return false;
        if (sources.stream().anyMatch(text -> SURVEY_MIXED_RECRUITMENT.matcher(compact(text)).find())) return false;
        for (String text : sources) {
            String introduction = text.substring(0, Math.min(6000, text.length()));
            for (String sentence : introduction.split("[。!?]|(?<=\\.)\\s+|\\n\\s*\\n")) {
                String value = compact(sentence);
                if (HISTORICAL_SURVEY.matcher(value).find()) continue;
                if (PLANNING_SURVEY_PROOF.matcher(value).find() && !NEGATED_OPINION_ONLY.matcher(value).find()) return true;
            }
        }
        return false;
    }

    static Nature classify(String title, List<String> sources, String documentType, boolean hasBusinessType) {
        String heading = compact(title);
        var introductions = sources.stream().map(text -> text.substring(0, Math.min(6000, text.length()))).toList();
        boolean currentTitle = recruitmentTitle(heading);
        if (planningDemandSurvey(heading, sources)) return Nature.GENERAL;
        if (contestNotice(heading, introductions)) return Nature.CONTEST;
        if (filedApplicationOpinion(heading, sources, introductions)) return Nature.GENERAL;
        if (AWARD_TITLE.matcher(heading).find() && !CLOSED_TITLE.matcher(heading).find()
                && introductions.stream().anyMatch(text -> AWARD_PROOF.matcher(compact(text)).find())) return Nature.AWARD;
        if (DESIGNATION_RECRUITMENT_TITLE.matcher(heading).find()
                && introductions.stream().anyMatch(text -> CONDITIONAL_DESIGNATION.matcher(compact(text)).find())) {
            return Nature.DESIGNATION_RECRUITMENT;
        }
        // The publication act belongs to the notice introduction; application rules in its annex do not override it.
        if (!currentTitle && !heading.contains("행정예고") && !heading.contains("입법예고")
                && REGULATION_TITLE.matcher(heading).find()
                && introductions.stream().anyMatch(text -> affirmative(text, REGULATION_ACTION))) return Nature.REGULATION;
        boolean recruiting = sources.stream().anyMatch(GeneralNoticeClassifier::recruitmentBody);
        boolean selected = SELECTION_PUBLICATION_TITLE.matcher(heading).find()
                && !ADDITIONAL_RECRUITMENT.matcher(heading).find()
                && introductions.stream().anyMatch(text -> affirmative(text, SELECTION_PUBLICATION_ACTION));
        if ((!currentTitle || selected) && !recruiting
                && introductions.stream().anyMatch(text -> affirmative(text, RESULT_ACTION))) {
            return Nature.RESULT;
        }
        if (matchesLegacy(title, sources, documentType, hasBusinessType)) {
            return GENERAL_TITLE.matcher(heading).find() && heading.contains("결과") ? Nature.RESULT : Nature.GENERAL;
        }
        return null;
    }

    private static boolean affirmative(String text, Pattern action) {
        for (String sentence : text.split("[。!?]|(?<=\\.)\\s+|\\n\\s*\\n")) {
            String value = compact(sentence);
            if (!NON_CURRENT.matcher(value).find() && action.matcher(value).find()) return true;
        }
        return false;
    }

    private GeneralNoticeClassifier() {}

    private static boolean matchesLegacy(String title, List<String> sources, String documentType, boolean hasBusinessType) {
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
