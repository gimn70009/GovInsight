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
    private static final String VERSION = "participation-llm-v5";
    private final PythonParticipationRewriteClient client;
    private final ParticipationRewriteStore store;
    private final ObjectMapper mapper;
    private final ConcurrentMap<Long, String> running = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> retryAfter = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Response> progress = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Response> completed = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("participation-rewrite-", 0).daemon().factory());

    public ParticipationRewriteService(PythonParticipationRewriteClient client, ParticipationRewriteStore store, ObjectMapper mapper) {
        this.client = client; this.store = store; this.mapper = mapper;
    }

    public ParticipationGuide resolve(DocumentVersion version, ParticipationGuide raw, boolean incomplete) {
        var input = ParticipationRewriteProjection.input(raw, incomplete);
        var request = input.request();
        if (request.sources().isEmpty()) return ParticipationRewriteProjection.guide(raw, input,
                new Response(java.util.List.of(), java.util.List.of(), "READY"), "READY");
        String key = fingerprint(request);
        String cacheId = version.getId() + ":" + key;
        if (version.getId() != null && key.equals(running.get(version.getId()))) {
            return ParticipationRewriteProjection.guide(raw, input,
                    progress.getOrDefault(cacheId, Response.unavailable(request)), "PENDING");
        }
        Response cached = null;
        if (key.equals(version.getParticipationRewriteKey()) && version.getParticipationRewriteJson() != null) {
            try { cached = mapper.readValue(version.getParticipationRewriteJson(), Response.class); }
            catch (RuntimeException ignored) { /* Rebuild invalid cache from source. */ }
        }
        if (cached == null) cached = completed.get(version.getId() + ":" + key);
        if (cached != null && cached.statements() != null && cached.evidenceOnlyIds() != null
                && ("READY".equals(cached.status()) || System.currentTimeMillis() < retryAfter.getOrDefault(cacheId, 0L)))
            return ParticipationRewriteProjection.guide(raw, input, cached, cached.status());
        String status = "PENDING";
        if (request.sources().size() > 160 || request.sources().stream().mapToInt(s -> s.excerpt().length()).sum() > 64000
                || version.getId() == null) status = "UNAVAILABLE";
        else if (running.putIfAbsent(version.getId(), key) == null) {
            try { executor.execute(() -> run(version.getId(), key, request)); }
            catch (RejectedExecutionException exception) { running.remove(version.getId(), key); status = "UNAVAILABLE"; }
        }
        return ParticipationRewriteProjection.guide(raw, input, Response.unavailable(request), status);
    }

    private void run(Long id, String key, Request request) {
        Response result = Response.unavailable(request);
        try {
            store.begin(id, key);
            log.info("Participation rewrite started. version_id={} sources={}", id, request.sources().size());
            result = client.rewrite(request, partial -> progress.put(id + ":" + key, partial));
            store.finish(id, key, mapper.writeValueAsString(result));
        } catch (RuntimeException exception) {
            log.warn("Participation rewrite unavailable. version_id={} error_type={}", id, exception.getClass().getSimpleName());
        } finally {
            // A bounded in-process terminal cache also prevents polling loops after a storage outage.
            if (completed.size() >= 256) { completed.clear(); retryAfter.clear(); }
            completed.put(id + ":" + key, result);
            if ("UNAVAILABLE".equals(result.status())) retryAfter.put(id + ":" + key, System.currentTimeMillis() + 300_000);
            else retryAfter.remove(id + ":" + key);
            progress.remove(id + ":" + key);
            log.info("Participation rewrite finished. version_id={} status={} statements={}",
                    id, result.status(), result.statements().size());
            running.remove(id, key);
        }
    }

    String fingerprint(Request request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (VERSION + mapper.writeValueAsString(request)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    @PreDestroy
    void close() { executor.shutdownNow(); }
}
