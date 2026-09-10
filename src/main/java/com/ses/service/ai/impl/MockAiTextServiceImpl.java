package com.ses.service.ai.impl;

import com.ses.service.ai.AiTextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "ai.provider", havingValue = "mock", matchIfMissing = true)
public class MockAiTextServiceImpl implements AiTextService {

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
}
