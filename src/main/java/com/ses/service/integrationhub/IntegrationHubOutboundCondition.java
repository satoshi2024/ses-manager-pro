package com.ses.service.integrationhub;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.boot.context.properties.bind.Binder;

/** 全outbound beanで同じBoolean bindingを使う。 */
public final class IntegrationHubOutboundCondition implements Condition {
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return Binder.get(context.getEnvironment()).bind("integration.hub.external-transport.enabled", Boolean.class).orElse(false);
    }
}
