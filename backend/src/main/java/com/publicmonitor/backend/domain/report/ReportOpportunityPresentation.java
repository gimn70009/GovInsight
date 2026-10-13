package com.publicmonitor.backend.domain.report;

import java.util.ArrayList;
import java.util.List;

/** Reads explicit report metadata without inferring a business classification for saved reports. */
final class ReportOpportunityPresentation {
    record Header(String institution, String nature, String businessType, String businessMethod,
                  List<String> legacyTags, int bodyStart) {
        boolean business() { return nature.equals("사업 기회"); }
        boolean generalNotice() { return nature.equals("일반 공지"); }

        String category() {
            if (business()) return "사업 공고";
            if (generalNotice()) return "일반 공지";
            if (nature.isBlank() && !legacyTags.isEmpty()) {
                String prior = legacyTags.getFirst();
                if (!prior.startsWith("기회점수:") && !prior.matches("신규|수정|변경 없음")) return prior;
            }
            return "분류 확인 필요";
        }

        String typeDisplay() {
            if (generalNotice()) return "일반 공지";
            if (!unknownOnly(businessType)) return businessType;
            return business() ? "원문 확인 필요" : "분류 정보 없음";
        }

        String methodDisplay() {
            if (generalNotice()) return "해당 없음 (일반 공지)";
            if (!unknownOnly(businessMethod)) return businessMethod;
            return business() ? "원문 확인 필요" : "분류 정보 없음";
        }

        List<String> badges() {
            var badges = new ArrayList<String>();
            badges.add(legacyTags.stream().filter(tag -> tag.startsWith("기회점수:"))
                    .findFirst().orElse("기회점수: 미산정"));
            badges.add(category());
            var states = new ArrayList<String>();
            for (int i = 1; i < legacyTags.size(); i++) {
                String tag = legacyTags.get(i);
                if (!tag.startsWith("기회점수:")) states.add(tag);
            }
            badges.add(states.isEmpty() ? "상태 미확인" : String.join(" · ", states));
            return List.copyOf(badges);
        }
    }

    private ReportOpportunityPresentation() {}

    static Header header(List<String> content) {
        String institution = "";
        String nature = "";
        String businessType = "";
        String businessMethod = "";
        var tags = new ArrayList<String>();
        int row = 1;
        for (; row < content.size(); row++) {
            String line = content.get(row).strip();
            if (line.isBlank()) continue;
            if (line.startsWith("기관:")) institution = line.substring("기관:".length()).strip();
            else if (line.startsWith("문서 유형:")) {
                for (String tag : line.substring("문서 유형:".length()).split("│")) {
                    if (!tag.isBlank()) tags.add(tag.strip());
                }
            } else if (line.startsWith("공고 성격:")) nature = line.substring("공고 성격:".length()).strip();
            else if (line.startsWith("사업 유형:")) businessType = line.substring("사업 유형:".length()).strip();
            else if (line.startsWith("사업 방식:")) businessMethod = line.substring("사업 방식:".length()).strip();
            else break;
        }
        return new Header(institution, nature, businessType, businessMethod, List.copyOf(tags), row);
    }

    static boolean unknownOnly(String value) {
        String text = value.strip().replaceFirst("[.!。]+$", "");
        return text.isBlank() || text.matches("(?:원문\\s*)?확인\\s*필요|미확인|미정|정보\\s*없음|해당\\s*없음")
                || text.equals("등록된 첨부파일 없음") || text.equals("원문에서 확인해 주세요")
                || text.matches("(?:원문에(?:서)?\\s*)?(?:명시되지 않았습니다|확인되지 않았습니다)");
    }
}
