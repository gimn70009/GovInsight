package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.client.PythonSubmissionNotesClient;
import com.publicmonitor.backend.domain.document.entity.DocumentAttachment;
import com.publicmonitor.backend.domain.document.entity.DocumentVersion;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.SubmissionGuide;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import static com.publicmonitor.backend.domain.document.client.SubmissionNotesContract.*;

@Slf4j
@Service
@org.springframework.context.annotation.Lazy
public class SubmissionNotesService {
    private static final String VERSION = "submission-notes-ko-v1";
    private final PythonSubmissionNotesClient client;
    private final SubmissionNotesStore store;
    private final ObjectMapper mapper;
    private final Map<Long, String> running = new HashMap<>();
    private final Map<String, Response> completed = new LinkedHashMap<>();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), Thread.ofPlatform().name("submission-notes-", 0).daemon().factory());
    public record Stored(Response result, long startedAt) {}

    public SubmissionNotesService(PythonSubmissionNotesClient client, SubmissionNotesStore store, ObjectMapper mapper) {
        this.client = client; this.store = store; this.mapper = mapper;
    }
    public SubmissionGuide resolve(DocumentVersion version, List<DocumentAttachment> attachments, SubmissionGuide raw) {
        var input = SubmissionNotesProjection.input(version.getContentText(), attachments, raw);
        var request = input.request();
        if (request.sources().isEmpty()) return new SubmissionGuide(raw.documents(), List.of(), request.incomplete() ? "PARTIAL" : "READY");
        String key = fingerprint(request), cacheId = version.getId() + ":" + key;
        synchronized (completed) {
            Response cached = completed.get(cacheId);
            if (cached != null) return SubmissionNotesProjection.guide(raw, input, cached);
            if (key.equals(version.getSubmissionNotesKey())) {
                try {
                    var stored = mapper.readValue(version.getSubmissionNotesJson(), Stored.class);
                    var result = stored.result();
                    if ("PENDING".equals(result.status()) && System.currentTimeMillis() - stored.startedAt() > 120_000)
                        result = Response.unavailable();
                    return SubmissionNotesProjection.guide(raw, input, result);
                } catch (RuntimeException exception) {
                    // A corrupt/abandoned paid attempt is not permission to charge again.
                    return SubmissionNotesProjection.guide(raw, input, Response.unavailable());
                }
            }
            if (running.containsKey(version.getId())) return new SubmissionGuide(raw.documents(), List.of(), "PENDING");
            if (version.getId() == null)
                return new SubmissionGuide(raw.documents(), List.of(), "UNAVAILABLE");
            running.put(version.getId(), key);
            try { executor.execute(() -> run(version.getId(), key, version.getSubmissionNotesKey(), request)); }
            catch (RejectedExecutionException exception) {
                running.remove(version.getId());
                return new SubmissionGuide(raw.documents(), List.of(), "UNAVAILABLE");
            }
        }
        return new SubmissionGuide(raw.documents(), List.of(), "PENDING");
    }
    private void run(Long id, String key, String previousKey, Request request) {
        Response result = Response.unavailable();
        boolean claimed = false;
        boolean otherWorker = false;
        long started = System.currentTimeMillis();
        try {
            claimed = store.claim(id, key, previousKey,
                    mapper.writeValueAsString(new Stored(new Response(List.of(), "PENDING"), started)));
            if (!claimed) { otherWorker = true; return; }
            var extracted = client.extract(request);
            if (extracted != null && extracted.notes() != null && extracted.notes().size() <= 30 && extracted.status() != null
                    && List.of("READY", "PARTIAL", "UNAVAILABLE").contains(extracted.status())) result = extracted;
        } catch (RuntimeException exception) {
            log.warn("Submission notes unavailable. version_id={} error_type={}", id, exception.getClass().getSimpleName());
        } finally {
            if (claimed) {
                try { store.finish(id, key, mapper.writeValueAsString(new Stored(result, started))); }
                catch (RuntimeException exception) {
                    log.warn("Submission notes persistence failed. version_id={} error_type={}", id, exception.getClass().getSimpleName());
                }
            }
            synchronized (completed) {
                // A lost database claim belongs to another worker; its result will arrive via the DB.
                if (!otherWorker) {
                    completed.put(id + ":" + key, result);
                    if (completed.size() > 256) completed.remove(completed.keySet().iterator().next());
                }
                running.remove(id, key);
            }
            log.info("Submission notes finished. version_id={} claimed={} status={} elapsed_ms={}",
                    id, claimed, result.status(), System.currentTimeMillis() - started);
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
