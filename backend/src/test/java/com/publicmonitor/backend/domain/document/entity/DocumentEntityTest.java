package com.publicmonitor.backend.domain.document.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class DocumentEntityTest {

    @Test
    void createDocumentInitializesDetectionTimes() {
        MonitoringSource source = createSource();
        LocalDateTime detectedAt = LocalDateTime.of(2026, 8, 18, 9, 0);

        Document document = Document.create(
                source,
                "https://example.go.kr/notices/1",
                "1",
                detectedAt
        );

        assertThat(document.getMonitoringSource()).isSameAs(source);
        assertThat(document.getOriginalUrl()).isEqualTo("https://example.go.kr/notices/1");
        assertThat(document.getExternalDocumentId()).isEqualTo("1");
        assertThat(document.getFirstDetectedAt()).isEqualTo(detectedAt);
        assertThat(document.getLastDetectedAt()).isEqualTo(detectedAt);
    }

    @Test
    void markDetectedUpdatesOnlyLastDetectionTime() {
        LocalDateTime firstDetectedAt = LocalDateTime.of(2026, 8, 18, 9, 0);
        LocalDateTime lastDetectedAt = LocalDateTime.of(2026, 8, 18, 10, 0);
        Document document = Document.create(
                createSource(),
                "https://example.go.kr/notices/1",
                "1",
                firstDetectedAt
        );

        document.markDetected(lastDetectedAt);

        assertThat(document.getFirstDetectedAt()).isEqualTo(firstDetectedAt);
        assertThat(document.getLastDetectedAt()).isEqualTo(lastDetectedAt);
    }

    @Test
    void createDocumentVersionStoresCollectedContent() {
        Document document = createDocument();
        LocalDateTime publishedAt = LocalDateTime.of(2026, 8, 17, 9, 0);
        LocalDateTime collectedAt = LocalDateTime.of(2026, 8, 18, 9, 0);

        DocumentVersion version = DocumentVersion.create(
                document,
                1,
                "지원사업 공고",
                "지원사업 본문",
                "a".repeat(64),
                publishedAt,
                2,
                collectedAt
        );

        assertThat(version.getDocument()).isSameAs(document);
        assertThat(version.getVersionNo()).isEqualTo(1);
        assertThat(version.getTitle()).isEqualTo("지원사업 공고");
        assertThat(version.getContentText()).isEqualTo("지원사업 본문");
        assertThat(version.getPublishedAt()).isEqualTo(publishedAt);
        assertThat(version.getAttachmentCount()).isEqualTo(2);
        assertThat(version.getCollectedAt()).isEqualTo(collectedAt);
    }

    @Test
    void createAttachmentInitializesPendingState() {
        DocumentVersion version = createVersion();

        DocumentAttachment attachment = DocumentAttachment.create(
                version,
                "공고문.pdf",
                "https://example.go.kr/files/1",
                "pdf",
                "application/pdf",
                1024L,
                null
        );

        assertThat(attachment.getDocumentVersion()).isSameAs(version);
        assertThat(attachment.getParseStatus()).isEqualTo(AttachmentParseStatus.PENDING);
        assertThat(attachment.getExtractedText()).isNull();
        assertThat(attachment.getErrorMessage()).isNull();
    }

    @Test
    void createDetectionConnectsRunSourceDocumentAndVersion() {
        MonitoringSource source = createSource();
        MonitoringRun run = MonitoringRun.create(
                MonitoringTriggerType.MANUAL,
                1,
                LocalDateTime.of(2026, 8, 18, 9, 0)
        );
        MonitoringRunSource runSource = MonitoringRunSource.create(run, source);
        Document document = Document.create(
                source,
                "https://example.go.kr/notices/1",
                "1",
                LocalDateTime.of(2026, 8, 18, 9, 1)
        );
        DocumentVersion version = createVersion(document);
        LocalDateTime detectedAt = LocalDateTime.of(2026, 8, 18, 9, 2);

        DocumentDetection detection = DocumentDetection.create(
                runSource,
                document,
                version,
                DocumentChangeType.NEW_DOCUMENT,
                detectedAt
        );

        assertThat(detection.getMonitoringRunSource()).isSameAs(runSource);
        assertThat(detection.getDocument()).isSameAs(document);
        assertThat(detection.getDocumentVersion()).isSameAs(version);
        assertThat(detection.getChangeType()).isEqualTo(DocumentChangeType.NEW_DOCUMENT);
        assertThat(detection.getDetectedAt()).isEqualTo(detectedAt);
    }

    @Test
    void documentRelationshipsUseLazyManyToOne() throws NoSuchFieldException {
        assertLazyManyToOne(Document.class.getDeclaredField("monitoringSource"));
        assertLazyManyToOne(DocumentVersion.class.getDeclaredField("document"));
        assertLazyManyToOne(DocumentAttachment.class.getDeclaredField("documentVersion"));
        assertLazyManyToOne(DocumentDetection.class.getDeclaredField("monitoringRunSource"));
        assertLazyManyToOne(DocumentDetection.class.getDeclaredField("document"));
        assertLazyManyToOne(DocumentDetection.class.getDeclaredField("documentVersion"));
    }

    @Test
    void documentEnumsAreStoredAsStrings() throws NoSuchFieldException {
        assertStringEnum(DocumentAttachment.class.getDeclaredField("parseStatus"));
        assertStringEnum(DocumentDetection.class.getDeclaredField("changeType"));
    }

    @Test
    void documentTablesDeclareRequiredCompositeUniqueConstraints() {
        assertCompositeUniqueConstraint(Document.class, "source_id", "original_url");
        assertCompositeUniqueConstraint(DocumentVersion.class, "document_id", "version_no");
        assertCompositeUniqueConstraint(DocumentAttachment.class, "version_id", "download_url");
        assertCompositeUniqueConstraint(DocumentDetection.class, "run_source_id", "document_id");
    }

    private MonitoringSource createSource() {
        return MonitoringSource.create(
                "산업통상부",
                "사업공고",
                null,
                "https://example.go.kr/notices",
                "/notices/",
                3,
                true
        );
    }

    private Document createDocument() {
        return Document.create(
                createSource(),
                "https://example.go.kr/notices/1",
                "1",
                LocalDateTime.of(2026, 8, 18, 9, 0)
        );
    }

    private DocumentVersion createVersion() {
        return createVersion(createDocument());
    }

    private DocumentVersion createVersion(Document document) {
        return DocumentVersion.create(
                document,
                1,
                "지원사업 공고",
                "지원사업 본문",
                "a".repeat(64),
                LocalDateTime.of(2026, 8, 17, 9, 0),
                1,
                LocalDateTime.of(2026, 8, 18, 9, 0)
        );
    }

    private void assertStringEnum(Field field) {
        Enumerated enumerated = field.getAnnotation(Enumerated.class);
        assertThat(enumerated).isNotNull();
        assertThat(enumerated.value()).isEqualTo(EnumType.STRING);
    }

    private void assertLazyManyToOne(Field field) {
        ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
        assertThat(manyToOne).isNotNull();
        assertThat(manyToOne.fetch()).isEqualTo(FetchType.LAZY);
        assertThat(manyToOne.optional()).isFalse();
    }

    private void assertCompositeUniqueConstraint(Class<?> entityType, String... columnNames) {
        Table table = entityType.getAnnotation(Table.class);
        boolean hasConstraint = Arrays.stream(table.uniqueConstraints())
                .anyMatch(constraint -> Arrays.equals(constraint.columnNames(), columnNames));

        assertThat(hasConstraint).isTrue();
    }
}
