package com.ses.service.ai.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF08 legacy local-only fallbackが解決済みscope外のIDを生成しないことを確認する。 */
class AiMatchingServiceImplIsolationTest {

    private final AiMatchingServiceImpl service = new AiMatchingServiceImpl();

    @Test
    void 固定のscope外IDを返さずfailClosedする() {
        assertTrue(service.findMatchingProjects(100L).isEmpty());
        assertTrue(service.findMatchingEngineers(200L).isEmpty());
    }
}
