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
@org.springframework.context.annotation.Conditional(IntegrationHubRetentionPurgeCondition.class)
@RequiredArgsConstructor
public class IntegrationHubDeliveryMetrics {
    private final ApiDeliveryMapper deliveries;
    private final InboundEventMapper inbound;
    private final ObjectProvider<MeterRegistry> registries;
    @org.springframework.beans.factory.annotation.Autowired private java.time.Clock clock = java.time.Clock.systemUTC();
    private java.time.Instant expires = java.time.Instant.MIN;
    private final java.util.Map<String, Double> cached = new java.util.HashMap<>();

    @PostConstruct
    void register() {
        MeterRegistry registry = registries.getIfAvailable();
        if (registry == null) return;
        for (String state : List.of("PENDING", "CLAIMED", "RETRYABLE", "SUCCEEDED", "FAILED", "DLQ")) {
            Gauge.builder("integration_hub_delivery_rows", this, ignored -> sample("DELIVERY", state))
                    .tag("status", state).register(registry);
        }
        for (String state : List.of("RECEIVED", "PROCESSING", "PROCESSED", "DUPLICATE", "CONFLICT", "DLQ")) {
            Gauge.builder("integration_hub_inbound_rows", this, ignored -> sample("INBOUND", state))
                    .tag("status", state).register(registry);
        }
    }

    private synchronized double sample(String kind, String state) {
        if (!clock.instant().isBefore(expires)) {
            for (String s : List.of("PENDING", "CLAIMED", "RETRYABLE", "SUCCEEDED", "FAILED", "DLQ"))
                cached.put("DELIVERY:" + s, countDelivery(s));
            for (String s : List.of("RECEIVED", "PROCESSING", "PROCESSED", "DUPLICATE", "CONFLICT", "DLQ"))
                cached.put("INBOUND:" + s, countInbound(s));
            expires = clock.instant().plusSeconds(30);
        }
        return cached.get(kind + ":" + state);
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
