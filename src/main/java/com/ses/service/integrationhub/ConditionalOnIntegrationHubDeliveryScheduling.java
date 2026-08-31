package com.ses.service.integrationhub;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** transportとアプリschedulerが明示的に有効な場合だけdelivery schedulerを生成する。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Conditional(IntegrationHubDeliverySchedulingCondition.class)
public @interface ConditionalOnIntegrationHubDeliveryScheduling {
}
