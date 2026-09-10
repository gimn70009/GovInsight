package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.client.dto.PythonProposalWriteRequest;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.web.dto.ProposalSourceResponse;
import com.publicmonitor.backend.domain.document.web.dto.ProposalWriteRequest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.context.annotation.Lazy
@RequiredArgsConstructor
public class ProposalSourceService {
    private static final int MAX_TEMPLATE_CHARS = 80_000;
    private final DocumentDetectionRepository detections;
    private final DocumentAttachmentRepository attachments;

    @Transactional(readOnly = true)
    public List<ProposalSourceResponse> list(Long detectionId) {
        return sources(version(detectionId)).stream().map(Source::summary).toList();
    }

    @Transactional(readOnly = true)
    public Map<ProposalWriteRequest, ProposalSourceResponse> excludedFromDrafting(Long detectionId) {
        return sources(version(detectionId)).stream().filter(Source::excluded)
                .collect(Collectors.toMap(source -> new ProposalWriteRequest(
                        source.summary().attachmentId(), source.summary().partIndex()), Source::summary));
    }

    // Finish reading LOBs and close the transaction before waiting for the AI service.
    @Transactional(readOnly = true)
    public PythonProposalWriteRequest prepare(Long detectionId, ProposalWriteRequest request) {
        var version = version(detectionId);
        var files = attachments.findAllByDocumentVersionId(version.getId());
        var source = files.stream().flatMap(file -> split(file).stream())
                .filter(item -> item.summary().attachmentId().equals(request.attachmentId())
                        && item.summary().partIndex() == request.partIndex())
                .findFirst().orElseThrow(() -> new DocumentDetectionException(
                        DocumentDetectionResponseCode.PROPOSAL_SOURCE_NOT_FOUND));
        if (!source.summary().available()) {
            throw new DocumentDetectionException(DocumentDetectionResponseCode.PROPOSAL_SOURCE_UNAVAILABLE);
        }
        return new PythonProposalWriteRequest(version.getTitle(),
                noticeContext(version, files, request.attachmentId()), source.summary().fileName(), source.text());
    }

    private String noticeContext(DocumentVersion version, List<DocumentAttachment> files, Long selectedId) {
        var references = files.stream()
                .filter(file -> !file.getId().equals(selectedId)
                        && file.getParseStatus() == AttachmentParseStatus.COMPLETED
                        && file.getExtractedText() != null && !file.getExtractedText().isBlank()
                        && Pattern.compile("공고|RFP|제안요청|안내", Pattern.CASE_INSENSITIVE)
                                .matcher(file.getFileName()).find()
                        && !"zip".equalsIgnoreCase(file.getFileExtension()))
                .sorted(Comparator.comparing(DocumentAttachment::getId)).limit(3).toList();
        var text = new StringBuilder(excerpt(version.getContentText(), references.isEmpty() ? 16_000 : 6000));
        int perFile = references.isEmpty() ? 0 : (16_000 - text.length()) / references.size();
        for (var file : references) {
            String label = "\n[참고 공고 첨부: " + file.getFileName() + "]\n";
            text.append(label).append(excerpt(file.getExtractedText(), perFile - label.length()));
        }
        return text.toString();
    }

    private String excerpt(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        String separator = "\n[중간 본문 생략]\n";
        int half = (limit - separator.length()) / 2;
        return text.substring(0, half) + separator + text.substring(text.length() - half);
    }

    private DocumentVersion version(Long detectionId) {
        return detections.findById(detectionId).orElseThrow(() ->
                new DocumentDetectionException(DocumentDetectionResponseCode.NOT_FOUND)).getDocumentVersion();
    }

    private List<Source> sources(DocumentVersion version) {
        return attachments.findAllByDocumentVersionId(version.getId()).stream()
                .sorted(Comparator.comparing(DocumentAttachment::getId))
                .flatMap(file -> split(file).stream()).toList();
    }

    private List<Source> split(DocumentAttachment file) {
        String text = file.getExtractedText() == null ? "" : file.getExtractedText();
        if (!"zip".equalsIgnoreCase(file.getFileExtension())) {
            return List.of(source(file, 0, file.getFileName(), text));
        }
        var raw = ZipArchiveContent.parts(text);
        var manifest = ZipArchiveContent.entries(file.getArchiveEntriesJson());
        if (!manifest.isEmpty()) {
            var result = new ArrayList<Source>();
            for (int index = 0; index < raw.size(); index++) {
                final int position = index;
                var group = manifest.stream().filter(entry -> entry.partIndex() != null
                        && entry.partIndex() == position
                        && ("COMPLETED".equals(entry.status()) || "DUPLICATE".equals(entry.status()))).toList();
                if (group.isEmpty()) continue;
                var part = raw.get(index);
                var parsed = source(file, index, part.name(), part.text());
                var summary = parsed.summary();
                result.add(new Source(new ProposalSourceResponse(summary.attachmentId(), index,
                        summary.fileName(), summary.attachmentName(), summary.available(), summary.reason(),
                        group.size() > 1 ? group.stream().map(ZipArchiveContent.Entry::fileName).toList() : List.of()),
                        parsed.text(), parsed.excluded()));
            }
            int unavailableIndex = raw.size();
            for (var entry : manifest) {
                if (!"UNSUPPORTED".equals(entry.status()) && !"FAILED".equals(entry.status())) continue;
                String reason = "UNSUPPORTED".equals(entry.status())
                        ? "미지원 형식 · HWP, HWPX, PDF만 읽을 수 있어요." : "읽기 실패 · 파일 내용을 확인할 수 없어요.";
                result.add(new Source(new ProposalSourceResponse(file.getId(), unavailableIndex++, entry.fileName(),
                        file.getFileName(), false, reason), ""));
            }
            if (!result.isEmpty()) return result;
        }
        if (raw.isEmpty()) {
            return List.of(new Source(new ProposalSourceResponse(file.getId(), 0, file.getFileName(),
                    file.getFileName(), false, "ZIP 내부에서 읽을 수 있는 문서를 찾지 못했습니다."), ""));
        }
        var parts = new ArrayList<Source>();
        for (int index = 0; index < raw.size(); index++) {
            parts.add(source(file, index, raw.get(index).name(), raw.get(index).text()));
        }
        return parts;
    }

    private Source source(DocumentAttachment file, int index, String name, String text) {
        String reason = "";
        if (file.getParseStatus() != AttachmentParseStatus.COMPLETED || text.isBlank()) {
            reason = "첨부파일의 본문을 읽지 못해 초안을 작성할 수 없습니다.";
        } else if (text.length() > MAX_TEMPLATE_CHARS) {
            reason = "문서가 너무 길어 현재 초안 생성 범위(8만 자)를 초과합니다.";
        }
        String exclusion = text.isBlank() ? "" : ProposalDraftScope.exclusion(text);
        if (reason.isEmpty()) reason = exclusion;
        return new Source(new ProposalSourceResponse(file.getId(), index, name, file.getFileName(),
                reason.isEmpty(), reason), text, !exclusion.isEmpty());
    }

    private record Source(ProposalSourceResponse summary, String text, boolean excluded) {
        Source(ProposalSourceResponse summary, String text) { this(summary, text, false); }
    }
}
