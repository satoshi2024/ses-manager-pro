package com.ses.service.ai.impl;

import com.ses.service.ai.AiTextService;
import com.ses.service.ai.CopilotBoundedAiTextService;
import com.ses.service.ai.copilot.summary.CopilotSummaryPromptTemplate;
import com.ses.service.ai.copilot.CopilotConfigSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiTextServiceImpl implements CopilotBoundedAiTextService {

    private static final String MODEL_ID = "mock-management-copilot";
    private static final CopilotConfigSnapshot COPILOT_CONFIG = new CopilotConfigSnapshot(
            "mock", "mock", "", MODEL_ID, CopilotSummaryPromptTemplate.VERSION,
            CopilotSummaryPromptTemplate.localConfigHash("mock", MODEL_ID), 0);

    @Override
    public String providerId() {
        return "mock";
    }

    @Override
    public String generate(String prompt) {
        log.debug("MockAiTextServiceImpl: モック応答を返します（promptLength={})",
                prompt == null ? 0 : prompt.length());
        return MockAiResponses.generate(prompt);
    }

    @Override
    public String copilotModelId() {
        return COPILOT_CONFIG.modelId();
    }

    @Override
    public String copilotPromptVersion() {
        return COPILOT_CONFIG.promptVersion();
    }

    @Override
    public String copilotConfigHash() {
        return COPILOT_CONFIG.configHash();
    }

    @Override
    public String generateCopilotSummary(String prompt, long deadlineNanos) {
        if (System.nanoTime() >= deadlineNanos) {
            throw com.ses.common.exception.BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
        }
        String result = generate(prompt);
        if (System.nanoTime() >= deadlineNanos) {
            throw com.ses.common.exception.BusinessException.of(504, "COPILOT_DEADLINE_EXCEEDED");
        }
        return result;
    }
}
