package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Reads only explicitly aligned criterion/score columns; flattened tables require review. */
final class ParticipationScoreTables {
    private static final Pattern FLAT_HEADER = Pattern.compile("(?:구분|번호|순번)(?:가점|감점|우대)(?:사항|항목|기준)?(?:가점|감점|점수|배점)");
    private static final Pattern SCORE = Pattern.compile("[+−–-]?\\d+(?:\\.\\d+)?점");
    private static final String[][] TOPICS = {
            {"여성연구자", "여성연구자 참여"}, {"여성인|여성기업", "여성기업"},
            {"기술이전", "기술이전 실적"}, {"기업부설연구소", "기업부설연구소"},
            {"의료기기", "의료기기 인증"}, {"혁신제품|혁신제.?품|NET|NEP", "혁신제품 인증"},
            {"수행결과|평가결과", "연구개발 평가 실적"}, {"수상|포상|표창", "수상·포상 실적"},
            {"직무발명", "직무발명보상"}, {"성과활용", "연구 성과 활용"},
            {"소외지역", "연구개발 소외지역"}, {"보안과제", "보안과제 수행"},
            {"국제공동", "국제 공동연구"}, {"사업재편", "사업재편 승인"},
            {"사회적기업", "사회적기업"}, {"고용", "고용 실적"}, {"수요기업", "수요기업 참여"},
            {"제재부가금|환수금", "제재부가금·환수금 납부 여부"}, {"제재처분", "제재 이력"}, {"수행을포기", "과제 수행 포기"}
    };
    private ParticipationScoreTables() {}

    static boolean header(String text) {
        var cells = java.util.Arrays.stream(text.split("\t", -1)).map(value -> value.replaceAll("\\s+", "")).toList();
        return cells.stream().anyMatch(value -> value.matches("(?:가점|감점|우대)(?:사항|항목|조건|기준)|평가내용"))
                && cells.stream().anyMatch(value -> value.matches("가점|감점|점수|배점"));
    }

    static boolean flattened(String text) { return FLAT_HEADER.matcher(text.replaceAll("\\s+", "")).find(); }

    static boolean organized(ParticipationCondition row) {
        return (row.kind() == ParticipationKind.PREFERENCE || row.kind() == ParticipationKind.DEDUCTION)
                && row.category().matches("평가 가점|평가 감점");
    }

    static ParticipationCondition read(String[] rows, List<String> notes, RequirementSource source) {
        if (rows.length < 2 || ParticipationReadability.damagedWord(source.excerpt())) return null;
        String[] headers = rows[0].split("\t", -1);
        int criterion = -1, score = -1;
        boolean deduction = false;
        for (int i = 0; i < headers.length; i++) {
            String header = headers[i].replaceAll("\\s+", "");
            if (header.matches("(?:가점|감점|우대)(?:사항|항목|조건|기준)|평가내용")) criterion = i;
            if (header.matches("가점|감점|점수|배점")) { score = i; deduction = header.equals("감점"); }
        }
        if (criterion < 0 || score < 0) return null;
        deduction |= headers[criterion].contains("감점");
        String effect = deduction ? "감점" : "가점";
        var items = new ArrayList<String>();
        var legalCriteria = new LinkedHashSet<String>();
        var topics = new LinkedHashSet<String>();
        var points = new LinkedHashSet<String>();
        for (int r = 1; r < rows.length; r++) {
            String[] cells = rows[r].split("\t", -1);
            if (cells.length != headers.length || cells[criterion].isBlank()) return null;
            String point = cells[score].replaceAll("\\s+", "");
            if (point.matches("[+−–-]?\\d+(?:\\.\\d+)?")) point += "점";
            if (!SCORE.matcher(point).matches() || (!deduction && point.matches("^[−–-].*"))
                    || (deduction && point.startsWith("+"))) return null;
            points.add(point.replaceFirst("^[+−–-]", ""));
            String topic = topic(cells[criterion], effect + " 항목 " + r);
            topics.add(topic);
            var lines = new ArrayList<String>();
            lines.add(topic + " · " + point.replaceFirst("^[+−–-]", "") + " " + effect);
            for (String clause : ParticipationConditionText.paragraphs(List.of(cells[criterion]
                    .replaceAll("\\h+(?=※)", "\n")))) {
                var explanation = ParticipationReadability.explainLegal(clause);
                String value = ParticipationReadability.clause(explanation.text());
                if (!value.isBlank()) lines.add(value);
                if (explanation.legal() != null) legalCriteria.add("법적 자격 기준: " + topic + " — " + explanation.legal());
            }
            for (int c = 0; c < cells.length; c++) {
                if (c == criterion || c == score || headers[c].strip().matches("구분|번호|순번") || cells[c].isBlank()) continue;
                lines.add(ParticipationReadability.clean(headers[c]) + ": " + ParticipationReadability.evidenceClause(cells[c]));
            }
            items.add(String.join("\n", lines));
        }
        if (items.isEmpty()) return null;
        String subjects = String.join(", ", topics.stream().limit(3).toList()) + (topics.size() > 3 ? " 등" : "");
        String summary = subjects + (deduction ? "의 감점 기준에 해당하면 " : "의 인정 기준에 해당하면 ")
                + (points.size() == 1 ? "항목별로 " + points.getFirst() + "을 " : "항목별 점수를 ")
                + (deduction ? "감점합니다." : "가점으로 반영합니다.");
        String noteText = String.join(" ", notes).replaceAll("\\h+", " ");
        var limit = Pattern.compile("(?:총|최대)\\h*[−–-]?(\\d+(?:\\.\\d+)?)\\h*점(?:\\h*이내|\\h*한도)").matcher(noteText);
        if (limit.find()) summary += "\n" + effect + " 합계는 최대 " + limit.group(1) + "점입니다.";
        if (!notes.isEmpty()) items.add("공통 적용 기준\n" + String.join("\n", notes.stream()
                .map(ParticipationReadability::clause).filter(value -> !value.isBlank()).toList()));
        items.addAll(legalCriteria);
        return new ParticipationCondition(deduction ? ParticipationKind.DEDUCTION : ParticipationKind.PREFERENCE,
                "평가 " + effect, summary, null, items, source);
    }

    static ParticipationCondition review(ParticipationKind kind, RequirementSource source) {
        String effect = kind == ParticipationKind.DEDUCTION ? "감점" : "가점";
        return new ParticipationCondition(ParticipationKind.REVIEW_REQUIRED, "평가 " + effect,
                effect + " 표의 항목별 조건·점수·증빙 연결을 확인해야 합니다.", null, List.of(), source,
                ParticipationReviewReason.TABLE_MAPPING_UNCLEAR);
    }

    private static String topic(String text, String fallback) {
        String compact = text.replaceAll("\\s+", "");
        if (compact.matches(".*(?:제재부가금|환수금).*납부하지않.*")) return "제재부가금·환수금 미납";
        for (String[] candidate : TOPICS) if (Pattern.compile(candidate[0]).matcher(compact).find()) return candidate[1];
        return fallback;
    }
}
