package com.publicmonitor.backend.domain.document.service;

import org.springframework.stereotype.Component;

@Component
public class DocumentContentNormalizer {

    public String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().replaceAll("\\s+", " ");
    }
}
