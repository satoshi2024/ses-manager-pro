package com.ses.config;

import com.ses.service.security.LegalEntityReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;

/**
 * 公開API（inbound）/外部transport（outbound）/Copilotを有効化する構成だけ、未解決法人行を起動時に拒否する。
 * outboundだけを開けた場合もpayloadのprimary bindingが法人scopeに依存するため対象に含める（N5-P2-3）。
 */
@Component
@RequiredArgsConstructor
public class LegalEntityReadinessStartupValidator {
    private final AiConfig aiConfig;
    private final LegalEntityReadinessService readinessService;
    private final IntegrationHubExternalApiProperties integrationHubProperties;

    @EventListener(ApplicationReadyEvent.class)
    void validate() {
        if (aiConfig.isManagementCopilotEnabled()
                || Boolean.TRUE.equals(integrationHubProperties.getPublicApi().getEnabled())
                || Boolean.TRUE.equals(integrationHubProperties.getExternalTransport().getEnabled())) {
            readinessService.assertReady();
        }
    }
}
