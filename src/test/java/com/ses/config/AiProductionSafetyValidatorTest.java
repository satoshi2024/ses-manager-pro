package com.ses.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

/** NF-08 本番profileのAI安全起動条件を確認する。 */
class AiProductionSafetyValidatorTest {

    @Test
    void 未承認のmanagementCopilotは本番起動を拒否する() {
        AiConfig config = new AiConfig();
        config.setManagementCopilotEnabled(true);

        assertThrows(IllegalStateException.class,
                () -> new AiProductionSafetyValidator(config).validate());
    }

    @Test
    void externalSendは本番起動を拒否する() {
        AiConfig config = new AiConfig();
        config.setExternalSendEnabled(true);

        assertThrows(IllegalStateException.class,
                () -> new AiProductionSafetyValidator(config).validate());
    }
}
