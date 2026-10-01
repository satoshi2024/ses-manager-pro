package com.ses.config;

import com.ses.service.security.LegalEntityReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;

/** 公開API/Copilotを有効化する構成だけ、未解決法人行を起動時に拒否する。 */
@Component
@RequiredArgsConstructor
public class LegalEntityReadinessStartupValidator {
    private final AiConfig aiConfig;
    private final LegalEntityReadinessService readinessService;
    private final IntegrationHubExternalApiProperties integrationHubProperties;

    @EventListener(ApplicationReadyEvent.class)
    void validate() {
        if (aiConfig.isManagementCopilotEnabled()
                || Boolean.TRUE.equals(integrationHubProperties.getPublicApi().getEnabled())) {
            readinessService.assertReady();
        }
    }
}
