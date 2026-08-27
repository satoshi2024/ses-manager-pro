package com.ses.service.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class AiOutboundProbeTest {

    @Test
    void Recordingは保持しNoOpは保持しない() {
        RecordingAiOutboundProbe recording = new RecordingAiOutboundProbe();
        recording.record("SECRET_PROMPT");
        assertThat(recording.lastOutbound()).isEqualTo("SECRET_PROMPT");
        recording.clear();
        assertThat(recording.lastOutbound()).isNull();

        NoOpAiOutboundProbe noOp = new NoOpAiOutboundProbe();
        noOp.record("SECRET_PROMPT");
        assertThat(noOp.lastOutbound()).isNull();
    }

    @Test
    void testプロファイルではRecordingがロードされる() {
        new ApplicationContextRunner()
                .withUserConfiguration(RecordingAiOutboundProbe.class, NoOpAiOutboundProbe.class)
                .withPropertyValues("spring.profiles.active=test")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(AiOutboundProbe.class)).isInstanceOf(RecordingAiOutboundProbe.class);
                });
    }

    @Test
    void prodでoutbound_probe_enabledは起動失敗する() {
        new ApplicationContextRunner()
                .withUserConfiguration(NoOpAiOutboundProbe.class, com.ses.config.AiOutboundProbeProdGuardConfig.class)
                .withPropertyValues("spring.profiles.active=prod", "ai.outbound-probe.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
