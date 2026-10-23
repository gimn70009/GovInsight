package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.client.PythonParticipationRewriteClient;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.ParticipationGuide;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static com.publicmonitor.backend.domain.document.client.ParticipationRewriteContract.*;

@Slf4j
@Service
@org.springframework.context.annotation.Lazy
public class ParticipationRewriteService {
    private static final String VERSION = "participation-seven-fields-v3";
    private final PythonParticipationRewriteClient client;
    private final ParticipationRewriteStore store;
    private final ObjectMapper mapper;
    private final ConcurrentMap<Long, String> running = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Response> progress = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Response> completed = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("participation-rewrite-", 0).daemon().factory());

    public ParticipationRewriteService(PythonParticipationRewriteClient client, ParticipationRewriteStore store, ObjectMapper mapper) {
        this.client = client; this.store = store; this.mapper = mapper;
    }

    public ParticipationGuide resolve(DocumentVersion version, ParticipationGuide raw, boolean incomplete) {
        return resolve(version, raw, incomplete, null);
    }

    public ParticipationGuide resolve(DocumentVersion version, ParticipationGuide raw, boolean incomplete, ParticipationScopeIndex scopes) {
        var input = ParticipationRewriteProjection.input(raw, incomplete, scopes);
        var request = input.request();
        var retained = retainedResult(version, raw, input, incomplete, scopes);
        if (retained != null) return retained;
        if (request.sources().isEmpty()) return ParticipationRewriteProjection.guide(raw, input,
                new Response(java.util.List.of(), java.util.List.of(), "READY"), "READY");
        // Old source ids belong to the frozen request, not the expanded seven-field request.
        var legacyInput = ParticipationRewriteProjection.legacyInput(raw, incomplete, scopes);
        String legacyKey = fingerprint(legacyInput.request(), "participation-four-fields-v1");
        if (legacyKey.equals(version.getParticipationRewriteKey()) && version.getParticipationRewriteJson() != null) {
            try {
                var legacy = mapper.readValue(version.getParticipationRewriteJson(), Response.class);
                if (legacy.statements() != null && legacy.evidenceOnlyIds() != null
                        && ("READY".equals(legacy.status()) || "UNAVAILABLE".equals(legacy.status())))
                    return ParticipationRewriteProjection.guide(raw, legacyInput, legacy, legacy.status());
            } catch (RuntimeException ignored) { /* Invalid stored JSON cannot be reused. */ }
        }
        var previousInput = ParticipationRewriteProjection.previousInput(raw, incomplete, scopes);
        if (fingerprint(previousInput.request(), "participation-seven-fields-v1").equals(version.getParticipationRewriteKey())
                && version.getParticipationRewriteJson() != null) {
            try {
                var previous = mapper.readValue(version.getParticipationRewriteJson(), Response.class);
                if (previous.statements() != null && previous.evidenceOnlyIds() != null
                        && ("READY".equals(previous.status()) || "UNAVAILABLE".equals(previous.status())))
                    return ParticipationRewriteProjection.guide(raw, previousInput, previous, previous.status());
            } catch (RuntimeException ignored) { /* Invalid stored JSON cannot be reused. */ }
        }
        String key = fingerprint(request);
        String cacheId = version.getId() + ":" + key;
        if (version.getId() != null && key.equals(running.get(version.getId()))) {
            return ParticipationRewriteProjection.guide(raw, input,
                    progress.getOrDefault(cacheId, Response.unavailable(request)), "PENDING");
        }
        Response cached = null;
        boolean currentCache = key.equals(version.getParticipationRewriteKey());
        if (currentCache && version.getParticipationRewriteJson() != null) {
            try { cached = mapper.readValue(version.getParticipationRewriteJson(), Response.class); }
            catch (RuntimeException ignored) { /* Rebuild invalid cache from source. */ }
        }
        if (cached == null) cached = completed.get(version.getId() + ":" + key);
        if (cached != null && cached.statements() != null && cached.evidenceOnlyIds() != null
                && ("READY".equals(cached.status()) || "UNAVAILABLE".equals(cached.status())))
            return ParticipationRewriteProjection.guide(raw, input, cached, cached.status());
        String status = "PENDING";
        if (request.sources().size() > 160
                || version.getId() == null) status = "UNAVAILABLE";
        else synchronized (completed) {
            // Check again under the admission lock: a job may have completed since the first cache lookup.
            cached = completed.get(cacheId);
            if (cached != null) return ParticipationRewriteProjection.guide(raw, input, cached, cached.status());
            // Preserve terminal results instead of evicting failures and charging for them again.
            if (completed.size() + running.size() >= 256) status = "UNAVAILABLE";
            else if (running.putIfAbsent(version.getId(), key) == null) {
                try { executor.execute(() -> run(version.getId(), key, request)); }
                catch (RejectedExecutionException exception) { running.remove(version.getId(), key); status = "UNAVAILABLE"; }
            }
        }
        return ParticipationRewriteProjection.guide(raw, input, Response.unavailable(request), status);
    }

    private ParticipationGuide retainedResult(DocumentVersion version, ParticipationGuide raw,
            ParticipationRewriteProjection.Input current, boolean incomplete, ParticipationScopeIndex scopes) {
        if (scopes == null || scopes.compatibility() == null || version.getParticipationRewriteJson() == null) return null;
        var old = scopes.compatibility();
        var oldRaw = new ParticipationGuide(raw.conditions(), old.rules(), raw.variants(), java.util.List.of());
        var inputs = java.util.List.of(
                ParticipationRewriteProjection.input(oldRaw, incomplete, old.scopes()),
                ParticipationRewriteProjection.previousInput(oldRaw, incomplete, old.scopes()),
                ParticipationRewriteProjection.legacyInput(oldRaw, incomplete, old.scopes()));
        var policies = java.util.List.of("participation-seven-fields-v2", "participation-seven-fields-v1", "participation-four-fields-v1");
        for (int i = 0; i < inputs.size(); i++) {
            var oldInput = inputs.get(i);
            if (!fingerprint(oldInput.request(), policies.get(i)).equals(version.getParticipationRewriteKey())) continue;
            try {
                var response = mapper.readValue(version.getParticipationRewriteJson(), Response.class);
                if (response.statements() != null && response.evidenceOnlyIds() != null
                        && ("READY".equals(response.status()) || "UNAVAILABLE".equals(response.status())))
                    return ParticipationRewriteProjection.guide(raw, oldInput, response, response.status(), current);
            } catch (RuntimeException ignored) { /* Invalid cache cannot establish a paid terminal result. */ }
        }
        return null;
    }

    private void run(Long id, String key, Request request) {
        Response result = Response.unavailable(request);
        try {
            store.begin(id, key);
            log.info("Participation rewrite started. version_id={} sources={}", id, request.sources().size());
            result = client.rewrite(request, partial -> progress.put(id + ":" + key, partial));
        } catch (RuntimeException exception) {
            log.warn("Participation rewrite unavailable. version_id={} error_type={}", id, exception.getClass().getSimpleName());
        } finally {
            try { store.finish(id, key, mapper.writeValueAsString(result)); }
            catch (RuntimeException exception) {
                log.warn("Participation rewrite result persistence failed. version_id={} error_type={}",
                        id, exception.getClass().getSimpleName());
            }
            progress.remove(id + ":" + key);
            log.info("Participation rewrite finished. version_id={} status={} statements={}",
                    id, result.status(), result.statements().size());
            synchronized (completed) {
                completed.put(id + ":" + key, result);
                running.remove(id, key);
            }
        }
    }

    String fingerprint(Request request) {
        return fingerprint(request, VERSION);
    }

    private String fingerprint(Request request, String policyVersion) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (policyVersion + mapper.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    @PreDestroy
    void close() { executor.shutdownNow(); }
}
