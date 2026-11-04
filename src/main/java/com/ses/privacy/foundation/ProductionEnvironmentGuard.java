package com.ses.privacy.foundation;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.Objects;

/** trustedな起動環境だけでproduction policy検証を許可するpackage内guard。 */
final class ProductionEnvironmentGuard {
    static final String PRODUCTION_AUDIENCE = "PRODUCTION";

    private final Environment environment;

    ProductionEnvironmentGuard(Environment environment) {
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    void requireProductionProfile() {
        boolean activeProd = Arrays.stream(environment.getActiveProfiles()).anyMatch("prod"::equals);
        if (!activeProd) {
            throw new SecurityException("prod profileが有効ではありません");
        }
    }

    void requireProductionAudience(String audience) {
        if (!PRODUCTION_AUDIENCE.equals(audience)) {
            throw new SecurityException("production policy audienceが一致しません");
        }
    }
}
