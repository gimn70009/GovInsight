package com.publicmonitor.backend.domain.settings.service;

import static org.assertj.core.api.Assertions.*;

import com.publicmonitor.backend.domain.settings.exception.EvaluationSettingsException;
import com.publicmonitor.backend.domain.settings.repository.*;
import com.publicmonitor.backend.domain.settings.web.dto.*;
import com.publicmonitor.backend.domain.settings.web.dto.EvaluationSettingsData.*;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:evaluation-settings;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.show-sql=false", "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, EvaluationSettingsService.class, EvaluationSettingsIntegrationTest.MapperConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EvaluationSettingsIntegrationTest {
    @Autowired EvaluationSettingsService service;
    @Autowired EvaluationSettingsRepository settings;
    @Autowired EvaluationSettingsRevisionRepository revisions;

    @TestConfiguration
    static class MapperConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
    }

    @BeforeEach void clear() { revisions.deleteAll(); settings.deleteAll(); }

    @Test void defaultsDoNotActivateBeforeFirstSave() {
        assertThat(service.find().revision()).isZero();
        assertThat(service.currentSnapshot()).isNull();
        assertThat(settings.count()).isZero();
        assertThat(service.find().catalog().get("industries")).extracting(EvaluationSettingsResponse.SelectionGroup::name)
                .containsExactly("전자·첨단제조", "기계·모빌리티");
    }

    @Test void committedUpdatesRetainImmutableSnapshotsAndNoOpDoesNotBumpVersion() {
        var initial = service.find().data();
        var first = service.update(new UpdateEvaluationSettingsRequest(0L, initial));
        assertThat(first.revision()).isEqualTo(1);
        assertThat(first.updatedAt()).isNotNull();
        assertThat(service.currentSnapshot().data()).isEqualTo(initial);
        assertThat(service.update(new UpdateEvaluationSettingsRequest(1L, initial)).revision()).isEqualTo(1);
        var changed = withName(initial, "새 회사");
        assertThat(service.update(new UpdateEvaluationSettingsRequest(1L, changed)).revision()).isEqualTo(2);
        assertThat(service.revision(1)).isEqualTo(initial);
        assertThat(service.revision(2)).isEqualTo(changed);
        assertThat(revisions.count()).isEqualTo(2);
        assertThat(service.find().data().company().name()).isEqualTo("새 회사");
    }

    @Test void staleEditorCannotOverwriteSavedConfiguration() {
        var initial = service.find().data();
        service.update(new UpdateEvaluationSettingsRequest(0L, initial));
        service.update(new UpdateEvaluationSettingsRequest(1L, withName(initial, "먼저 저장한 회사")));
        assertThatThrownBy(() -> service.update(new UpdateEvaluationSettingsRequest(1L, withName(initial, "오래된 화면"))))
                .isInstanceOf(EvaluationSettingsException.class);
        assertThat(service.find().data().company().name()).isEqualTo("먼저 저장한 회사");
        assertThat(revisions.count()).isEqualTo(2);
    }

    @Test void unknownAndDuplicateSelectionsAreRejectedWithoutSaving() {
        var initial = service.find().data();
        var c = initial.company();
        for (var industries : List.of(List.of("없는 분야"), List.of("반도체", "반도체"))) {
            var invalid = new EvaluationSettingsData(new Company(c.name(), c.type(), c.location(), c.establishedOn(),
                    c.description(), industries, c.technologies(), c.projects(), c.certifications()), initial.criteria());
            assertThatThrownBy(() -> service.update(new UpdateEvaluationSettingsRequest(0L, invalid)))
                    .isInstanceOf(EvaluationSettingsException.class);
        }
        assertThat(settings.count()).isZero();
    }

    @Test void unknownHistoricalRevisionIsNotSilentlyScoredWithCurrentSettings() {
        assertThat(service.revision(0)).isNull();
        assertThatThrownBy(() -> service.revision(99)).isInstanceOf(EvaluationSettingsException.class);
    }

    private EvaluationSettingsData withName(EvaluationSettingsData data, String name) {
        var c = data.company();
        return new EvaluationSettingsData(new Company(name, c.type(), c.location(), c.establishedOn(), c.description(),
                c.industries(), c.technologies(), c.projects(), c.certifications()), data.criteria());
    }
}
