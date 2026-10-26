package com.publicmonitor.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.publicmonitor.backend.domain.monitoring.service.MonitoringScheduleRunner;
import com.publicmonitor.backend.domain.report.repository.MonitoringReportRepository;
import com.publicmonitor.backend.domain.report.repository.ReportTaskRepository;
import com.publicmonitor.backend.domain.report.service.ReportPreparationService;
import com.publicmonitor.backend.domain.report.service.ReportRecoveryScheduler;
import com.publicmonitor.backend.domain.report.service.ReportTaskRunner;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class SchedulingIsolationTest {

    @Test
    void 보고서_복구가_기다리는_동안에도_실제_예약_메서드가_전용_스케줄러에서_실행된다() throws Exception {
        var recoveryEntered = new CountDownLatch(1);
        var releaseRecovery = new CountDownLatch(1);
        var monitoringEntered = new CountDownLatch(1);
        var recoveryThread = new AtomicReference<String>();
        var monitoringThread = new AtomicReference<String>();
        var tasks = mock(ReportTaskRepository.class);
        var reports = mock(MonitoringReportRepository.class);
        var reportRunner = mock(ReportTaskRunner.class);
        var monitoringRunner = mock(MonitoringScheduleRunner.class);
        when(reports.findUnqueued(any(Pageable.class))).thenReturn(List.of());
        when(tasks.findDue(any(LocalDateTime.class), any(Pageable.class))).thenReturn(List.of(7L));
        doAnswer(invocation -> {
            recoveryThread.set(Thread.currentThread().getName());
            recoveryEntered.countDown();
            releaseRecovery.await(10, TimeUnit.SECONDS);
            return null;
        }).when(reportRunner).run(7L);
        doAnswer(invocation -> {
            if (recoveryEntered.await(3, TimeUnit.SECONDS)) {
                monitoringThread.set(Thread.currentThread().getName());
                monitoringEntered.countDown();
            }
            return null;
        }).when(monitoringRunner).runIfDue();

        var context = new AnnotationConfigApplicationContext();
        ThreadPoolTaskScheduler recoveryScheduler = null;
        ThreadPoolTaskScheduler monitoringScheduler = null;
        try {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "scheduling-isolation", Map.of("app.report.recovery.initial-delay-ms", "0")));
            context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if ("monitoringScheduleScheduler".equals(beanName)) {
                        // Exercise the production cron just before its next scheduled tick.
                        ((ThreadPoolTaskScheduler) bean).setClock(Clock.fixed(
                                Instant.parse("2026-10-08T00:00:59.900Z"), ZoneOffset.UTC));
                    }
                    return bean;
                }
            });
            context.register(SchedulingConfig.class);
            context.registerBean("reportRecoveryScheduler", ReportRecoveryScheduler.class,
                    () -> new ReportRecoveryScheduler(tasks, reportRunner, reports,
                            mock(ReportPreparationService.class), Clock.systemUTC()));
            context.registerBean("monitoringScheduleRunner", MonitoringScheduleRunner.class,
                    () -> monitoringRunner);
            context.refresh();
            recoveryScheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            monitoringScheduler = context.getBean("monitoringScheduleScheduler", ThreadPoolTaskScheduler.class);

            assertThat(recoveryEntered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(monitoringEntered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseRecovery.getCount()).isEqualTo(1);
            assertThat(recoveryThread.get()).startsWith("recovery-scheduling-");
            assertThat(monitoringThread.get()).startsWith("monitoring-scheduling-");
            verify(monitoringRunner).runIfDue();
        } finally {
            releaseRecovery.countDown();
            context.close();
        }
        assertThat(recoveryScheduler.getScheduledThreadPoolExecutor().isTerminated()).isTrue();
        assertThat(monitoringScheduler.getScheduledThreadPoolExecutor().isTerminated()).isTrue();
    }
}
