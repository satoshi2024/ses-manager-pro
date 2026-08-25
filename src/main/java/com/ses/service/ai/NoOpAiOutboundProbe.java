package com.ses.service.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 本番・開発用: プロンプトを保持しない。
 */
@Component
@Profile("!test")
public class NoOpAiOutboundProbe implements AiOutboundProbe {

    @Override
    public void record(String prompt) {
        // no-op
    }

    @Override
    public String lastOutbound() {
        return null;
    }

    @Override
    public void clear() {
        // no-op
    }
}
