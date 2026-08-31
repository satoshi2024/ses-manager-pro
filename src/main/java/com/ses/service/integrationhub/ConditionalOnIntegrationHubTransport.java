package com.ses.service.integrationhub;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** external-transport flagとprovider modeを一つのfail-closed条件で評価する。 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(IntegrationHubTransportModeCondition.class)
public @interface ConditionalOnIntegrationHubTransport {
    String mode();
}
