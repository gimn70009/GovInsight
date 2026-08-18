package com.publicmonitor.backend.domain.document.service;

import com.publicmonitor.backend.domain.document.web.dto.CollectionResultRequest.CollectedAttachment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DocumentVersionHasher {

    public String hash(String title, String content, List<CollectedAttachment> attachments) {
        String attachmentSignature = attachments.stream()
                .sorted(Comparator.comparing(CollectedAttachment::downloadUrl)
                        .thenComparing(CollectedAttachment::fileName))
                .map(attachment -> attachment.fileName() + "\u001f" + attachment.downloadUrl())
                .reduce((left, right) -> left + "\u001e" + right)
                .orElse("");
        String value = title + "\u001d" + content + "\u001d" + attachmentSignature;
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 해시 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
