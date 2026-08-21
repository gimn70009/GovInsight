package com.publicmonitor.backend;

import com.publicmonitor.backend.domain.analysis.service.AnalysisJobRequestService;
import com.publicmonitor.backend.domain.analysis.service.AnalysisResultService;
import com.publicmonitor.backend.domain.document.service.CollectionResultService;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringSourceRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunSourceRepository;
import com.publicmonitor.backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
		"spring.autoconfigure.exclude="
				+ "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration,"
				+ "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
		"app.jwt.secret=VGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS10aGF0LWlzLWxvbmc=",
		"app.jwt.access-token-expiration=1h",
		"app.local-admin.enabled=false",
		"app.jpa-auditing.enabled=false"
})
class BackendApplicationTests {

    @MockitoBean
    private CollectionResultService collectionResultService;

    @MockitoBean
    private AnalysisJobRequestService analysisJobRequestService;

    @MockitoBean
    private AnalysisResultService analysisResultService;

	@MockitoBean
	UserRepository userRepository;

	@MockitoBean
	MonitoringSourceRepository monitoringSourceRepository;

	@MockitoBean
	MonitoringRunRepository monitoringRunRepository;

	@MockitoBean
	MonitoringRunSourceRepository monitoringRunSourceRepository;

	@Test
	void contextLoads() {
	}

}
