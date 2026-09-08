package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.client.PythonProposalWriterClient;
import com.publicmonitor.backend.domain.document.web.dto.ProposalWriteRequest;
import com.publicmonitor.backend.domain.document.web.dto.ProposalWriteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@org.springframework.context.annotation.Lazy
@RequiredArgsConstructor
public class ProposalWriteService {
    private final ProposalSourceService sources;
    private final PythonProposalWriterClient writer;

    public ProposalWriteResponse write(Long detectionId, ProposalWriteRequest request) {
        return writer.write(sources.prepare(detectionId, request));
    }
}
