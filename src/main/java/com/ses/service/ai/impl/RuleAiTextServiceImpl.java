package com.ses.service.ai.impl;

import com.ses.service.ai.AiTextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** rule providerは外部egressを持たない決定的なlocal provider。 */
@Slf4j
@Service
@ConditionalOnProperty(name = "ai.provider", havingValue = "rule")
public class RuleAiTextServiceImpl implements AiTextService {

    @Override
    public String providerId() {
        return "rule";
    }

    @Override
    public String generate(String prompt) {
        log.debug("RuleAiTextServiceImpl: 決定的なlocal応答を返します（promptLength={})",
                prompt == null ? 0 : prompt.length());
        return MockAiResponses.generate(prompt);
    }
}
