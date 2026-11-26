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
    private static final String VERSION = "participation-condition-contract-v7-scope-ownership";
    // Allow the AI document deadline (150 seconds) plus transport/persistence overhead.
    private static final long PENDING_TIMEOUT_MILLIS = 180_000;
    record Pending(String status, long startedAt) {}
    private final PythonParticipationRewriteClient client;
    private final ParticipationRewriteStore store;
    private final ObjectMapper mapper;
    private final ConcurrentMap<Long, String> running = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Response> progress = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Response> completed = new ConcurrentHashMap<>();
    private final java.util.Set<String> persistedResults = ConcurrentHashMap.newKeySet();
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
        // v7 binds conditions to source-local program/organization ownership.
        // A previous policy is refreshed once through the same database claim below.
        if (input.availableSources().isEmpty()) return ParticipationRewriteProjection.guide(raw, input,
                new Response(java.util.List.of(), java.util.List.of(), "READY"), "READY");
        String key = fingerprint(request);
        String cacheId = version.getId() + ":" + key;
        if (version.getId() != null && key.equals(running.get(version.getId()))) {
            return ParticipationRewriteProjection.guide(raw, input,
                    progress.getOrDefault(cacheId, Response.unavailable(request)), "PENDING");
        }
        Response cached = completed.get(cacheId);
        if (cached == null && key.equals(version.getParticipationRewriteKey())) cached = storedResult(version, request);
        if (cached != null)
            return ParticipationRewriteProjection.guide(raw, input, cached, cached.status());
        // Unmatched source identities are stale, not an empty extraction for the current input.
        // Refresh once under a database claim; never remap old sequential ids using a partial quote.
        var outbound = ParticipationSourceSelection.select(input).outbound(input, version.getTitle());
        if (outbound.sources().isEmpty()) return ParticipationRewriteProjection.guide(raw, input,
                new Response(java.util.List.of(), java.util.List.of(), "READY"), "READY");
        String status = "PENDING";
        // Candidate count must not prevent Python's bounded local selection.
        if (version.getId() == null) status = "UNAVAILABLE";
        else synchronized (completed) {
            // Check again under the admission lock: a job may have completed since the first cache lookup.
            cached = completed.get(cacheId);
            if (cached != null) return ParticipationRewriteProjection.guide(raw, input, cached, cached.status());
            // DB-persisted results can leave memory; failed writes must remain to prevent a second paid call.
            for (var persisted : persistedResults) {
                if (completed.size() + running.size() < 256) break;
                completed.remove(persisted);
                persistedResults.remove(persisted);
            }
            if (completed.size() + running.size() >= 256) status = "PENDING";
            else if (running.putIfAbsent(version.getId(), key) == null) {
                try { executor.execute(() -> run(version.getId(), key, version.getParticipationRewriteKey(), outbound)); }
                catch (RejectedExecutionException exception) { running.remove(version.getId(), key); status = "PENDING"; }
            }
        }
        return ParticipationRewriteProjection.guide(raw, input, Response.unavailable(request), status);
    }

    private Response storedResult(DocumentVersion version, Request request) {
        try {
            var json = mapper.readTree(version.getParticipationRewriteJson());
            if (json != null && "PENDING".equals(json.path("status").asText())) {
                long startedAt = json.path("startedAt").asLong(0);
                String status = startedAt > 0 && System.currentTimeMillis() - startedAt < PENDING_TIMEOUT_MILLIS
                        ? "PENDING" : "UNAVAILABLE";
                return new Response(java.util.List.of(), request.sources().stream().map(Source::id).toList(), status);
            }
            var response = mapper.treeToValue(json, Response.class);
            if (response != null && response.statements() != null && response.evidenceOnlyIds() != null
                    && ("READY".equals(response.status()) || "PARTIAL".equals(response.status()) || "UNAVAILABLE".equals(response.status()))) return response;
        } catch (RuntimeException ignored) { /* An interrupted or corrupt attempt for this exact input must not charge again. */ }
        return Response.unavailable(request);
    }

    private void run(Long id, String key, String previousKey, Request request) {
        Response result = Response.unavailable(request);
        boolean claimed = false;
        boolean otherWorker = false;
        boolean persisted = false;
        try {
            claimed = store.claim(id, key, previousKey, mapper.writeValueAsString(new Pending("PENDING", System.currentTimeMillis())));
            if (!claimed) { otherWorker = true; return; }
            log.info("Participation rewrite started. version_id={} sources={}", id, request.sources().size());
            var extracted = client.rewrite(request, partial -> progress.put(id + ":" + key, partial));
            if (extracted != null && extracted.statements() != null && extracted.evidenceOnlyIds() != null
                    && ("READY".equals(extracted.status()) || "PARTIAL".equals(extracted.status()) || "UNAVAILABLE".equals(extracted.status()))) result = extracted;
        } catch (RuntimeException exception) {
            log.warn("Participation rewrite unavailable. version_id={} error_type={}", id, exception.getClass().getSimpleName());
        } finally {
            if (claimed) {
                try { store.finish(id, key, mapper.writeValueAsString(result)); persisted = true; }
                catch (RuntimeException exception) {
                    log.warn("Participation rewrite result persistence failed. version_id={} error_type={}", id, exception.getClass().getSimpleName());
                }
            }
            progress.remove(id + ":" + key);
            log.info("Participation rewrite finished. version_id={} status={} statements={}",
                    id, result.status(), result.statements().size());
            synchronized (completed) {
                if (!otherWorker) {
                    completed.put(id + ":" + key, result);
                    if (persisted) persistedResults.add(id + ":" + key);
                }
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
