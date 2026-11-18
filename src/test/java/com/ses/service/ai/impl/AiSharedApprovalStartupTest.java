package com.ses.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.config.AiConfig;
import com.ses.mapper.AiArtifactVersionMapper;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.ai.*;
import com.ses.service.ai.copilot.CopilotFeatureGate;
import com.ses.common.security.OutboundUrlGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** DIと同じconstructor経由で三つの利用者が起動判定を取り直さないことを確認する。 */
class AiSharedApprovalStartupTest {
    @Test
    void gatewayとmonitorとadapterはgateの一つの起動判定に従う() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-13T01:00:00Z"), ZoneOffset.UTC);
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(LocalDate.of(2026, 11, 13));
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config, clock);
        var startup = gate.startupExternalSendStatus().approval();
        config.setExternalSendEnabled(false);
        GeminiTextServiceImpl adapter = new GeminiTextServiceImpl(config, mock(RestTemplate.class),
                new ObjectMapper(), mock(OutboundUrlGuard.class), mock(CopilotBoundedHttpTransport.class), gate, clock);
        AiExecutionGatewayImpl gateway = new AiExecutionGatewayImpl(adapter, config, mock(AiOutboundProbe.class),
                new ObjectMapper(), mock(AiArtifactVersionMapper.class), mock(AiRecommendationRunMapper.class),
                mock(PlatformTransactionManager.class), clock, gate, new CopilotFeatureGate(config, gate));
        AiExternalSendApprovalMonitor monitor = new AiExternalSendApprovalMonitor(config, gate, clock, (MeterRegistry) null);
        assertThat(ReflectionTestUtils.getField(gateway, "startupExternalSendApproval")).isSameAs(startup);
        assertThat(ReflectionTestUtils.getField(monitor, "startupApproval")).isSameAs(startup);
        assertThat(ReflectionTestUtils.getField(adapter, "approvedDigest")).isEqualTo(startup.approvalDigest());
        assertThat(ReflectionTestUtils.getField(adapter, "approvalClock")).isSameAs(clock);
        assertThat(gate.externalSendStatus().approved()).isFalse();
    }
}
