package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.client.PythonProposalWriterClient;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionException;
import com.publicmonitor.backend.domain.document.exception.DocumentDetectionResponseCode;
import com.publicmonitor.backend.domain.document.web.dto.*;
import com.publicmonitor.backend.domain.document.web.dto.ProposalDraftStateResponse.RunningProposalResponse;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@org.springframework.context.annotation.Lazy
@RequiredArgsConstructor
public class ProposalWriteService {
    private final ProposalSourceService sources;
    private final PythonProposalWriterClient writer;
    private final ProposalDraftStore drafts;
    private final ConcurrentHashMap<JobKey, Job> running = new ConcurrentHashMap<>();

    public ProposalWriteResponse write(Long userId, Long detectionId, ProposalWriteRequest request) {
        return execute(userId, detectionId, request, "GENERATE", null, request,
                () -> generate(userId, detectionId, request));
    }

    public ProposalWriteResponse regenerate(Long userId, Long detectionId, ProposalRegenerateRequest request) {
        String id = request.operationId().toString();
        return execute(userId, detectionId, request.source(), "REGENERATE", id, request, () -> {
            var rejected = excluded(detectionId, request.source());
            if (rejected != null) return rejected;
            var previous = drafts.beforeChange(userId, detectionId, request.source(), request.expectedRevision(), id);
            if (id.equals(previous.lastOperationId())) return previous.result();
            String generationId = userId + ":" + request.attachmentId() + ":" + request.partIndex() + ":" + id;
            var input = sources.prepare(detectionId, request.source()).regeneration(
                    generationId, request.feedback(), previous.result());
            var next = writer.write(input);
            if (!completed(next)) return "COMPLETED".equals(next.status()) ? ProposalWriteResponse.unavailable() : next;
            return drafts.replace(userId, detectionId, request.source(), request.expectedRevision(), id, next);
        });
    }

    public ProposalWriteResponse restore(Long userId, Long detectionId, ProposalRestoreRequest request) {
        String id = request.operationId().toString();
        return execute(userId, detectionId, request.source(), "RESTORE", id, request, () -> {
            var rejected = excluded(detectionId, request.source());
            return rejected != null ? rejected : drafts.restore(userId, detectionId, request.source(),
                    request.expectedRevision(), id);
        });
    }

    private ProposalWriteResponse execute(Long userId, Long detectionId, ProposalWriteRequest source,
            String kind, String operationId, Object identity, Supplier<ProposalWriteResponse> action) {
        var key = new JobKey(userId, drafts.getVersionId(detectionId), source.attachmentId(), source.partIndex());
        var job = new Job(kind, operationId, identity, new CompletableFuture<>());
        var existing = running.putIfAbsent(key, job);
        if (existing != null) {
            if (!existing.kind().equals(kind) || !existing.identity().equals(identity)) {
                throw new DocumentDetectionException(DocumentDetectionResponseCode.PROPOSAL_DRAFT_CONFLICT);
            }
            try { return existing.result().join(); }
            catch (CompletionException exception) {
                if (exception.getCause() instanceof RuntimeException cause) throw cause;
                throw exception;
            }
        }
        try {
            var response = action.get();
            job.result().complete(response);
            return response;
        } catch (RuntimeException | Error exception) {
            job.result().completeExceptionally(exception);
            throw exception;
        } finally {
            // The marker lasts until the persistence transaction has committed or failed.
            running.remove(key, job);
        }
    }

    public ProposalDraftStateResponse state(Long userId, Long detectionId) {
        Long versionId = drafts.getVersionId(detectionId);
        var pending = running.entrySet().stream()
                .filter(entry -> entry.getKey().userId().equals(userId) && entry.getKey().versionId().equals(versionId))
                .map(entry -> new RunningProposalResponse(entry.getKey().attachmentId(), entry.getKey().partIndex(),
                        entry.getValue().operationId(), entry.getValue().kind()))
                .sorted(Comparator.comparing(RunningProposalResponse::attachmentId)
                        .thenComparingInt(RunningProposalResponse::partIndex)).toList();
        return new ProposalDraftStateResponse(drafts.list(userId, detectionId), pending);
    }

    private ProposalWriteResponse excluded(Long detectionId, ProposalWriteRequest request) {
        var source = sources.excludedFromDrafting(detectionId).get(request);
        return source == null ? null : new ProposalWriteResponse("NEEDS_TEMPLATE", source.fileName(), false,
                java.util.List.of(), source.reason());
    }

    private boolean completed(ProposalWriteResponse response) {
        return "COMPLETED".equals(response.status()) && response.sections() != null && !response.sections().isEmpty();
    }

    private ProposalWriteResponse generate(Long userId, Long detectionId, ProposalWriteRequest request) {
        var rejected = excluded(detectionId, request);
        if (rejected != null) return rejected;
        var saved = drafts.reuse(userId, detectionId, request);
        if (saved.isPresent()) return saved.get();
        var response = writer.write(sources.prepare(detectionId, request));
        if (completed(response)) return drafts.save(userId, detectionId, request, response);
        return "COMPLETED".equals(response.status()) ? ProposalWriteResponse.unavailable() : response;
    }

    private record JobKey(Long userId, Long versionId, Long attachmentId, int partIndex) {}
    private record Job(String kind, String operationId, Object identity, CompletableFuture<ProposalWriteResponse> result) {}
}
