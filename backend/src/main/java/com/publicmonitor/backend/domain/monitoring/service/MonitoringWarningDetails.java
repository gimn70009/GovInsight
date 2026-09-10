package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.SourceResult;
import com.publicmonitor.backend.domain.document.web.dto.CollectionSourceStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSourceStatus;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringRunWarningsResponse.Warning;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/** Keeps the warning at collection time; shared attachment rows can change on a later run. */
public final class MonitoringWarningDetails {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern HTTP_STATUS = Pattern.compile("(?i)(?:HTTP|status(?: code)?)[^0-9]{0,20}([45][0-9]{2})");

    private MonitoringWarningDetails() {}

    public static void record(MonitoringRunSource source, SourceResult result) {
        List<Warning> warnings;
        if (result.status() == CollectionSourceStatus.FAILED) {
            warnings = List.of(item(source, "SOURCE_COLLECTION", null, null, explain(result.errorMessage(), true), 1));
        } else {
            warnings = result.documents().stream().flatMap(document -> document.attachments().stream()
                    .filter(file -> file.parseStatus() == AttachmentParseStatus.FAILED)
                    .map(file -> item(source, "ATTACHMENT_READ", document.title(), file.fileName(),
                            explain(file.errorMessage(), false), 1))).toList();
        }
        source.recordWarningDetails(JSON.writeValueAsString(warnings));
    }

    public static List<Warning> read(MonitoringRunSource source) {
        int expected = source.getStatus() == MonitoringRunSourceStatus.FAILED ? 1 : source.getWarningCount();
        if (expected == 0) return List.of();
        if (source.getWarningDetailsJson() != null) {
            try {
                var warnings = Arrays.asList(JSON.readValue(source.getWarningDetailsJson(), Warning[].class));
                if (warnings.size() == expected && warnings.stream().allMatch(warning -> warning != null
                        && warning.count() == 1 && warning.message() != null)) return warnings;
            } catch (RuntimeException ignored) {
                // Older or unreadable snapshots must not invent file names from today's attachments.
            }
        }
        if (source.getStatus() == MonitoringRunSourceStatus.FAILED) {
            return List.of(item(source, "SOURCE_COLLECTION", null, null, explain(source.getErrorMessage(), true), 1));
        }
        return List.of(item(source, "LEGACY_DETAILS_UNAVAILABLE", null, null,
                "첨부파일 읽기 실패가 있었지만, 당시 파일별 상세 기록은 남아 있지 않아요.", expected));
    }

    private static Warning item(MonitoringRunSource source, String stage, String title, String file,
            String message, int count) {
        var board = source.getMonitoringSource();
        return new Warning(stage, board.getOrganizationName(), board.getBoardName(), title, file, message, count);
    }

    static String explain(String raw, boolean source) {
        // External exceptions may contain URLs, headers or local paths; return only safe explanations.
        String value = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (value.contains("timeout") || value.contains("timed out") || value.contains("시간 초과") || value.contains("시간이 초과")) {
            return "응답 대기 시간이 초과됐어요.";
        }
        var http = HTTP_STATUS.matcher(value);
        if (http.find()) return "요청한 서버가 HTTP " + http.group(1) + " 오류를 반환했어요.";
        if (source) return "게시판에 접속하거나 글 목록을 읽지 못했어요.";
        if (value.contains("암호") || value.contains("encrypted") || value.contains("password protected")) {
            return "암호로 보호된 파일이라 내용을 읽지 못했어요.";
        }
        if (value.contains("크기") || value.contains("too large") || value.contains("size limit")) {
            return "파일 또는 압축 해제 용량이 처리 한도를 초과했어요.";
        }
        if (value.contains("손상") || value.contains("badzip") || value.contains("corrupt")) {
            return "파일 구조가 손상되어 내용을 읽지 못했어요.";
        }
        if (value.contains("텍스트를 찾지") || value.contains("no text")) {
            return "파일에서 읽을 수 있는 텍스트를 찾지 못했어요.";
        }
        if (value.contains("지원하지") || value.contains("unsupported")) {
            return "현재 지원하지 않는 파일 형식이에요.";
        }
        return "첨부파일을 다운로드하거나 읽지 못했어요.";
    }
}
