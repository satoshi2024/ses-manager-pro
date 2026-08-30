package com.ses.service.integrationhub;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntegrationHubWebhookBackoffPolicyTest {
    @Test
    void exponentialBackoffとjitterはbounded() {
        IntegrationHubWebhookBackoffPolicy policy = new IntegrationHubWebhookBackoffPolicy(() -> 2);
        assertEquals(Duration.ofSeconds(7), policy.delayForAttempt(1));
        assertEquals(Duration.ofSeconds(12), policy.delayForAttempt(2));
        assertEquals(Duration.ofSeconds(642), policy.delayForAttempt(8));
        assertThrows(IllegalArgumentException.class, () -> policy.delayForAttempt(0));
        assertThrows(IllegalArgumentException.class, () -> policy.delayForAttempt(9));
    }
}
