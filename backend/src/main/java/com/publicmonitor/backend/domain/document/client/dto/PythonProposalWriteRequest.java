package com.publicmonitor.backend.domain.document.client.dto;

public record PythonProposalWriteRequest(String title, String noticeText, String fileName, String templateText) {}
