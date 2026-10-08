package com.publicmonitor.backend.domain.monitoring.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.publicmonitor.backend.domain.monitoring.exception.MonitoringAlreadyRunningException;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringJobAcceptanceException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringRunRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringRunResponse;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MonitoringScheduleRunnerTest {

    @Mock MonitoringScheduleQueueService queue;
    @Mock MonitoringRunRepository runRepository;
    @Mock MonitoringCollectionRecovery collectionRecovery;
    private MonitoringScheduleRunner runner;

    @BeforeEach
    void setUp() {
        runner = new MonitoringScheduleRunner(queue, runRepository, collectionRecovery);
    }

    @Test
    void 예정이나_대기가_없으면_복구나_접수를_실행하지_않는다() {
        runner.runIfDue();
        verify(queue, never()).enqueueDue();
        verify(queue, never()).startPending();
        verifyNoInteractions(collectionRecovery, runRepository);
    }

    @Test
    void 대기를_먼저_저장하고_복구_후_비어있는_실행을_시작한다() {
        when(queue.hasWork()).thenReturn(true);
        when(queue.startPending()).thenReturn(Optional.of(mock(CreateMonitoringRunResponse.class)));

        runner.runIfDue();

        var order = inOrder(queue, collectionRecovery, runRepository);
        order.verify(queue).enqueueDue();
        order.verify(collectionRecovery).recover();
        order.verify(runRepository).existsByStatusIn(any());
        order.verify(queue).startPending();
    }

    @Test
    void 진행_중인_실행이_있어도_대기를_등록하고_소비하지_않는다() {
        when(queue.hasWork()).thenReturn(true);
        when(runRepository.existsByStatusIn(any())).thenReturn(true);

        runner.runIfDue();

        verify(queue).enqueueDue();
        verify(queue, never()).startPending();
    }

    @Test
    void 수집_복구가_실패해도_그_전에_대기를_저장한다() {
        when(queue.hasWork()).thenReturn(true);
        doThrow(new IllegalStateException("temporary failure")).when(collectionRecovery).recover();

        assertThatThrownBy(runner::runIfDue).isInstanceOf(IllegalStateException.class);

        var order = inOrder(queue, collectionRecovery);
        order.verify(queue).enqueueDue();
        order.verify(collectionRecovery).recover();
        verify(queue, never()).startPending();
    }

    @Test
    void 취소나_다른_호출의_시작으로_대기가_사라져도_정상_종료한다() {
        when(queue.hasWork()).thenReturn(true);
        when(queue.startPending()).thenReturn(Optional.empty());

        assertThatCode(runner::runIfDue).doesNotThrowAnyException();
        verify(queue).startPending();
    }

    @Test
    void 실행_경합은_대기_유지로_처리하고_즉시_재시도하지_않는다() {
        when(queue.hasWork()).thenReturn(true);
        when(queue.startPending()).thenThrow(new MonitoringAlreadyRunningException());

        assertThatCode(runner::runIfDue).doesNotThrowAnyException();
        verify(queue, times(1)).startPending();
    }

    @Test
    void 기록된_접수_실패를_숨은_중복_재시도로_바꾸지_않는다() {
        when(queue.hasWork()).thenReturn(true);
        when(queue.startPending()).thenThrow(new MonitoringJobAcceptanceException());

        assertThatCode(runner::runIfDue).doesNotThrowAnyException();
        verify(queue, times(1)).startPending();
    }
}
