package com.publicmonitor.backend.domain.document.client.dto;

import java.util.List;
import com.publicmonitor.backend.domain.document.web.dto.ProposalWriteResponse;

public record PythonProposalWriteRequest(String title, String noticeText, String fileName, String templateText,
        String generationId, String feedback, List<PreviousSection> previousSections) {
    public record PreviousSection(String title, String body) {}
    public PythonProposalWriteRequest(String title, String noticeText, String fileName, String templateText) {
        this(title, noticeText, fileName, templateText, "", "", List.of());
    }
    public PythonProposalWriteRequest regeneration(String id, String instructions, ProposalWriteResponse previous) {
        return new PythonProposalWriteRequest(title, noticeText, fileName, templateText, id,
                instructions == null ? "" : instructions.strip(),
                previous.sections().stream().map(section -> new PreviousSection(section.title(), section.body())).toList());
    }
}
