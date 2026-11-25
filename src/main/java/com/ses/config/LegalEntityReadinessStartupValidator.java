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
 *
 * <p>NF05 の開閉の源が DB（{@code app.feature-enablement.source=database}。prod）の時は、NF05 の部分をここでは判定しない。port を開く前の
 * 起動の後の反映（{@code IntegrationHubBootDecisionRecorder}）が判定し、未解決なら起動を止めずに NF05 を {@code AUTO_CLOSED} にする
 * （{@code ApplicationReadyEvent} は port を開いた後のため。feature-enablement-console design §9.2・review P3-5）。management copilot の
 * 部分は従来どおり例外で起動を止める。property の源（dev・test）の NF05 も従来どおり。</p>
 */
@Component
@RequiredArgsConstructor
public class LegalEntityReadinessStartupValidator {
    private final AiConfig aiConfig;
    private final LegalEntityReadinessService readinessService;
    private final IntegrationHubExternalApiProperties integrationHubProperties;
    /** 機能の開放の運用値（Spring 管理外で {@code new} した試験では無く、property の源として扱う）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.featureenablement.config.FeatureEnablementProperties featureEnablementProperties;

    @EventListener(ApplicationReadyEvent.class)
    void validate() {
        boolean nf05HandledBeforePort = featureEnablementProperties != null && featureEnablementProperties.databaseSource();
        boolean nf05 = !nf05HandledBeforePort
                && (Boolean.TRUE.equals(integrationHubProperties.getPublicApi().getEnabled())
                || Boolean.TRUE.equals(integrationHubProperties.getExternalTransport().getEnabled()));
        if (aiConfig.isManagementCopilotEnabled() || nf05) {
            readinessService.assertReady();
        }
    }

    /** 試験用（機能の開放の運用値を差し替える）。 */
    void setFeatureEnablementProperties(com.ses.featureenablement.config.FeatureEnablementProperties properties) {
        this.featureEnablementProperties = properties;
    }
}
