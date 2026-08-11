package com.publicmonitor.backend.domain.monitoring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.exception.DuplicateMonitoringSourceException;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringSourceNotFoundException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringSourceRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringSourceRequest;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringSourceResponse;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonitoringSourceServiceTest {

    private static final String LIST_URL = "https://example.go.kr/notices";

    @Mock
    private MonitoringSourceRepository monitoringSourceRepository;

    private MonitoringSourceService monitoringSourceService;

    @BeforeEach
    void setUp() {
        monitoringSourceService = new MonitoringSourceService(monitoringSourceRepository);
    }

    @Test
    void 모니터링_소스를_등록한다() {
        CreateMonitoringSourceRequest request = createRequest(5, false);
        given(monitoringSourceRepository.existsByListUrl(LIST_URL)).willReturn(false);
        given(monitoringSourceRepository.save(any(MonitoringSource.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MonitoringSourceResponse response = monitoringSourceService.create(request);

        assertThat(response.organizationName()).isEqualTo("조달청");
        assertThat(response.listUrl()).isEqualTo(LIST_URL);
        assertThat(response.detailFetchCount()).isEqualTo(5);
        assertThat(response.enabled()).isFalse();
    }

    @Test
    void 상세_수집_수를_입력하지_않으면_기본값을_사용한다() {
        CreateMonitoringSourceRequest request = createRequest(null, null);
        given(monitoringSourceRepository.existsByListUrl(LIST_URL)).willReturn(false);
        given(monitoringSourceRepository.save(any(MonitoringSource.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        MonitoringSourceResponse response = monitoringSourceService.create(request);

        assertThat(response.detailFetchCount()).isEqualTo(MonitoringSource.DEFAULT_DETAIL_FETCH_COUNT);
        assertThat(response.enabled()).isTrue();
    }

    @Test
    void 동일한_목록_URL은_중복_등록할_수_없다() {
        CreateMonitoringSourceRequest request = createRequest(3, true);
        given(monitoringSourceRepository.existsByListUrl(LIST_URL)).willReturn(true);

        assertThatThrownBy(() -> monitoringSourceService.create(request))
                .isInstanceOf(DuplicateMonitoringSourceException.class);
    }

    @Test
    void 모니터링_소스_목록을_조회한다() {
        MonitoringSource source = createSource();
        given(monitoringSourceRepository.findAllByOrderByIdDesc()).willReturn(List.of(source));

        List<MonitoringSourceResponse> responses = monitoringSourceService.findAll();

        assertThat(responses).hasSize(1);
        assertThat(responses.getFirst().boardName()).isEqualTo("공지사항");
    }

    @Test
    void 존재하지_않는_모니터링_소스를_조회하면_실패한다() {
        given(monitoringSourceRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> monitoringSourceService.findById(1L))
                .isInstanceOf(MonitoringSourceNotFoundException.class);
    }

    private CreateMonitoringSourceRequest createRequest(Integer detailFetchCount, Boolean enabled) {
        return new CreateMonitoringSourceRequest(
                "조달청",
                "공지사항",
                "조달청 공지 모니터링",
                LIST_URL,
                "/notice/view",
                detailFetchCount,
                enabled
        );
    }

    private MonitoringSource createSource() {
        return MonitoringSource.create(
                "조달청",
                "공지사항",
                "조달청 공지 모니터링",
                LIST_URL,
                "/notice/view",
                3,
                true
        );
    }
}
