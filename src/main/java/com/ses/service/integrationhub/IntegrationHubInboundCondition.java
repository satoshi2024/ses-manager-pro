package com.ses.service.integrationhub;
import org.springframework.context.annotation.*;
import org.springframework.core.type.AnnotatedTypeMetadata;
/** inboundのBoolean表記を全生成条件で統一する。 */
public final class IntegrationHubInboundCondition implements Condition {
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        return org.springframework.boot.context.properties.bind.Binder.get(context.getEnvironment())
                .bind("integration.hub.public-api.enabled", Boolean.class).orElse(false);
    }
}
