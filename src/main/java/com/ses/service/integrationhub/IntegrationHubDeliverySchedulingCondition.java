package com.ses.service.integrationhub;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** scheduler flagの欠損は生成しない側へ倒す。 */
public final class IntegrationHubDeliverySchedulingCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        String transport = context.getEnvironment().getProperty("integration.hub.external-transport.enabled");
        String scheduling = context.getEnvironment().getProperty("app.scheduling.enabled");
        return "true".equalsIgnoreCase(transport)
                && (scheduling == null || "true".equalsIgnoreCase(scheduling));
    }
}
