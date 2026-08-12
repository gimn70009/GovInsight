package com.publicmonitor.backend.domain.monitoring.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.publicmonitor.backend.domain.monitoring.client.dto.PythonMonitoringJobResponse;
import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class PythonMonitoringClientTest {

    private MockRestServiceServer server;
    private PythonMonitoringClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:8000");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PythonMonitoringClient(builder.build());
    }

    @Test
    void sendsMonitoringJobAndReadsAcceptedResponse() {
        MonitoringSource source = createSource();
        server.expect(once(), requestTo("http://localhost:8000/internal/monitoring/jobs"))
                .andExpect(method(POST))
                .andExpect(content().json("""
                        {
                          "runId": 5,
                          "sources": [
                            {
                              "sourceId": 1,
                              "organizationName": "서울시",
                              "boardName": "공지사항",
                              "listUrl": "https://example.com/notices",
                              "urlIncludePattern": "/notice/",
                              "detailFetchCount": 3
                            }
                          ]
                        }
                        """))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "jobId": "3ed1132b-8d61-45d9-bfab-06c1ed96f202",
                                  "status": "ACCEPTED"
                                }
                                """));

        PythonMonitoringJobResponse response = client.accept(5L, List.of(source));

        assertThat(response.jobId().toString())
                .isEqualTo("3ed1132b-8d61-45d9-bfab-06c1ed96f202");
        assertThat(response.status()).isEqualTo(PythonMonitoringJobStatus.ACCEPTED);
        server.verify();
    }

    @Test
    void rejectsNonAcceptedResponse() {
        server.expect(requestTo("http://localhost:8000/internal/monitoring/jobs"))
                .andRespond(withStatus(HttpStatus.OK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("""
                                {
                                  "jobId": "3ed1132b-8d61-45d9-bfab-06c1ed96f202",
                                  "status": "ACCEPTED"
                                }
                                """));

        assertThatThrownBy(() -> client.accept(5L, List.of(createSource())))
                .isInstanceOf(PythonMonitoringClientException.class);
        server.verify();
    }

    private MonitoringSource createSource() {
        MonitoringSource source = MonitoringSource.create(
                "서울시",
                "공지사항",
                null,
                "https://example.com/notices",
                "/notice/",
                3,
                true
        );
        ReflectionTestUtils.setField(source, "id", 1L);
        return source;
    }
}
