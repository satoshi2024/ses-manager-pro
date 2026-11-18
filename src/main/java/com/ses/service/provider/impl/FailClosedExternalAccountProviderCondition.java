package com.ses.service.provider.impl;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Profiles;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** prod または mock 以外の設定で fail-closed provider を選択する。 */
public class FailClosedExternalAccountProviderCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        return environment.acceptsProfiles(Profiles.of("prod"))
                || !"mock".equalsIgnoreCase(environment.getProperty("external-account.provider", "mock"));
    }
}
