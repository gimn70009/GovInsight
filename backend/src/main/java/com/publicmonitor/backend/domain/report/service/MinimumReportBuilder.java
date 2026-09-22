package com.publicmonitor.backend.domain.report.service;

import com.publicmonitor.backend.domain.analysis.entity.DocumentAnalysis;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import java.net.URI;
import java.util.List;
import java.util.Map;

public final class MinimumReportBuilder {
    private MinimumReportBuilder() {}
    public record Draft(String title, String summary) {}
    public static Draft build(MonitoringRun run, List<DocumentDetection> detections, Map<Long, DocumentAnalysis> analyses) {
        String title = "[공공기관 모니터링] " + (run.getRequestedAt() == null ? "" : run.getRequestedAt().toLocalDate() + " ") + "보고서";
        StringBuilder body = new StringBuilder("확인한 게시글 " + detections.size() + "건\n\n");
        int included = 0;
        for (var detection : detections) {
            var version = detection.getDocumentVersion();
            var analysis = analyses.get(version.getId());
            String url = safeUrl(detection.getDocument().getOriginalUrl());
            String block = "▸ " + text(version.getTitle(), 180) + "\n기관: "
                    + text(detection.getMonitoringRunSource().getMonitoringSource().getOrganizationName(), 100)
                    + "\n요약: " + (analysis == null ? "분석 결과를 확인하지 못했습니다." : text(analysis.getSummary(), 180))
                    + (url == null ? "\n원문: 게시글 상세에서 확인" : "\n원문: [게시글 보기](" + url + ")") + "\n\n";
            // Includes URLs in the budget, so both Telegram display and DB storage stay bounded.
            if (body.length() + block.length() > 3300) break;
            body.append(block); included++;
        }
        if (included < detections.size()) body.append("그 외 ").append(detections.size() - included).append("건은 감지된 게시글에서 확인하세요.\n");
        if (detections.isEmpty()) body.append("이번 실행에서 확인한 게시글이 없습니다.\n");
        body.append("\n미확인 항목: 상세 제출 안내 — 기본 정보로 작성한 보고서입니다.");
        return new Draft(title, body.toString());
    }
    private static String text(String value, int limit) {
        if (value == null || value.isBlank()) return "확인되지 않음";
        String clean = value.replaceAll("\\s+", " ").replace('[', '［').replace(']', '］').strip();
        if (clean.length() <= limit) return clean;
        int end = Character.isHighSurrogate(clean.charAt(limit - 1)) ? limit - 1 : limit;
        return clean.substring(0, end) + "…";
    }
    private static String safeUrl(String value) {
        if (value == null || value.length() > 2000 || value.chars().anyMatch(c -> c <= 32) || value.contains("\\")) return null;
        try {
            URI uri = URI.create(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null || uri.getUserInfo() != null) return null;
            return value.replace("(", "%28").replace(")", "%29");
        } catch (IllegalArgumentException e) { return null; }
    }
}
