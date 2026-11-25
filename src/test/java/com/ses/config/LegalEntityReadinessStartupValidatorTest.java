package com.ses.config;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.service.security.LegalEntityReadinessService;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** N5-P2-3: 法人readinessはinbound・outbound・Copilotのどれか一つでも有効なら起動時に検査する。 */
class LegalEntityReadinessStartupValidatorTest {

    @Test
    void outboundOnlyAlsoRequiresLegalEntityReadiness() {
        LegalEntityReadinessService readiness = mock(LegalEntityReadinessService.class);
        new LegalEntityReadinessStartupValidator(new AiConfig(), readiness, properties(false, true)).validate();
        verify(readiness, times(1)).assertReady();
    }

    @Test
    void inboundOnlyRequiresLegalEntityReadiness() {
        LegalEntityReadinessService readiness = mock(LegalEntityReadinessService.class);
        new LegalEntityReadinessStartupValidator(new AiConfig(), readiness, properties(true, false)).validate();
        verify(readiness, times(1)).assertReady();
    }

    @Test
    void allDirectionsOffAndCopilotOffSkipTheCheck() {
        LegalEntityReadinessService readiness = mock(LegalEntityReadinessService.class);
        new LegalEntityReadinessStartupValidator(new AiConfig(), readiness, properties(false, false)).validate();
        verify(readiness, never()).assertReady();
    }

    /**
     * FE-D02（design §9.2・review P3-5）: DB の源では NF05 の部分をここ（port を開いた後）で判定せず、port を開く前の起動の後の反映が
     * 自動閉鎖する。management copilot は従来どおり例外で止める。
     */
    @Test
    void DBの源ではNF05をここで判定せずcopilotは従来どおり() {
        com.ses.featureenablement.config.FeatureEnablementProperties database =
                new com.ses.featureenablement.config.FeatureEnablementProperties();
        database.setSource(com.ses.featureenablement.config.FeatureEnablementProperties.Source.DATABASE);
        LegalEntityReadinessService readiness = mock(LegalEntityReadinessService.class);
        LegalEntityReadinessStartupValidator nf05 = new LegalEntityReadinessStartupValidator(new AiConfig(), readiness,
                properties(true, true));
        nf05.setFeatureEnablementProperties(database);
        nf05.validate();
        verify(readiness, never()).assertReady();

        AiConfig copilot = new AiConfig();
        copilot.setManagementCopilotEnabled(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic unresolved")).when(readiness).assertReady();
        LegalEntityReadinessStartupValidator withCopilot = new LegalEntityReadinessStartupValidator(copilot, readiness,
                properties(false, false));
        withCopilot.setFeatureEnablementProperties(database);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, withCopilot::validate);
    }

    private static IntegrationHubExternalApiProperties properties(boolean inbound, boolean outbound) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(inbound);
        properties.getExternalTransport().setEnabled(outbound);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);
        return properties;
    }
}
