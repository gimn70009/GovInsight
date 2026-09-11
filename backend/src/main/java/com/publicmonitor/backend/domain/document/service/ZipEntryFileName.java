package com.publicmonitor.backend.domain.document.service;

import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;

final class ZipEntryFileName {
    private static final Charset CP437 = Charset.forName("IBM437");
    private static final Charset CP949 = Charset.forName("x-windows-949");

    private ZipEntryFileName() {}

    static String restoreLegacyKorean(String value) {
        // Old extracted ZIP markers lack encoding metadata. Only repair reversible
        // CP437 box-drawing mojibake that decodes to Korean; otherwise keep the original.
        if (value.codePoints().noneMatch(code -> code >= 0x2500 && code <= 0x259f)) {
            return value;
        }
        try {
            var bytes = CP437.newEncoder().encode(CharBuffer.wrap(value));
            String decoded = CP949.newDecoder().decode(bytes.asReadOnlyBuffer()).toString();
            if (decoded.codePoints().anyMatch(code -> code >= 0xac00 && code <= 0xd7a3)
                    && bytes.equals(CP949.newEncoder().encode(CharBuffer.wrap(decoded)))) {
                return decoded;
            }
        } catch (CharacterCodingException ignored) {
            // A lossy conversion must never replace the stored name.
        }
        return value;
    }
}
