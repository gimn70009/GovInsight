package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.analysis.event.CollectionStoredEvent;
import com.publicmonitor.backend.domain.document.entity.AttachmentParseStatus;
import com.publicmonitor.backend.domain.document.entity.Document;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.entity.DocumentChangeType;
import com.publicmonitor.backend.domain.document.entity.DocumentDetection;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.exception.CollectionResultException;
import com.publicmonitor.backend.domain.document.exception.CollectionResultResponseCode;
import com.publicmonitor.backend.domain.document.repository.DocumentAttachmentRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentDetectionRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentRepository;
import com.publicmonitor.backend.domain.document.repository.DocumentVersionRepository;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.CollectedAttachment;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.CollectedDocument;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.SourceResult;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultResponse;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultResponse.DocumentResult;
import com.publicmonitor.backend.domain.document.web.dto.CollectionSourceStatus;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunSourceRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CollectionResultService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern FILE_SIZE_SUFFIX = Pattern.compile(
            "(?i)\\s*(?:[\\[(]\\s*)?\\d+(?:[.,]\\d+)?\\s*(?:bytes?|kb|mb|gb)\\s*[\\])]?[\\s]*$"
    );
    private static final Pattern FILE_NAME_WITH_EXTENSION = Pattern.compile(
            "(?i)^(.+?\\.([a-z0-9]{1,10}))(?=\\s*(?:[\\[(]|$))"
    );
    private static final Pattern FILE_EXTENSION = Pattern.compile("(?i)\\.([a-z0-9]{1,10})$");

    private final MonitoringRunRepository monitoringRunRepository;
    private final MonitoringRunSourceRepository monitoringRunSourceRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository documentVersionRepository;
    private final DocumentAttachmentRepository documentAttachmentRepository;
    private final DocumentDetectionRepository documentDetectionRepository;
    private final DocumentContentNormalizer normalizer;
    private final DocumentVersionHasher hasher;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public CollectionResultResponse receive(CollectionResultRequest request) {
        MonitoringRun run = monitoringRunRepository.findById(request.runId())
                .orElseThrow(() -> new CollectionResultException(CollectionResultResponseCode.RUN_NOT_FOUND));
        if (!request.jobId().equals(run.getPythonJobId())) {
            throw new CollectionResultException(CollectionResultResponseCode.JOB_ID_MISMATCH);
        }

        LocalDateTime now = LocalDateTime.now(clock.withZone(SERVICE_ZONE));
        run.start(now);
        List<DocumentResult> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;
        int warningCount = 0;

        for (SourceResult sourceResult : request.sources()) {
            MonitoringRunSource runSource = monitoringRunSourceRepository
                    .findByMonitoringRunIdAndMonitoringSourceId(run.getId(), sourceResult.sourceId())
                    .orElseThrow(() -> new CollectionResultException(CollectionResultResponseCode.SOURCE_NOT_INCLUDED));

            if (sourceResult.status() == CollectionSourceStatus.FAILED) {
                runSource.fail(safeError(sourceResult.errorMessage()), now);
                failedCount++;
                warningCount++;
                continue;
            }

            int sourceWarningCount = Math.toIntExact(sourceResult.documents().stream()
                    .flatMap(document -> document.attachments().stream())
                    .filter(attachment -> attachment.parseStatus() == AttachmentParseStatus.FAILED)
                    .count());
            int displayOrder = 0;
            for (CollectedDocument collected : sourceResult.documents()) {
                results.add(saveDocument(runSource, collected, displayOrder++, now));
            }
            runSource.complete(sourceResult.documents().size(), sourceWarningCount, now);
            successCount++;
            warningCount += sourceWarningCount;
        }

        run.completeCollection(successCount, failedCount, results.size(), warningCount, now);
        eventPublisher.publishEvent(new CollectionStoredEvent(run.getId()));
        return new CollectionResultResponse(results);
    }

    private DocumentResult saveDocument(
            MonitoringRunSource runSource,
            CollectedDocument collected,
            int displayOrder,
            LocalDateTime detectedAt
    ) {
        String title = normalizer.normalize(collected.title());
        String content = normalizer.normalize(collected.contentText());
        List<CollectedAttachment> attachments = normalizeAttachments(collected.attachments());
        String versionHash = hasher.hash(title, content, attachments);

        Document document = documentRepository
                .findByMonitoringSourceIdAndOriginalUrl(runSource.getMonitoringSource().getId(), collected.originalUrl())
                .orElseGet(() -> documentRepository.save(Document.create(
                        runSource.getMonitoringSource(),
                        collected.originalUrl(),
                        collected.externalDocumentId(),
                        detectedAt
                )));
        document.markDetected(detectedAt);

        DocumentVersion latestVersion = documentVersionRepository
                .findTopByDocumentIdOrderByVersionNoDesc(document.getId())
                .orElse(null);
        DocumentChangeType changeType = determineChangeType(latestVersion, versionHash);
        DocumentVersion version = latestVersion;

        if (changeType != DocumentChangeType.UNCHANGED_DOCUMENT) {
            int versionNo = latestVersion == null ? 1 : latestVersion.getVersionNo() + 1;
            version = documentVersionRepository.save(DocumentVersion.create(
                    document,
                    versionNo,
                    title,
                    content.isEmpty() ? null : content,
                    versionHash,
                    collected.publishedAt(),
                    attachments.size(),
                    detectedAt
            ));
            DocumentVersion savedVersion = version;
            documentAttachmentRepository.saveAll(attachments.stream()
                    .map(attachment -> DocumentAttachment.create(
                            savedVersion,
                            attachment.fileName(),
                            attachment.downloadUrl(),
                            extensionOf(attachment.fileName()),
                            attachment.contentType(),
                            attachment.fileSize(),
                            attachment.fileHash(),
                            attachment.extractedText(),
                            attachment.parseStatus(),
                            attachment.errorMessage()
                    ))
                    .toList());
        } else {
            updateAttachmentMetadata(version, attachments);
        }

        if (!documentDetectionRepository.existsByMonitoringRunSourceIdAndDocumentId(runSource.getId(), document.getId())) {
            documentDetectionRepository.save(DocumentDetection.create(
                    runSource,
                    document,
                    version,
                    changeType,
                    displayOrder,
                    detectedAt
            ));
        }

        return new DocumentResult(
                collected.originalUrl(),
                document.getId(),
                version.getId(),
                changeType,
                changeType != DocumentChangeType.UNCHANGED_DOCUMENT
        );
    }

    private void updateAttachmentMetadata(
            DocumentVersion version,
            List<CollectedAttachment> collectedAttachments
    ) {
        documentAttachmentRepository.findAllByDocumentVersionId(version.getId()).forEach(savedAttachment ->
                collectedAttachments.stream()
                        .filter(collected -> collected.downloadUrl().equals(savedAttachment.getDownloadUrl()))
                        .findFirst()
                        .ifPresent(collected -> savedAttachment.updateDownloadMetadata(
                                collected.contentType(),
                                collected.fileSize(),
                                collected.fileHash(),
                                collected.extractedText(),
                                collected.parseStatus(),
                                collected.errorMessage()
                        ))
        );
    }
    private DocumentChangeType determineChangeType(DocumentVersion latestVersion, String versionHash) {
        if (latestVersion == null) {
            return DocumentChangeType.NEW_DOCUMENT;
        }
        return latestVersion.getVersionHash().equals(versionHash)
                ? DocumentChangeType.UNCHANGED_DOCUMENT
                : DocumentChangeType.UPDATED_DOCUMENT;
    }

    private List<CollectedAttachment> normalizeAttachments(List<CollectedAttachment> attachments) {
        return attachments.stream()
                .map(attachment -> new CollectedAttachment(
                        normalizeFileName(attachment.fileName()),
                        attachment.downloadUrl(),
                        attachment.contentType(),
                        attachment.fileSize(),
                        attachment.fileHash(),
                        attachment.extractedText(),
                        attachment.parseStatus(),
                        attachment.errorMessage()
                ))
                .toList();
    }

    private String normalizeFileName(String fileName) {
        String trimmed = fileName.strip();
        Matcher fileNameMatcher = FILE_NAME_WITH_EXTENSION.matcher(trimmed);
        if (fileNameMatcher.find()) {
            return fileNameMatcher.group(1).strip();
        }
        return FILE_SIZE_SUFFIX.matcher(trimmed).replaceFirst("").strip();
    }

    private String extensionOf(String fileName) {
        Matcher matcher = FILE_EXTENSION.matcher(fileName);
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    private String safeError(String errorMessage) {
        return errorMessage == null || errorMessage.isBlank() ? "문서 수집에 실패했습니다." : errorMessage;
    }
}
