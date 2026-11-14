package com.ses.service.integrationhub;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.InboundEventMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/** 台帳の状態を有限labelで公開する。識別子、URL、tenant、秘密値をlabelへ出さない。 */
@Component
@RequiredArgsConstructor
public class IntegrationHubDeliveryMetrics {
    private final ApiDeliveryMapper deliveries;
    private final InboundEventMapper inbound;
    private final ObjectProvider<MeterRegistry> registries;

    @PostConstruct
    void register() {
        MeterRegistry registry = registries.getIfAvailable();
        if (registry == null) return;
        for (String state : List.of("PENDING", "CLAIMED", "RETRYABLE", "SUCCEEDED", "FAILED", "DLQ")) {
            Gauge.builder("integration_hub_delivery_rows", this, ignored -> countDelivery(state))
                    .tag("status", state).register(registry);
        }
        for (String state : List.of("RECEIVED", "PROCESSING", "PROCESSED", "DUPLICATE", "CONFLICT", "DLQ")) {
            Gauge.builder("integration_hub_inbound_rows", this, ignored -> countInbound(state))
                    .tag("status", state).register(registry);
        }
    }

    private double countDelivery(String state) {
        try { return deliveries.selectCount(new QueryWrapper<com.ses.entity.integrationhub.ApiDelivery>().eq("status", state)); }
        catch (RuntimeException unavailable) { return Double.NaN; }
    }
    private double countInbound(String state) {
        try { return inbound.selectCount(new QueryWrapper<com.ses.entity.integrationhub.InboundEvent>().eq("status", state)); }
        catch (RuntimeException unavailable) { return Double.NaN; }
    }
}
