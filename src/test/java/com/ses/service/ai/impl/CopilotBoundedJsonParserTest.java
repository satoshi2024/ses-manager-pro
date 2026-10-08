package com.ses.service.ai.impl;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotBoundedJsonParserTest {

    @Test
    void 制約内の境界値を受け付ける() {
        CopilotBoundedJsonParser parser = new CopilotBoundedJsonParser(() -> 1L);
        String exactDepth = "[".repeat(CopilotBoundedJsonParser.MAX_NESTING_DEPTH) + "0"
                + "]".repeat(CopilotBoundedJsonParser.MAX_NESTING_DEPTH);
        String exactString = "\"" + "a".repeat(CopilotBoundedJsonParser.MAX_STRING_LENGTH) + "\"";
        String exactNumber = "1" + "0".repeat(CopilotBoundedJsonParser.MAX_NUMBER_LENGTH - 1);
        byte[] exactBodySize = ("null" + " ".repeat(CopilotBoundedJsonParser.MAX_JSON_BYTES - 4))
                .getBytes(StandardCharsets.UTF_8);

        var parsedDepth = parser.parse(exactDepth.getBytes(StandardCharsets.UTF_8), 10L);
        for (int index = 0; index < CopilotBoundedJsonParser.MAX_NESTING_DEPTH; index++) {
            parsedDepth = parsedDepth.get(0);
        }
        assertEquals(0, parsedDepth.asInt());
        assertEquals(CopilotBoundedJsonParser.MAX_STRING_LENGTH,
                parser.parse(exactString.getBytes(StandardCharsets.UTF_8), 10L).asText().length());
        assertEquals(exactNumber, parser.parse(exactNumber.getBytes(StandardCharsets.UTF_8), 10L).asText());
        assertEquals(CopilotBoundedJsonParser.MAX_JSON_BYTES, exactBodySize.length);
        assertTrue(parser.parse(exactBodySize, 10L).isNull());
    }

    @Test
    void 構造tokenと本文サイズの上限超過を拒否する() {
        CopilotBoundedJsonParser parser = new CopilotBoundedJsonParser(() -> 1L);
        String depth = "[".repeat(CopilotBoundedJsonParser.MAX_NESTING_DEPTH + 1) + "0"
                + "]".repeat(CopilotBoundedJsonParser.MAX_NESTING_DEPTH + 1);
        String string = "\"" + "a".repeat(CopilotBoundedJsonParser.MAX_STRING_LENGTH + 1) + "\"";
        String number = "1" + "0".repeat(CopilotBoundedJsonParser.MAX_NUMBER_LENGTH);
        byte[] oversized = new byte[CopilotBoundedJsonParser.MAX_JSON_BYTES + 1];

        assertInvalid(parser, depth.getBytes(StandardCharsets.UTF_8));
        assertInvalid(parser, string.getBytes(StandardCharsets.UTF_8));
        assertInvalid(parser, number.getBytes(StandardCharsets.UTF_8));
        assertInvalid(parser, oversized);
    }

    @Test
    void 末尾token不正JSONと不正UTF8を拒否する() {
        CopilotBoundedJsonParser parser = new CopilotBoundedJsonParser(() -> 1L);
        assertInvalid(parser, "{} {}".getBytes(StandardCharsets.UTF_8));
        byte[] privateBody = "{\"secret\":\"private-response-marker\"".getBytes(StandardCharsets.UTF_8);
        BusinessException malformed = assertThrows(BusinessException.class,
                () -> parser.parse(privateBody, 10L));
        assertEquals("COPILOT_RESPONSE_INVALID", malformed.getMessage());
        assertFalse(malformed.toString().contains("private-response-marker"));
        assertInvalid(parser, new byte[]{'"', (byte) 0xc3, '"'});
    }

    @Test
    void parse開始前と完了後に単調deadlineを確認する() {
        CopilotBoundedJsonParser expiredBeforeParse = new CopilotBoundedJsonParser(() -> 10L);
        BusinessException before = assertThrows(BusinessException.class,
                () -> expiredBeforeParse.parse("{}".getBytes(StandardCharsets.UTF_8), 10L));
        assertEquals("COPILOT_DEADLINE_EXCEEDED", before.getMessage());

        AtomicInteger reads = new AtomicInteger();
        CopilotBoundedJsonParser expiresAfterParse = new CopilotBoundedJsonParser(
                () -> reads.incrementAndGet() == 1 ? 1L : 11L);
        BusinessException after = assertThrows(BusinessException.class,
                () -> expiresAfterParse.parse("{}".getBytes(StandardCharsets.UTF_8), 10L));
        assertEquals("COPILOT_DEADLINE_EXCEEDED", after.getMessage());
        assertEquals(2, reads.get());
    }

    private static void assertInvalid(CopilotBoundedJsonParser parser, byte[] bytes) {
        BusinessException error = assertThrows(BusinessException.class, () -> parser.parse(bytes, 10L));
        assertEquals("COPILOT_RESPONSE_INVALID", error.getMessage());
    }
}
