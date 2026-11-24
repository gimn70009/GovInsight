package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.analysis.repository.DocumentAnalysisRepository;
import com.publicmonitor.backend.domain.document.repository.*;
import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.Proposal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@lombok.extern.slf4j.Slf4j @Service @RequiredArgsConstructor
@org.springframework.context.annotation.Lazy
public class DocumentDetailSnapshotBuilder {
    private final DocumentVersionRepository versions;
    private final DocumentAttachmentRepository attachments;
    private final DocumentAnalysisRepository analyses;
    private final ParticipationRewriteService participation;
    private final SubmissionNotesService notes;
    private final ObjectMapper mapper;

    @Transactional(readOnly = true)
    public DocumentDetailPayload build(Long versionId) {
        var version = versions.findById(versionId).orElseThrow();
        var files = attachments.findAllByDocumentVersionId(versionId);
        var analysis = analyses.findByDocumentVersionId(versionId).orElse(null);
        Proposal proposal = null;
        if (analysis != null && analysis.getProposalDirection() != null && analysis.getProposalDirection().strip().startsWith("{"))
            proposal = mapper.readValue(PreparationCompatibility.normalize(analysis.getProposalDirection(), mapper), Proposal.class);
        var preparation = proposal == null ? null : proposal.preparation();
        var business = BusinessTypeResolver.diagnose(version.getTitle(), version.getContentText(), files,
                analysis == null ? null : analysis.getComparisonSummary(), proposal == null ? null : proposal.documentType(), mapper);
        log.info("Business type materialized. version_id={} status={} type_code={} decision_reason={} stored_reason={}",
                versionId, business.summary().status(), business.summary().code(), business.decisionReason(), business.storedReason());
        var candidates = ParticipationConditionResolver.candidatesWithScopes(version.getContentText(), files, preparation);
        var submission = SubmissionDocumentResolver.resolveWithSources(version.getContentText(), files, preparation);
        return new DocumentDetailPayload(ResponsibleOrganizationExtractor.extract(version.getContentText(), files),
                notes.resolve(version, submission.attachments(), submission.guide()),
                participation.resolve(version, candidates.guide(), ParticipationCategorySources.incomplete(version.getContentText(), files), candidates.scopes()),
                business.summary());
    }
}
