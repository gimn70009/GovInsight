package com.publicmonitor.backend.domain.document.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Restores explicit list/section boundaries in flattened notice prose, without rewriting evidence. */
final class ParticipationExecutionClauses {
    private static final Pattern TABLE = Pattern.compile("(?s)\\[표\\].*?\\[/표\\]");
    private static final Pattern BREAK = Pattern.compile(
            "\\s+(?=(?:[①-⑳➀-➉]|[○◦●※•*](?:\\s|(?=[가-힣]))|[oㅇ]\\s|[Ⅰ-ⅩIVX]{1,5}[.]?\\s+[가-힣]"
            + "|(?:\\d+|[가-하])[.]\\s+[가-힣]|\\[\\s*(?:[가-힣][^\\]]{0,40}(?:유의사항|신청자격)|공고\\s*참여국[^\\]]*)\\s*\\]))"
            + "|(?<=[다함음됨][.])\\s+");
    private ParticipationExecutionClauses() {}

    static List<String> split(String value) {
        var pieces = new ArrayList<String>();
        var table = TABLE.matcher(value);
        int start = 0;
        while (table.find()) {
            splitProse(value.substring(start, table.start()), pieces);
            pieces.add(table.group()); // Only the table reader may interpret cells; flattened tables are never prose.
            start = table.end();
        }
        splitProse(value.substring(start), pieces);
        return List.copyOf(pieces);
    }

    private static void splitProse(String value, List<String> pieces) {
        var match = BREAK.matcher(value);
        int start = 0;
        while (match.find()) {
            if (ParticipationClauseText.inside(value, match.start())) continue;
            if (value.substring(match.end()).matches("(?s)^\\([^()]*\\)[.]?$")) continue;
            if (value.substring(match.end()).matches("(?s)^(?:다만|단,|이때|이 경우).*")) continue;
            if (match.start() > start) pieces.add(value.substring(start, match.start()).strip());
            start = match.end();
        }
        if (start < value.length() && !value.substring(start).isBlank()) pieces.add(value.substring(start).strip());
    }

    static String display(String value) {
        String text = value.replaceAll("\\s+", " ").strip()
                .replaceFirst("^(?:[①-⑳➀-➉○◦●ㅇo•*※]|-(?!\\d))\\s*", "")
                .replaceAll("(합|입|됩|있습|없습)\\s+니다", "$1니다");
        // A named platform stays readable; its literal address remains available in the unchanged source.
        text = text.replaceAll("\\(https?://[^\\s)]+\\)", "");
        text = text.replaceFirst("첨부하여\\s*제출\\s*\\(필수\\)$", "첨부하여 제출해야 합니다.")
                .replaceFirst("제출\\s*\\(필수\\)$", "제출해야 합니다.")
                .replaceFirst("제출\\s*필수$", "제출해야 합니다.")
                .replaceFirst("제출[.]?$", "제출해야 합니다.")
                .replaceFirst("참여\\s*필수$", "참여가 필수입니다.")
                .replaceFirst("대상에서 제외$", "대상에서 제외됩니다.")
                .replaceFirst("기회 부여$", "기회를 부여합니다.")
                .replaceFirst("심사 실시$", "심사를 실시합니다.")
                .replaceFirst("평가$", "평가합니다.")
                .replaceFirst("갈음$", "갈음합니다.")
                .replaceFirst("아래와 같음$", "아래와 같습니다.")
                .replaceFirst("아래의 표와 같음$", "세부 조건의 자부담 표에 명시되어 있습니다.")
                .replaceFirst("구성\\s*\\(([^()]*)\\)$", "구성해야 합니다. ($1)");
        return ParticipationReadability.clause(text);
    }

    static boolean plan(String text) {
        return ParticipationCriterion.PLAN.topic.matcher(text).find()
                && !text.contains("[표]") && !text.contains("[/표]")
                && !ParticipationClauseText.administrative(text)
                && !text.matches(".*(?:접수 마감|접수처|영문 공고문|사업 절차 및|사업 추진체계).*")
                && text.matches(".*(?:제출(?:해야|하여야)|제출.{0,12}필수|필수.{0,12}제출|(?:작성|서명).{0,100}제출"
                    + "|동일하여야|일치.{0,40}제출|발표.{0,60}(?:심사|필수|실시|진행|제외|생략|갈음|부여|평가됨)"
                    + "|발표평가.{0,30}운영|사업계획서.{0,40}(?:평가|검토)).*");
    }

    static boolean funding(String text) {
        return ParticipationCriterion.MATCHING.topic.matcher(text).find()
                && !text.contains("[표]") && !text.contains("[/표]")
                && !ParticipationClauseText.administrative(text)
                && !text.matches(".*(?:유의사항|작성|집행|정의|이하,).*")
                && text.matches(".*(?:부담(?:하여야|해야|하지|의무|할)|매칭.{0,40}(?:%|비율|의무)|(?:현금|현물|자부담|부담비율).{0,100}(?:%|이상|이하|면제)).*");
    }
}
