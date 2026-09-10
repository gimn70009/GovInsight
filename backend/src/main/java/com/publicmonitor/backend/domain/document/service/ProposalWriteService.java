package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.client.PythonProposalWriterClient;
import com.publicmonitor.backend.domain.document.web.dto.ProposalDraftStateResponse;
import com.publicmonitor.backend.domain.document.web.dto.ProposalDraftStateResponse.RunningProposalResponse;
import com.publicmonitor.backend.domain.document.web.dto.ProposalWriteRequest;
import com.publicmonitor.backend.domain.document.web.dto.ProposalWriteResponse;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@org.springframework.context.annotation.Lazy
@RequiredArgsConstructor
public class ProposalWriteService {
    private final ProposalSourceService sources;
    private final PythonProposalWriterClient writer;
    private final ProposalDraftStore drafts;
    private final ConcurrentHashMap<JobKey, CompletableFuture<ProposalWriteResponse>> running = new ConcurrentHashMap<>();

    public ProposalWriteResponse write(Long userId, Long detectionId, ProposalWriteRequest request) {
        var key = new JobKey(userId, drafts.getVersionId(detectionId), request.attachmentId(), request.partIndex());
        var result = new CompletableFuture<ProposalWriteResponse>();
        var existing = running.putIfAbsent(key, result);
        if (existing != null) {
            try {
                return existing.join();
            } catch (CompletionException exception) {
                if (exception.getCause() instanceof RuntimeException cause) throw cause;
                throw exception;
            }
        }
        try {
            var response = generate(userId, detectionId, request);
            result.complete(response);
            return response;
        } catch (RuntimeException | Error exception) {
            result.completeExceptionally(exception);
            throw exception;
        } finally {
            // Persistence has committed (or failed) before the running marker disappears.
            running.remove(key, result);
        }
    }

    public ProposalDraftStateResponse state(Long userId, Long detectionId) {
        Long versionId = drafts.getVersionId(detectionId);
        var pending = running.keySet().stream()
                .filter(key -> key.userId().equals(userId) && key.versionId().equals(versionId))
                .map(key -> new RunningProposalResponse(key.attachmentId(), key.partIndex()))
                .sorted(Comparator.comparing(RunningProposalResponse::attachmentId)
                        .thenComparingInt(RunningProposalResponse::partIndex)).toList();
        // Read running work first: an empty marker list must not precede an uncommitted result.
        return new ProposalDraftStateResponse(drafts.list(userId, detectionId), pending);
    }

    private ProposalWriteResponse generate(Long userId, Long detectionId, ProposalWriteRequest request) {
        var source = sources.excludedFromDrafting(detectionId).get(request);
        if (source != null) return new ProposalWriteResponse("NEEDS_TEMPLATE", source.fileName(), false,
                java.util.List.of(), source.reason());
        var saved = drafts.reuse(userId, detectionId, request);
        if (saved.isPresent()) return saved.get();
        var response = writer.write(sources.prepare(detectionId, request));
        if ("COMPLETED".equals(response.status()) && response.sections() != null && !response.sections().isEmpty()) {
            return drafts.save(userId, detectionId, request, response);
        }
        return "COMPLETED".equals(response.status()) ? ProposalWriteResponse.unavailable() : response;
    }

    private record JobKey(Long userId, Long versionId, Long attachmentId, int partIndex) {}
}
