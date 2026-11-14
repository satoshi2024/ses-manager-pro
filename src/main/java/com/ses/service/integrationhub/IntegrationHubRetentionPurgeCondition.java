package com.ses.service.integrationhub;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * retention/nonce purge schedulerの生成条件（N5-P2-3、Q-12）。
 *
 * <p>public-api（inbound）またはexternal-transport（outbound）のどちらかが明示的にtrueなら生成する。
 * outboundだけを開けた場合もdelivery payload（30/90日）とreplay監査（1年）をpurgeする。
 * 値の欠落・不正は生成しない側へ倒す（起動validatorが欠落自体を拒否する）。
 */
public final class IntegrationHubRetentionPurgeCondition implements Condition {
    static final String PUBLIC_API_ENABLED = "integration.hub.public-api.enabled";
    static final String EXTERNAL_TRANSPORT_ENABLED = "integration.hub.external-transport.enabled";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String inbound = context.getEnvironment().getProperty(PUBLIC_API_ENABLED);
        String outbound = context.getEnvironment().getProperty(EXTERNAL_TRANSPORT_ENABLED);
        return "true".equalsIgnoreCase(inbound) || "true".equalsIgnoreCase(outbound);
    }
}
