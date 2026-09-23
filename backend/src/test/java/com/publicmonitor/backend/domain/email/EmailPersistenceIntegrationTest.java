package com.publicmonitor.backend.domain.email;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.publicmonitor.backend.domain.email.entity.*;
import com.publicmonitor.backend.domain.email.repository.*;
import com.publicmonitor.backend.domain.email.service.*;
import com.publicmonitor.backend.domain.email.web.dto.*;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import com.publicmonitor.backend.domain.report.service.ReportDeliveryQueryService;
import com.publicmonitor.backend.domain.telegram.entity.*;
import com.publicmonitor.backend.domain.telegram.repository.TelegramDeliveryRepository;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
@DataJpaTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:email-delivery;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false", "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({com.publicmonitor.backend.domain.report.service.ReportDeliveryAttemptService.class, JpaAuditingConfig.class, EmailReportDeliveryService.class, EmailDeliveryWorker.class,
    EmailDeliveryPreparationService.class, EmailSettingsService.class, ReportDeliveryQueryService.class, EmailPersistenceIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EmailPersistenceIntegrationTest {
    @Autowired EntityManager em;
    @Autowired MonitoringReportRepository reports;
    @Autowired EmailDeliveryRepository deliveries;
    @Autowired TelegramDeliveryRepository telegram;
    @Autowired EmailSettingsRepository settings;
    @Autowired EmailSettingsService settingsService;
    @Autowired ReportDeliveryQueryService query;
    @Autowired EmailReportDeliveryService service;
    @Autowired EmailDeliveryPreparationService preparation;
    @Autowired EmailDeliveryWorker worker;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean EmailClient client;
    @TestConfiguration static class Config {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean EmailProperties emailProperties() { return new EmailProperties(EmailProperties.Provider.GMAIL, "sender@gmail.com", "test-only", "GovInsight"); }
        @Bean(destroyMethod = "close") ExecutorService emailDeliveryExecutor() { return Executors.newFixedThreadPool(3); }
    }
    @BeforeEach void clean() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { deliveries.deleteAllInBatch(); telegram.deleteAllInBatch(); reports.deleteAllInBatch(); settings.deleteAll(); em.flush(); });
        reset(client);
    }
    EmailRecipientRequest person(String address, boolean enabled) { return new EmailRecipientRequest(address, "담당자", enabled); }
    void recipients(EmailRecipientRequest... people) {
        var current = settingsService.find(); settingsService.update(new UpdateEmailSettingsRequest(current.version(), true, List.of(people)));
    }
    Long report() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, LocalDateTime.now()); em.persist(run);
            var report = MonitoringReport.pending(run); report.complete("테스트 보고서", "저장된 본문", LocalDateTime.now()); em.persist(report); em.flush(); return report.getId();
        });
    }
    @Test void settingsPersistNormalizeReorderAndRejectStaleWritesAndDuplicates() {
        recipients(person("A@gmail.com", true), person("b@naver.com", false));
        var before = settingsService.find();
        assertThat(before.recipients()).extracting(EmailRecipientRequest::address).containsExactly("a@gmail.com", "b@naver.com");
        var after = settingsService.update(new UpdateEmailSettingsRequest(before.version(), true, List.of(person("b@naver.com", false), person("a@gmail.com", true))));
        assertThat(settingsService.find().recipients()).extracting(EmailRecipientRequest::address).containsExactly("b@naver.com", "a@gmail.com");
        assertThatThrownBy(() -> settingsService.update(new UpdateEmailSettingsRequest(before.version(), false, List.of()))).hasMessageContaining("설정이 변경");
        assertThatThrownBy(() -> settingsService.update(new UpdateEmailSettingsRequest(after.version(), true, List.of(person("a@gmail.com", true), person("A@gmail.com", true))))).hasMessageContaining("한 번만");
    }
    @Test void partialFailureRetriesOnlyFailedAddressAndPreservesRecipientSnapshot() {
        recipients(person("a@gmail.com", true), person("b@naver.com", true), person("off@naver.com", false));
        Long id = report(); var runId = query.detail(id).report().runId();
        doThrow(new EmailClientException("테스트 실패")).when(client).send(eq("b@naver.com"), anyString(), anyString());
        service.deliver(runId);
        var detail = query.detail(id);
        assertThat(detail.report().email().status()).isEqualTo("PARTIAL");
        assertThat(detail.report().email().sentCount()).isEqualTo(1);
        assertThat(detail.emailDeliveries()).hasSize(2);
        verify(client, never()).send(eq("off@naver.com"), anyString(), anyString());
        var failed = detail.emailDeliveries().stream().filter(d -> d.status() == EmailDeliveryState.FAILED).findFirst().orElseThrow();
        doNothing().when(client).send(eq("b@naver.com"), anyString(), anyString());
        recipients(new EmailRecipientRequest("b@naver.com", "변경 이름", true), person("a@gmail.com", true));
        assertThat(service.retry(failed.deliveryId(), new EmailRetryRequest("b@naver.com", 1)).status()).isEqualTo(EmailDeliveryState.SENT);
        assertThatThrownBy(() -> service.retry(failed.deliveryId(), new EmailRetryRequest("b@naver.com", 1))).hasMessageContaining("이미 처리");
        service.deliver(runId);
        verify(client, times(1)).send("a@gmail.com", "테스트 보고서", "저장된 본문");
        verify(client, times(2)).send("b@naver.com", "테스트 보고서", "저장된 본문");
        assertThat(query.detail(id).emailDeliveries()).extracting(EmailRecipientDeliveryResponse::name).containsOnly("담당자");
    }
    @Test void concurrentCompletionEventsDoNotDuplicateEmails() throws Exception {
        recipients(person("a@gmail.com", true)); Long id = report(); Long run = query.detail(id).report().runId();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.deliver(run)); var second = pool.submit(() -> service.deliver(run));
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        verify(client, times(1)).send(eq("a@gmail.com"), anyString(), anyString());
    }
    @Test void disabledSettingsAndRemovedRecipientsPreventQueuedDelivery() {
        recipients(person("a@gmail.com", true)); Long id = report(); var ids = preparation.prepare(query.detail(id).report().runId());
        var current = settingsService.find(); settingsService.update(new UpdateEmailSettingsRequest(current.version(), false, current.recipients()));
        worker.sendPending(ids.getFirst());
        assertThat(query.detail(id).emailDeliveries().getFirst().status()).isEqualTo(EmailDeliveryState.FAILED); verifyNoInteractions(client);
        assertThatThrownBy(() -> service.retry(ids.getFirst(), new EmailRetryRequest("a@gmail.com", 1))).hasMessageContaining("꺼져");
        current = settingsService.find(); settingsService.update(new UpdateEmailSettingsRequest(current.version(), true, List.of()));
        assertThatThrownBy(() -> service.retry(ids.getFirst(), new EmailRetryRequest("a@gmail.com", 1))).hasMessageContaining("수신 대상");
    }
    @Test void unifiedHistoryFiltersChannelsStatusesDatesAndLegacyTelegram() {
        recipients(person("a@gmail.com", true)); Long id = report();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> { var r = reports.findById(id).orElseThrow(); r.completeTelegramDelivery(777L, LocalDateTime.now()); });
        doThrow(new EmailClientException("실패")).when(client).send(anyString(), anyString(), anyString());
        service.deliver(query.detail(id).report().runId());
        assertThat(query.list(0, 10, null, null, "TELEGRAM", "SENT").totalElements()).isEqualTo(1);
        assertThat(query.list(0, 10, null, null, "EMAIL", "SENT").totalElements()).isZero();
        assertThat(query.list(0, 10, null, null, "EMAIL", "FAILED").totalElements()).isEqualTo(1);
        assertThat(query.list(0, 10, null, null, "ALL", "FAILED").totalElements()).isEqualTo(1);
        assertThat(query.list(0, 10, LocalDate.now().plusDays(1), null, "ALL", null).totalElements()).isZero();
        assertThatThrownBy(() -> query.list(0, 10, LocalDate.now(), LocalDate.now().minusDays(1), "ALL", null)).hasMessageContaining("시작 날짜");
    }
    @Test void noRecipientsOrDisabledChannelNeverSend() {
        var current = settingsService.find();
        assertThat(current.enabled()).isFalse(); service.deliver(query.detail(report()).report().runId());
        recipients(person("off@gmail.com", false)); service.deliver(query.detail(report()).report().runId());
        verifyNoInteractions(client);
    }
    @Test void committedReservationSurvivesSendTransactionRollbackAndPreventsResend() {
        recipients(person("a@gmail.com", true));
        Long reportId = report();
        Long runId = new TransactionTemplate(transactions).execute(tx -> reports.findById(reportId).orElseThrow().getMonitoringRun().getId());
        Long deliveryId = preparation.prepare(runId).getFirst();
        doThrow(new IllegalStateException("DB outcome unavailable")).when(client).send(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> worker.sendPending(deliveryId)).isInstanceOf(IllegalStateException.class);
        var persisted = deliveries.findById(deliveryId).orElseThrow();
        assertThat(persisted.getAttemptCount()).isEqualTo(1);
        assertThat(persisted.getStatus()).isEqualTo(com.publicmonitor.backend.domain.email.entity.EmailDeliveryState.PENDING);
        worker.sendPending(deliveryId);
        verify(client, times(1)).send(anyString(), anyString(), anyString());
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            var d = deliveries.findById(deliveryId).orElseThrow();
            org.springframework.test.util.ReflectionTestUtils.setField(d, "attemptedAt", java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Seoul")).minusMinutes(3));
        });
        worker.sendPending(deliveryId);
        assertThat(deliveries.findById(deliveryId).orElseThrow().getStatus()).isEqualTo(com.publicmonitor.backend.domain.email.entity.EmailDeliveryState.FAILED);
        verify(client, times(1)).send(anyString(), anyString(), anyString());
    }

}
