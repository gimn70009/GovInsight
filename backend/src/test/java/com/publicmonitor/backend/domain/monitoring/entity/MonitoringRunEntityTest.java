package com.publicmonitor.backend.domain.monitoring.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MonitoringRunEntityTest {

    @Test
    void createMonitoringRunInitializesRequestedStateAndCounts() {
        LocalDateTime requestedAt = LocalDateTime.of(2026, 8, 12, 9, 0);

        MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 2, requestedAt);

        assertThat(run.getStatus()).isEqualTo(MonitoringRunStatus.REQUESTED);
        assertThat(run.getTriggerType()).isEqualTo(MonitoringTriggerType.MANUAL);
        assertThat(run.getPythonJobId()).isNull();
        assertThat(run.getTotalSourceCount()).isEqualTo(2);
        assertThat(run.getSuccessSourceCount()).isZero();
        assertThat(run.getFailedSourceCount()).isZero();
        assertThat(run.getDetectedDocumentCount()).isZero();
        assertThat(run.getWarningCount()).isZero();
        assertThat(run.getRequestedAt()).isEqualTo(requestedAt);
    }

    @Test
    void monitoringRunEnumsAreStoredAsStrings() throws NoSuchFieldException {
        assertStringEnum(MonitoringRun.class.getDeclaredField("status"));
        assertStringEnum(MonitoringRun.class.getDeclaredField("triggerType"));
        assertStringEnum(MonitoringRunSource.class.getDeclaredField("status"));
        assertStringEnum(MonitoringRunSource.class.getDeclaredField("processingMode"));
    }

    @Test
    void createMonitoringRunSourceInitializesPendingStateAndNormalMode() {
        MonitoringRun run = MonitoringRun.create(MonitoringTriggerType.SCHEDULED, 1, LocalDateTime.now());
        MonitoringSource source = MonitoringSource.create(
                "환경부",
                "공지사항",
                null,
                "https://example.com/notices",
                null,
                3,
                true
        );

        MonitoringRunSource runSource = MonitoringRunSource.create(run, source);

        assertThat(runSource.getMonitoringRun()).isSameAs(run);
        assertThat(runSource.getMonitoringSource()).isSameAs(source);
        assertThat(runSource.getStatus()).isEqualTo(MonitoringRunSourceStatus.PENDING);
        assertThat(runSource.getProcessingMode()).isEqualTo(MonitoringProcessingMode.NORMAL);
        assertThat(runSource.getDetectedDocumentCount()).isZero();
        assertThat(runSource.getWarningCount()).isZero();
    }

    @Test
    void monitoringRunSourceUsesLazyManyToOneRelationships() throws NoSuchFieldException {
        assertLazyManyToOne(MonitoringRunSource.class.getDeclaredField("monitoringRun"));
        assertLazyManyToOne(MonitoringRunSource.class.getDeclaredField("monitoringSource"));
    }

    @Test
    void monitoringRunSourceDeclaresRunAndSourceCompositeUniqueConstraint() {
        Table table = MonitoringRunSource.class.getAnnotation(Table.class);

        boolean hasCompositeConstraint = Arrays.stream(table.uniqueConstraints())
                .anyMatch(constraint -> Arrays.equals(
                        constraint.columnNames(),
                        new String[]{"run_id", "source_id"}
                ));

        assertThat(hasCompositeConstraint).isTrue();
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
}
