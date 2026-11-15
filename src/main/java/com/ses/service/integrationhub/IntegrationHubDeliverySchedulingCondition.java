package com.ses.service.integrationhub;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** scheduler flagの欠損は生成しない側へ倒す。 */
public final class IntegrationHubDeliverySchedulingCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var binder = org.springframework.boot.context.properties.bind.Binder.get(context.getEnvironment());
        return binder.bind("integration.hub.external-transport.enabled", Boolean.class).orElse(false)
                && binder.bind("app.scheduling.enabled", Boolean.class).orElse(true);
    }
}
