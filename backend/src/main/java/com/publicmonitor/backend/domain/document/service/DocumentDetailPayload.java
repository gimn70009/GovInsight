package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.DocumentDetectionDetailResponse.*;
import java.util.List;

/** Only version-specific derived sections are stored; live run/analysis state stays a DB read. */
public record DocumentDetailPayload(List<ResponsibleOrganization> responsibleOrganizations,
                                    SubmissionGuide submissionGuide, ParticipationGuide participationGuide,
                                    BusinessTypeSummary businessType) {
    boolean pending() {
        return "PENDING".equals(participationGuide.rewriteStatus()) || "PENDING".equals(submissionGuide.notesStatus());
    }
    public static DocumentDetailPayload empty(String status) {
        return new DocumentDetailPayload(List.of(), new SubmissionGuide(List.of(), List.of(), status),
                new ParticipationGuide(List.of(), List.of(), List.of(), List.of(), status), null);
    }
    DocumentDetailPayload refreshing() {
        return new DocumentDetailPayload(responsibleOrganizations,
                new SubmissionGuide(submissionGuide.documents(), submissionGuide.notes(), "PENDING"),
                new ParticipationGuide(participationGuide.conditions(), participationGuide.rules(), participationGuide.variants(),
                        participationGuide.categories(), "PENDING"), businessType);
    }
    DocumentDetailPayload preservePendingContent(DocumentDetailPayload previous) {
        var old = previous.refreshing();
        boolean keepSubmission = "PENDING".equals(submissionGuide.notesStatus())
                && (!previous.submissionGuide.documents().isEmpty() || !previous.submissionGuide.notes().isEmpty());
        boolean keepParticipation = "PENDING".equals(participationGuide.rewriteStatus())
                && (!previous.participationGuide.rules().isEmpty() || !previous.participationGuide.conditions().isEmpty()
                    || !previous.participationGuide.categories().isEmpty());
        return new DocumentDetailPayload(responsibleOrganizations,
                keepSubmission ? old.submissionGuide : submissionGuide,
                keepParticipation ? old.participationGuide : participationGuide, businessType);
    }
    DocumentDetailPayload failed() {
        return new DocumentDetailPayload(responsibleOrganizations,
                new SubmissionGuide(submissionGuide.documents(), submissionGuide.notes(),
                        submissionGuide.documents().isEmpty() && submissionGuide.notes().isEmpty() ? "UNAVAILABLE" : "PARTIAL"),
                new ParticipationGuide(participationGuide.conditions(), participationGuide.rules(), participationGuide.variants(),
                        participationGuide.categories(), participationGuide.rules().isEmpty() ? "UNAVAILABLE" : "PARTIAL"), businessType);
    }
}
