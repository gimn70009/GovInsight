package com.publicmonitor.backend.domain.monitoring.service;

import com.publicmonitor.backend.domain.monitoring.entity.MonitoringSource;
import com.publicmonitor.backend.domain.monitoring.exception.DuplicateMonitoringSourceException;
import com.publicmonitor.backend.domain.monitoring.exception.MonitoringSourceNotFoundException;
import com.publicmonitor.backend.domain.monitoring.repository.MonitoringSourceRepository;
import com.publicmonitor.backend.domain.monitoring.web.dto.CreateMonitoringSourceRequest;
import com.publicmonitor.backend.domain.monitoring.web.dto.UpdateMonitoringSourceEnabledRequest;
import com.publicmonitor.backend.domain.monitoring.web.dto.UpdateMonitoringSourceRequest;
import com.publicmonitor.backend.domain.monitoring.web.dto.MonitoringSourceResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MonitoringSourceService {

    private final MonitoringSourceRepository monitoringSourceRepository;

    @Transactional
    public MonitoringSourceResponse create(CreateMonitoringSourceRequest request) {
        if (monitoringSourceRepository.existsByListUrl(request.listUrl())) {
            throw new DuplicateMonitoringSourceException();
        }

        int detailFetchCount = request.detailFetchCount() == null
                ? MonitoringSource.DEFAULT_DETAIL_FETCH_COUNT
                : request.detailFetchCount();
        boolean enabled = request.enabled() == null
                ? MonitoringSource.DEFAULT_ENABLED
                : request.enabled();
        MonitoringSource source = MonitoringSource.create(
                request.organizationName(),
                request.boardName(),
                request.description(),
                request.listUrl(),
                request.urlIncludePattern(),
                detailFetchCount,
                enabled
        );

        return MonitoringSourceResponse.from(monitoringSourceRepository.save(source));
    }

    @Transactional(readOnly = true)
    public List<MonitoringSourceResponse> findAll() {
        return monitoringSourceRepository.findAllByOrderByIdDesc().stream()
                .map(MonitoringSourceResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public MonitoringSourceResponse findById(Long sourceId) {
        MonitoringSource source = getSource(sourceId);
        return MonitoringSourceResponse.from(source);
    }

    @Transactional
    public MonitoringSourceResponse update(Long sourceId, UpdateMonitoringSourceRequest request) {
        MonitoringSource source = getSource(sourceId);
        if (monitoringSourceRepository.existsByListUrlAndIdNot(request.listUrl(), sourceId)) {
            throw new DuplicateMonitoringSourceException();
        }

        source.update(
                request.organizationName(),
                request.boardName(),
                request.description(),
                request.listUrl(),
                request.urlIncludePattern(),
                request.detailFetchCount(),
                request.enabled()
        );
        monitoringSourceRepository.flush();
        return MonitoringSourceResponse.from(source);
    }

    @Transactional
    public MonitoringSourceResponse changeEnabled(
            Long sourceId,
            UpdateMonitoringSourceEnabledRequest request
    ) {
        MonitoringSource source = getSource(sourceId);
        source.changeEnabled(request.enabled());
        monitoringSourceRepository.flush();
        return MonitoringSourceResponse.from(source);
    }

    private MonitoringSource getSource(Long sourceId) {
        return monitoringSourceRepository.findById(sourceId)
                .orElseThrow(MonitoringSourceNotFoundException::new);
    }
}
