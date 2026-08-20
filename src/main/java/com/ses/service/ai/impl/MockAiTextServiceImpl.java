package com.ses.service.ai.impl;

import com.ses.service.ai.AiTextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@ConditionalOnExpression("!'gemini'.equals('${ai.provider:mock}')")
public class MockAiTextServiceImpl implements AiTextService {

    @Override
    public String generate(String prompt) {
        log.debug("MockAiTextServiceImpl: モック応答を返します（promptLength={})",
                prompt == null ? 0 : prompt.length());
        return MockAiResponses.generate(prompt);
    }
}
