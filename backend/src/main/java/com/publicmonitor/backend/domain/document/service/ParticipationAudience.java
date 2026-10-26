package com.publicmonitor.backend.domain.document.service;

import java.util.List;
import java.util.regex.Pattern;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.RequirementSource;

/** Explicit document roles, never a notice title or amount-specific rule. */
final class ParticipationAudience {
    static boolean recipient(List<RequirementSource> sources, String parent) {
        if (parent.contains("[별도 참여 역할] 구매기관")) return true;
        return sources.stream().anyMatch(source -> {
            String heading = source.sectionTitle() == null ? "" : source.sectionTitle();
            return Pattern.compile("(?:주관|운영|수행)\\s*기관.{0,30}모집").matcher(heading).find()
                    && Pattern.compile("창업기업.{0,45}별도.{0,15}공고").matcher(heading).find()
                    && parent.replaceAll("\\s+", "").matches("사업개요|지원사업개요")
                    && Pattern.compile("지원\\s*대상.*(?:기업|업력)").matcher(source.excerpt()).find();
        });
    }
}
