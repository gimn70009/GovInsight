package com.publicmonitor.backend.domain.telegram;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.publicmonitor.backend.domain.monitoring.entity.*;
import com.publicmonitor.backend.domain.report.entity.MonitoringReport;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import com.publicmonitor.backend.domain.telegram.entity.*;
import com.publicmonitor.backend.domain.telegram.repository.*;
import com.publicmonitor.backend.domain.telegram.service.*;
import com.publicmonitor.backend.domain.telegram.web.dto.*;
import com.publicmonitor.backend.global.config.JpaAuditingConfig;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
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
    "spring.datasource.url=jdbc:h2:mem:telegram-multi;MODE=Oracle;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "app.local-admin.enabled=false", "app.monitoring.schedule.enabled=false", "spring.jpa.show-sql=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, TelegramReportDeliveryService.class, TelegramDeliveryWorker.class,
        TelegramDeliveryPreparationService.class, TelegramSettingsService.class, TelegramReportQueryService.class,
        TelegramPersistenceIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TelegramPersistenceIntegrationTest {
    @Autowired EntityManager em;
    @Autowired MonitoringReportRepository reports;
    @Autowired TelegramDeliveryRepository deliveries;
    @Autowired TelegramSettingsRepository settings;
    @Autowired TelegramSettingsService settingsService;
    @Autowired TelegramReportQueryService queries;
    @Autowired TelegramReportDeliveryService delivery;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean TelegramClient client;
    @TestConfiguration static class Config {
        @Bean Clock clock() { return Clock.systemUTC(); }
        @Bean TelegramProperties properties() { return new TelegramProperties(true, "test-token", "123", Duration.ofSeconds(1), Duration.ofSeconds(1), ""); }
        @Bean(destroyMethod = "close") ExecutorService telegramDeliveryExecutor() { return Executors.newFixedThreadPool(3); }
    }
    @BeforeEach void clean() {
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            deliveries.deleteAllInBatch(); reports.deleteAllInBatch(); settings.deleteAll(); em.flush();
        });
        reset(client);
    }
    void recipients(TelegramRecipientRequest... recipients) {
        var current = settingsService.find();
        settingsService.update(new UpdateTelegramSettingsRequest(current.version(), true, List.of(recipients)));
    }
    TelegramRecipientRequest person(String id, boolean enabled) { return new TelegramRecipientRequest(id, "이름 " + id, enabled); }

    @Test void 설정_재조회_순서_변경_삭제와_낙관적_충돌을_검증한다() {
        recipients(person("123", true), person("456", false), person("789", true));
        var before = settingsService.find();
        assertThat(before.recipients()).hasSize(3);
        var after = settingsService.update(new UpdateTelegramSettingsRequest(before.version(), true, List.of(person("789", true), person("123", true))));
        assertThat(settingsService.find().recipients()).extracting(TelegramRecipientRequest::chatId).containsExactly("789", "123");
        assertThat(after.version()).isGreaterThan(before.version());
        var swapped = settingsService.update(new UpdateTelegramSettingsRequest(after.version(), true,
                List.of(person("123", true), person("789", true))));
        assertThat(settingsService.find().recipients()).extracting(TelegramRecipientRequest::chatId).containsExactly("123", "789");
        assertThatThrownBy(() -> settingsService.update(new UpdateTelegramSettingsRequest(before.version(), false, List.of())))
                .hasMessageContaining("설정이 변경");
        settingsService.update(new UpdateTelegramSettingsRequest(swapped.version(), false, List.of()));
        assertThat(settingsService.find().recipients()).isEmpty();
    }

    @Test void 일부_실패를_격리하고_실패자만_재전송하며_이력_대상을_보존한다() {
        recipients(person("123", true), person("456", true), person("789", false));
        Long id = report("COMPLETED", LocalDate.of(2025, 1, 2));
        when(client.send(eq("123"), anyString())).thenThrow(new TelegramClientException("차단됨", null));
        when(client.send(eq("456"), anyString())).thenReturn(100L);
        delivery.deliver(queries.detail(id).report().runId());
        var detail = queries.detail(id);
        assertThat(detail.report().status()).isEqualTo(TelegramDeliveryStatus.PARTIAL);
        assertThat(detail.report().sentCount()).isEqualTo(1);
        assertThat(detail.deliveries()).hasSize(2);
        assertThat(queries.list(0, 10, LocalDate.of(2025, 1, 2), LocalDate.of(2025, 1, 2), TelegramDeliveryStatus.PARTIAL).totalElements()).isEqualTo(1);
        assertThat(queries.list(0, 10, null, null, TelegramDeliveryStatus.FAILED).totalElements()).isZero();
        var failed = detail.deliveries().stream().filter(d -> d.status() == TelegramDeliveryState.FAILED).findFirst().orElseThrow();
        when(client.send(eq("123"), anyString())).thenReturn(101L);
        recipients(new TelegramRecipientRequest("123", "새 이름", true), person("456", true), person("999", true));
        assertThat(delivery.retry(failed.deliveryId(), new TelegramRetryRequest("123", 1)).status()).isEqualTo(TelegramDeliveryState.SENT);
        delivery.deliver(detail.report().runId());
        verify(client, times(2)).send("123", "테스트 보고서\n\n저장된 본문");
        verify(client, times(1)).send(eq("456"), anyString());
        verify(client, never()).send(eq("789"), anyString());
        verify(client, never()).send(eq("999"), anyString());
        assertThat(queries.detail(id).report().status()).isEqualTo(TelegramDeliveryStatus.SENT);
        assertThat(queries.detail(id).deliveries()).extracting(TelegramRecipientDeliveryResponse::name).contains("이름 123");
    }

    @Test void 동시에_같은_실패를_재전송해도_한번만_보낸다() throws Exception {
        Long id = report("COMPLETED", LocalDate.of(2025, 2, 2));
        Long targetId = failedTarget(id);
        var sending = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(client.send(anyString(), anyString())).thenAnswer(invocation -> {
            sending.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); return 77L;
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> delivery.retry(targetId, new TelegramRetryRequest("123", 1)));
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> delivery.retry(targetId, new TelegramRetryRequest("123", 1)));
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo(TelegramDeliveryState.SENT);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(com.publicmonitor.backend.domain.telegram.exception.TelegramException.class);
        } finally { release.countDown(); }
        verify(client, times(1)).send(anyString(), anyString());
        assertThat(queries.detail(id).deliveries().getFirst().attemptCount()).isEqualTo(2);
    }

    @Test void 중복_완료_이벤트에서도_대상별_한번만_병렬_발송한다() throws Exception {
        recipients(person("123", true), person("456", true));
        Long id = report("COMPLETED", LocalDate.of(2025, 3, 2));
        Long runId = queries.detail(id).report().runId();
        var arrived = new CountDownLatch(2);
        var active = new AtomicInteger(); var maxActive = new AtomicInteger();
        when(client.send(anyString(), anyString())).thenAnswer(invocation -> {
            int n = active.incrementAndGet(); maxActive.accumulateAndGet(n, Math::max); arrived.countDown();
            try { assertThat(arrived.await(5, TimeUnit.SECONDS)).isTrue(); return 88L; }
            finally { active.decrementAndGet(); }
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> delivery.deliver(runId));
            var second = executor.submit(() -> delivery.deliver(runId));
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        assertThat(maxActive.get()).isBetween(2, 3);
        verify(client, times(1)).send(eq("123"), anyString()); verify(client, times(1)).send(eq("456"), anyString());
        assertThat(queries.detail(id).report().sentCount()).isEqualTo(2);
    }

    @Test void 재전송_실패도_커밋되고_삭제된_수신자는_거절한다() {
        Long id = report("COMPLETED", LocalDate.of(2025, 4, 2));
        Long targetId = failedTarget(id);
        when(client.send(anyString(), anyString())).thenThrow(new TelegramClientException("권한 없음", null));
        assertThat(delivery.retry(targetId, new TelegramRetryRequest("123", 1)).status()).isEqualTo(TelegramDeliveryState.FAILED);
        assertThat(queries.detail(id).deliveries().getFirst().attemptCount()).isEqualTo(2);
        recipients(person("456", true));
        assertThatThrownBy(() -> delivery.retry(targetId, new TelegramRetryRequest("123", 2))).hasMessageContaining("수신 대상");
        verify(client, times(1)).send(anyString(), anyString());
    }

    @Test void 기존_수신자_미기록_이력과_날짜_페이징을_보존한다() {
        Long oldSent = report("SENT", LocalDate.of(2025, 5, 2));
        report("PREPARING", LocalDate.of(2025, 5, 2));
        report("FAILED", LocalDate.of(2025, 5, 3));
        var result = queries.list(0, 1, LocalDate.of(2025, 5, 2), LocalDate.of(2025, 5, 2), null);
        assertThat(result.totalElements()).isEqualTo(2); assertThat(result.totalPages()).isEqualTo(2);
        assertThat(queries.detail(oldSent).deliveries()).isEmpty();
        assertThat(queries.detail(oldSent).report().status()).isEqualTo(TelegramDeliveryStatus.SENT);
        delivery.deliver(queries.detail(oldSent).report().runId()); verifyNoInteractions(client);
        assertThatThrownBy(() -> queries.list(0, 10, LocalDate.of(2025, 6, 2), LocalDate.of(2025, 5, 2), null)).hasMessageContaining("시작 날짜");
    }

    @Test void 전체_발송_중지에서는_자동_대상도_만들지_않는다() {
        settingsService.update(new UpdateTelegramSettingsRequest(null, false, List.of(person("123", true))));
        Long id = report("COMPLETED", LocalDate.of(2025, 6, 2));
        delivery.deliver(queries.detail(id).report().runId());
        assertThat(queries.detail(id).deliveries()).isEmpty(); verifyNoInteractions(client);
    }

    @Test void 마지막_수신자를_껐다_켜면_다음_보고서부터_그_사람에게만_보낸다() {
        var off = settingsService.update(new UpdateTelegramSettingsRequest(null, true,
                List.of(person("123", false), person("456", false))));
        Long skipped = report("COMPLETED", LocalDate.of(2025, 7, 2));
        delivery.deliver(queries.detail(skipped).report().runId());
        assertThat(settingsService.find().enabled()).isTrue();
        assertThat(queries.detail(skipped).deliveries()).isEmpty();
        verifyNoInteractions(client);
        settingsService.update(new UpdateTelegramSettingsRequest(off.version(), true,
                List.of(person("123", true), person("456", false))));
        when(client.send(eq("123"), anyString())).thenReturn(1234L);
        Long nextReport = report("COMPLETED", LocalDate.of(2025, 7, 3));
        delivery.deliver(queries.detail(nextReport).report().runId());
        verify(client, times(1)).send(eq("123"), anyString());
        verify(client, never()).send(eq("456"), anyString());
        assertThat(queries.detail(nextReport).report().status()).isEqualTo(TelegramDeliveryStatus.SENT);
    }

    private Long failedTarget(Long id) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var d = TelegramDelivery.pending(reports.findById(id).orElseThrow(), new TelegramRecipient("123", "원래 이름", true));
            d.begin(LocalDateTime.now()); d.fail("이전 실패");
            return deliveries.saveAndFlush(d).getId();
        });
    }
    private Long report(String state, LocalDate date) {
        return new TransactionTemplate(transactions).execute(tx -> {
            var run = MonitoringRun.create(MonitoringTriggerType.MANUAL, 1, date.atStartOfDay()); em.persist(run);
            var report = MonitoringReport.pending(run);
            if (!state.equals("PREPARING")) report.complete("테스트 보고서", "저장된 본문", date.atStartOfDay());
            if (state.equals("FAILED")) report.failTelegramDelivery("기존 실패");
            if (state.equals("SENT")) report.completeTelegramDelivery(777L, date.atStartOfDay());
            em.persist(report); em.flush();
            em.createQuery("update MonitoringReport r set r.createdAt = :at where r.id = :id")
                    .setParameter("at", date.atTime(12, 0)).setParameter("id", report.getId()).executeUpdate();
            return report.getId();
        });
    }
}
