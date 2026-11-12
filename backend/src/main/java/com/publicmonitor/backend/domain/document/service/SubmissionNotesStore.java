package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.repository.DocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.context.annotation.Lazy
@RequiredArgsConstructor
public class SubmissionNotesStore {
    private final DocumentVersionRepository versions;
    @Transactional
    public boolean claim(Long id, String key, String previousKey, String json) {
        return versions.claimSubmissionNotes(id, key, previousKey, json) == 1;
    }
    @Transactional
    public void finish(Long id, String key, String json) { versions.finishSubmissionNotes(id, key, json); }
}
