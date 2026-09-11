package com.publicmonitor.backend.domain.monitoring.repository;

import static org.assertj.core.api.Assertions.*;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.SourceResult;
import com.publicmonitor.backend.domain.document.web.dto.CollectionSourceStatus;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.monitoring.service.MonitoringWarningDetails;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:run-warnings;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false", "app.telegram.commands.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JpaAuditingConfig.class)
class MonitoringRunWarningPersistenceTest {
    @Autowired EntityManager em;
    @Autowired MonitoringRunSourceRepository sources;

    @Test
    void 실행별로_CLOB_경고를_복원하고_원래_기관명과_과거_빈_상세를_보존한다() {
        var now = LocalDateTime.of(2026, 9, 10, 12, 0);
        var board = MonitoringSource.create("수집 당시 기관", "게시판", null, "https://example.org", null, 3, true);
        em.persist(board);
        var first = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now); em.persist(first);
        var second = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, now.plusDays(1)); em.persist(second);
        var failed = MonitoringRunSource.create(first, board);
        failed.fail("TimeoutError", now);
        MonitoringWarningDetails.record(failed, new SourceResult(board.getId(), CollectionSourceStatus.FAILED, "TimeoutError", List.of()));
        em.persist(failed);
        var legacy = MonitoringRunSource.create(second, board); legacy.complete(1, 2, now); em.persist(legacy);
        board.update("변경된 기관", "새 게시판", null, "https://example.org", null, 3, true);
        em.flush(); em.clear();

        var restored = sources.findWithSourcesByMonitoringRunId(first.getId());
        assertThat(restored).hasSize(1);
        em.clear(); // Fetch join must leave the source usable outside the persistence context.
        assertThat(MonitoringWarningDetails.read(restored.getFirst()).getFirst().organizationName()).isEqualTo("수집 당시 기관");
        var old = sources.findWithSourcesByMonitoringRunId(second.getId()).getFirst();
        assertThat(old.getWarningDetailsJson()).isNull();
        em.clear();
        assertThat(MonitoringWarningDetails.read(old).getFirst().count()).isEqualTo(2);
        assertThat(MonitoringWarningDetails.read(old).getFirst().organizationName()).isEqualTo("변경된 기관");
    }
}
