package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductionEnvironmentGuardTest {

    @Test
    void acceptsOnlyTrustedProdProfileAndProductionAudience() {
        ProductionEnvironmentGuard guard = new ProductionEnvironmentGuard(prodEnvironment());

        assertDoesNotThrow(guard::requireProductionProfile);
        assertDoesNotThrow(() -> guard.requireProductionAudience("PRODUCTION"));
    }

    @Test
    void rejectsNonProdProfile() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        ProductionEnvironmentGuard guard = new ProductionEnvironmentGuard(environment);

        assertThrows(SecurityException.class, guard::requireProductionProfile);
    }

    @Test
    void rejectsProdDefaultProfileWhenNoActiveProfileExists() {
        MockEnvironment environment = new MockEnvironment();
        environment.setDefaultProfiles("prod");
        ProductionEnvironmentGuard guard = new ProductionEnvironmentGuard(environment);

        assertThrows(SecurityException.class, guard::requireProductionProfile);
    }

    @Test
    void rejectsSyntheticAndUnknownAudiences() {
        ProductionEnvironmentGuard guard = new ProductionEnvironmentGuard(prodEnvironment());

        assertThrows(SecurityException.class,
                () -> guard.requireProductionAudience(SyntheticEnvironmentGuard.SYNTHETIC_AUDIENCE));
        assertThrows(SecurityException.class, () -> guard.requireProductionAudience("unknown"));
    }

    private static MockEnvironment prodEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }
}
