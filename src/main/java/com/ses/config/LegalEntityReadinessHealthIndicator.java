package com.ses.config;

import com.ses.service.security.LegalEntityReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** 法人不整合をhealthにも反映し、起動後に投入された不正行を隠さない。 */
@Component("legalEntityReadiness")
@RequiredArgsConstructor
public class LegalEntityReadinessHealthIndicator implements HealthIndicator {
    private final LegalEntityReadinessService readinessService;

    @Override
    public Health health() {
        try {
            readinessService.assertReady();
            return Health.up().build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("reason", "LEGAL_ENTITY_BACKFILL_INCOMPLETE").build();
        }
    }
}
