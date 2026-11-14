package com.ses.service.integrationhub;

import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.InboundEventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IntegrationHubDeliveryMetricsTest {
    @Test
    void 台帳状態のlabelは固定集合だけでDB障害はNaNにする() {
        var deliveries = mock(ApiDeliveryMapper.class);
        var inbound = mock(InboundEventMapper.class);
        when(deliveries.selectCount(any())).thenReturn(2L);
        when(inbound.selectCount(any())).thenReturn(3L);
        MeterRegistry registry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        new IntegrationHubDeliveryMetrics(deliveries, inbound, provider).register();
        assertThat(registry.getMeters()).hasSize(12);
        registry.getMeters().forEach(meter -> assertThat(meter.getId().getTags())
                .allMatch(tag -> tag.getKey().equals("status") && tag.getValue().matches("[A-Z_]+")));
        assertThat(registry.get("integration_hub_delivery_rows").tag("status", "DLQ").gauge().value()).isEqualTo(2);
        when(deliveries.selectCount(any())).thenThrow(new IllegalStateException("synthetic"));
        assertThat(registry.get("integration_hub_delivery_rows").tag("status", "DLQ").gauge().value()).isNaN();
    }
}
