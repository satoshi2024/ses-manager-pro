package com.ses.service.ai.copilot.digest;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CopilotDigestTest {

    @Test
    void sha256は安定したhexを返す() {
        String hash = CopilotDigest.sha256("test");
        assertEquals(64, hash.length());
        assertEquals(hash, CopilotDigest.sha256("test"));
    }

    @Test
    void 異なる入力は異なるhashになる() {
        assertNotEquals(
                CopilotDigest.sha256("a"),
                CopilotDigest.sha256("b"));
    }
}
