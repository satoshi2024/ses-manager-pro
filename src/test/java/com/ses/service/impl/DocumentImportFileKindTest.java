package com.ses.service.impl;

import com.ses.common.enums.FileKind;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentImportFileKindTest {
    @Test
    void importKindIsStrictlyCsvAndRawByteBounded() {
        assertEquals(64L * 1024 * 1024, FileKind.IMPORT.getMaxBytes());
        assertTrue(FileKind.IMPORT.isExtensionAllowed("csv"));
        assertFalse(FileKind.IMPORT.isExtensionAllowed("xlsx"));
        assertTrue(FileKind.IMPORT.isContentTypeAllowed("csv", "text/csv"));
        assertFalse(FileKind.IMPORT.isContentTypeAllowed("csv", "application/zip"));
        assertTrue(FileKind.IMPORT.isMagicBytesAllowed("csv", "externalKey,companyName\n".getBytes(StandardCharsets.UTF_8)));
        assertFalse(FileKind.IMPORT.isMagicBytesAllowed("csv", new byte[]{'P', 'K', 3, 4}));
    }
}
