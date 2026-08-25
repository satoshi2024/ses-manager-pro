package com.ses.service.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 直近の provider 向けプロンプト。テストの canary 検査用。本文はログに出さない。
 * <p>
 * 本番では {@code ai.outbound-probe.enabled=false}（既定）のため記録しない（REV-B2-P2-002）。
 * テストプロファイルのみ {@code application-test.yml} で有効化する。
 */
@Component
public class AiOutboundProbe {

    private final boolean enabled;
    private final AtomicReference<String> lastOutbound = new AtomicReference<>();

    public AiOutboundProbe(
            @Value("${ai.outbound-probe.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public void record(String prompt) {
        if (!enabled) {
            return;
        }
        lastOutbound.set(prompt);
    }

    public String lastOutbound() {
        return lastOutbound.get();
    }

    /** テスト用: 直近の outbound 記録を消す。 */
    public void clear() {
        lastOutbound.set(null);
    }

    public boolean isEnabled() {
        return enabled;
    }
}
