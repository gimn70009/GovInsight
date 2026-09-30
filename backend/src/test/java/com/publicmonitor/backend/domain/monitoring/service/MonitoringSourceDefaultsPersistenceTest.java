package com.publicmonitor.backend.domain.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringSourceNotFoundException;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRun;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringRunSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringTriggerType;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringSourceRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.UpdateMonitoringSourceSettingsRequest;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:source-defaults;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false", "app.telegram.commands.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, MonitoringSourceService.class})
class MonitoringSourceDefaultsPersistenceTest {
    @Autowired MonitoringSourceRepository repository;
    @Autowired MonitoringSourceService service;
    @Autowired EntityManager em;

    @Test
    void 빈_DB에_지정한_여섯_게시판을_최근_2건_활성으로_등록한다() {
        initialize();
        em.flush();
        em.clear();

        var sources = repository.findAll();
        assertThat(sources).hasSize(6);
        assertThat(sources).extracting(MonitoringSource::getListUrl).containsExactlyInAnyOrder(
                "https://www.motir.go.kr/kor/article/ATCL2826a2625",
                "https://www.msit.go.kr/bbs/list.do?sCode=user&mId=311&mPid=121&pageIndex=1&bbsSeqNo=100",
                "https://mcee.go.kr/home/web/board/list.do?menuId=10524&boardMasterId=39",
                "https://www.moel.go.kr/info/govsupport/govsupportcon/govSupportSubList.do",
                "https://www.molit.go.kr/USR/BORD0201/m_69/LST.jsp?id=N01_B",
                "https://www.kiat.or.kr/front/board/boardContentsListPage.do?MenuId=b159c9dac684471b87256f1e25404f5e&board_id=90"
        );
        var detailUrls = Map.of(
                "산업통상부", "https://www.motir.go.kr/kor/article/ATCL2826a2625/71324/view",
                "과학기술정보통신부", "https://www.msit.go.kr/bbs/view.do?nttSeqNo=3186858",
                "기후에너지환경부", "https://mcee.go.kr/home/web/board/read.do?boardId=1891560",
                "고용노동부", "https://www.moel.go.kr/info/govsupport/govsupportcon/govSupportSubView.do?bbs_seq=12345",
                "국토교통부", "https://www.molit.go.kr/USR/BORD0201/m_69/DTL.jsp?id=N01_B&idx=270001",
                "한국산업기술진흥원", "https://www.kiat.or.kr/front/board/boardContentsView.do?contents_id=6cbbabc261de4a2dabe7fc1fdb226da2"
        );
        assertThat(sources).allSatisfy(source -> {
            assertThat(source.getDetailFetchCount()).isEqualTo(2);
            assertThat(source.isEnabled()).isTrue();
            assertThat(source.getUrlIncludePattern()).isNotBlank();
            assertThat(detailUrls.get(source.getOrganizationName())).contains(source.getUrlIncludePattern());
        });
    }

    @Test
    void 반복_초기화에도_기존_설정과_식별자와_실행_이력을_보존한다() {
        var kiat = MonitoringSourceDefaults.SOURCES.getLast().create();
        kiat.changeCollectionCount(7);
        kiat.changeEnabled(false);
        repository.saveAndFlush(kiat);
        em.refresh(kiat);
        var originalId = kiat.getId();
        var originalUpdatedAt = kiat.getUpdatedAt();
        var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, LocalDateTime.now());
        em.persist(run);
        var runSource = MonitoringRunSource.create(run, kiat);
        em.persist(runSource);

        initialize();
        initialize();
        em.flush();
        em.clear();

        assertThat(repository.count()).isEqualTo(6);
        var restored = repository.findById(originalId).orElseThrow();
        assertThat(restored.getDetailFetchCount()).isEqualTo(7);
        assertThat(restored.isEnabled()).isFalse();
        assertThat(restored.getUpdatedAt()).isEqualTo(originalUpdatedAt);
        assertThat(em.find(MonitoringRunSource.class, runSource.getId()).getMonitoringSource().getId()).isEqualTo(originalId);
    }

    @Test
    void 기본_기관_이외의_기존_소스도_삭제하거나_비활성화하지_않는다() {
        var legacy = repository.save(MonitoringSource.create("기존 기관", "기존 게시판", null,
                "https://example.org/legacy", "/detail", 4, true));
        initialize();
        assertThat(repository.count()).isEqualTo(7);
        assertThat(repository.findById(legacy.getId()).orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void 여러_기관의_설정을_저장하고_URL과_수집규칙을_보존한다() {
        initialize();
        var sources = repository.findAll();
        var first = sources.getFirst();
        var second = sources.get(1);
        var url = first.getListUrl();
        var pattern = first.getUrlIncludePattern();
        service.updateSettings(new UpdateMonitoringSourceSettingsRequest(List.of(
                new UpdateMonitoringSourceSettingsRequest.SourceSetting(first.getId(), 7, false),
                new UpdateMonitoringSourceSettingsRequest.SourceSetting(second.getId(), 5, true))));
        em.clear();
        initialize();
        var restored = repository.findById(first.getId()).orElseThrow();
        assertThat(restored.getDetailFetchCount()).isEqualTo(7);
        assertThat(restored.isEnabled()).isFalse();
        assertThat(restored.getListUrl()).isEqualTo(url);
        assertThat(restored.getUrlIncludePattern()).isEqualTo(pattern);
        assertThat(repository.findById(second.getId()).orElseThrow().getDetailFetchCount()).isEqualTo(5);
        assertThat(repository.findById(sources.get(2).getId()).orElseThrow().getDetailFetchCount()).isEqualTo(2);
    }

    @Test
    void 일부_기관이_없으면_다른_기관에도_변경사항을_적용하지_않는다() {
        initialize();
        var first = repository.findAll().getFirst();
        var request = new UpdateMonitoringSourceSettingsRequest(List.of(
                new UpdateMonitoringSourceSettingsRequest.SourceSetting(first.getId(), 9, false),
                new UpdateMonitoringSourceSettingsRequest.SourceSetting(Long.MAX_VALUE, 4, true)));
        assertThatThrownBy(() -> service.updateSettings(request)).isInstanceOf(MonitoringSourceNotFoundException.class);
        em.flush();
        em.clear();
        var restored = repository.findById(first.getId()).orElseThrow();
        assertThat(restored.getDetailFetchCount()).isEqualTo(2);
        assertThat(restored.isEnabled()).isTrue();
    }

    private void initialize() {
        new MonitoringSourceInitializer(service).run(new DefaultApplicationArguments());
    }
}
