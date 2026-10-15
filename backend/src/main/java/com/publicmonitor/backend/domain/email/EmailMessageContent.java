package com.publicmonitor.backend.domain.email;

/** Both MIME alternatives describe the same report snapshot. */
public record EmailMessageContent(String plainText, String html) {}
