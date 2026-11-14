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

    private static IntegrationHubExternalApiProperties properties(boolean inbound, boolean outbound) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(inbound);
        properties.getExternalTransport().setEnabled(outbound);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);
        return properties;
    }
}
