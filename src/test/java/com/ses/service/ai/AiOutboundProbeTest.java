package com.ses.service.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiOutboundProbeTest {

    @Test
    void 無効時はプロンプトを保持しない() {
        AiOutboundProbe probe = new AiOutboundProbe(false);
        probe.record("SECRET_PROMPT");
        assertThat(probe.isEnabled()).isFalse();
        assertThat(probe.lastOutbound()).isNull();
    }

    @Test
    void 有効時のみ記録する() {
        AiOutboundProbe probe = new AiOutboundProbe(true);
        probe.record("PROMPT");
        assertThat(probe.lastOutbound()).isEqualTo("PROMPT");
        probe.clear();
        assertThat(probe.lastOutbound()).isNull();
    }
}
