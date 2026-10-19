package com.publicmonitor.backend.domain.settings.service;

import com.publicmonitor.backend.domain.settings.entity.EvaluationSettings;
import com.publicmonitor.backend.domain.settings.entity.EvaluationSettingsRevision;
import com.publicmonitor.backend.domain.settings.exception.*;
import com.publicmonitor.backend.domain.settings.repository.*;
import com.publicmonitor.backend.domain.settings.web.dto.*;
import com.publicmonitor.backend.domain.settings.web.dto.EvaluationSettingsResponse.SelectionGroup;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class EvaluationSettingsService {
    private final EvaluationSettingsRepository repository;
    private final EvaluationSettingsRevisionRepository revisions;
    private final ObjectMapper mapper;
    private final EvaluationSettingsData defaults;
    private final Map<String, List<SelectionGroup>> catalog;

    public EvaluationSettingsService(EvaluationSettingsRepository repository,
            EvaluationSettingsRevisionRepository revisions, ObjectMapper mapper) {
        this.repository = repository; this.revisions = revisions; this.mapper = mapper;
        try (var data = new ClassPathResource("opportunity-settings-defaults.json").getInputStream();
             var choices = new ClassPathResource("opportunity-settings-catalog.json").getInputStream()) {
            defaults = mapper.readValue(data, EvaluationSettingsData.class);
            catalog = mapper.readValue(choices, new TypeReference<Map<String, List<SelectionGroup>>>() {});
        } catch (java.io.IOException e) { throw new IllegalStateException("평가 설정 기본값을 읽지 못했습니다.", e); }
    }

    @Transactional(readOnly = true)
    public EvaluationSettingsResponse find() {
        return repository.findById(EvaluationSettings.ID)
                .map(s -> new EvaluationSettingsResponse(s.revision(), read(s.getData()), catalog, s.getUpdatedAt()))
                .orElseGet(() -> new EvaluationSettingsResponse(0, defaults, catalog, null));
    }

    @Transactional(readOnly = true)
    public EvaluationSettingsSnapshot currentSnapshot() {
        var current = find();
        return current.revision() == 0 ? null : new EvaluationSettingsSnapshot(current.revision(), current.data());
    }

    @Transactional(readOnly = true)
    public EvaluationSettingsData revision(long revision) {
        if (revision == 0) return null;
        return revisions.findById(revision).map(s -> read(s.getData())).orElseThrow(() ->
                new EvaluationSettingsException(EvaluationSettingsResponseCode.UNKNOWN_REVISION));
    }

    @Transactional
    public EvaluationSettingsResponse update(UpdateEvaluationSettingsRequest request) {
        validateSelections(request.data());
        var current = repository.findById(EvaluationSettings.ID).orElseGet(EvaluationSettings::empty);
        if (current.revision() != request.revision()) {
            throw new EvaluationSettingsException(EvaluationSettingsResponseCode.CHANGED);
        }
        String data = mapper.writeValueAsString(request.data());
        if (current.revision() > 0 && read(current.getData()).equals(request.data())) return find();
        current.update(data);
        try {
            repository.saveAndFlush(current);
            revisions.saveAndFlush(new EvaluationSettingsRevision(current.revision(), data));
        } catch (ObjectOptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw new EvaluationSettingsException(EvaluationSettingsResponseCode.CHANGED);
        }
        return new EvaluationSettingsResponse(current.revision(), request.data(), catalog, current.getUpdatedAt());
    }

    private EvaluationSettingsData read(String json) { return mapper.readValue(json, EvaluationSettingsData.class); }
    private void validateSelections(EvaluationSettingsData data) {
        selected("industries", data.company().industries());
        selected("technologies", data.company().technologies());
        selected("industries", data.criteria().industries());
        selected("technologies", data.criteria().tasks());
        selected("roles", data.criteria().roles());
        selected("businessTypes", data.criteria().businessTypes());
        selected("goals", data.criteria().goals());
    }
    private void selected(String type, List<String> values) {
        var allowed = catalog.get(type).stream().flatMap(g -> g.options().stream()).toList();
        if (values == null || new HashSet<>(values).size() != values.size() || !allowed.containsAll(values)) {
            throw new EvaluationSettingsException(EvaluationSettingsResponseCode.INVALID_SELECTION);
        }
    }
}
