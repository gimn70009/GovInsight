package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.repository.DocumentVersionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@org.springframework.context.annotation.Lazy
@RequiredArgsConstructor
public class ParticipationRewriteStore {
    private final DocumentVersionRepository versions;
    @Transactional
    public void begin(Long id, String key) { versions.beginParticipationRewrite(id, key); }
    @Transactional
    public void finish(Long id, String key, String json) { versions.finishParticipationRewrite(id, key, json); }
}
