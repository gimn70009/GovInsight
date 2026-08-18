package com.publicmonitor.backend.domain.document.web.controller;

import com.publicmonitor.backend.domain.document.service.CollectionResultService;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest;
import com.publicmonitor.backend.domain.document.web.dto.CollectionResultResponse;
import com.publicmonitor.backend.global.response.SuccessResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/monitoring/collection-results")
public class InternalCollectionResultController {

    private final CollectionResultService collectionResultService;

    @PostMapping
    public SuccessResponse<CollectionResultResponse> receive(
            @Valid @RequestBody CollectionResultRequest request
    ) {
        return SuccessResponse.ok(collectionResultService.receive(request));
    }
}
