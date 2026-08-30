package com.ses.service.integrationhub;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/** 設定値が明示的に一致した場合だけB1 transportをbean化する。 */
public final class IntegrationHubTransportModeCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attributes = metadata.getAnnotationAttributes(
                ConditionalOnIntegrationHubTransport.class.getName());
        String expectedMode = attributes == null ? null : (String) attributes.get("mode");
        String enabled = context.getEnvironment().getProperty("integration.hub.external-transport.enabled");
        String mode = context.getEnvironment().getProperty("integration.hub.provider.mode");
        return "true".equalsIgnoreCase(enabled) && expectedMode != null && expectedMode.equals(mode);
    }
}
