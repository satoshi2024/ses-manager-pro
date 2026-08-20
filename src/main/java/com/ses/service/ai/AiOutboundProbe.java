package com.ses.service.ai;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 直近の provider 向けプロンプト。テストの canary 検査用。本文はログに出さない。
 */
@Component
public class AiOutboundProbe {

    private final AtomicReference<String> lastOutbound = new AtomicReference<>();

    public void record(String prompt) {
        lastOutbound.set(prompt);
    }

    public String lastOutbound() {
        return lastOutbound.get();
    }
}
