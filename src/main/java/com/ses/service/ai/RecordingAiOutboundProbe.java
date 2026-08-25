package com.ses.service.ai;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * テスト専用: 直近プロンプトを保持する。{@code test} プロファイル以外では Bean 化しない。
 */
@Component
@Profile("test")
public class RecordingAiOutboundProbe implements AiOutboundProbe {

    private final AtomicReference<String> lastOutbound = new AtomicReference<>();

    @Override
    public void record(String prompt) {
        lastOutbound.set(prompt);
    }

    @Override
    public String lastOutbound() {
        return lastOutbound.get();
    }

    @Override
    public void clear() {
        lastOutbound.set(null);
    }
}
