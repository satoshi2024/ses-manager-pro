package com.ses.service;

import com.ses.service.provider.ExternalAccountProviderClient;
import com.ses.service.provider.impl.FailClosedExternalAccountProviderClient;
import com.ses.service.provider.impl.MockExternalAccountProviderClientImpl;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalAccountProviderProfileTest {

    @Test
    void devAndTestCanUseMockByDefault() {
        try (var context = context(new String[]{"dev"}, null)) {
            assertThat(context.getBean(ExternalAccountProviderClient.class))
                    .isInstanceOf(MockExternalAccountProviderClientImpl.class);
        }
        try (var context = context(new String[]{"test"}, "mock")) {
            assertThat(context.getBean(ExternalAccountProviderClient.class))
                    .isInstanceOf(MockExternalAccountProviderClientImpl.class);
        }
    }

    @Test
    void prodOnlyAndMixedProfilesNeverSelectMockEvenWhenConfigured() {
        for (String[] profiles : new String[][]{{"prod"}, {"prod", "test"}}) {
            try (var context = context(profiles, "mock")) {
                ExternalAccountProviderClient provider = context.getBean(ExternalAccountProviderClient.class);
                assertThat(provider).isInstanceOf(FailClosedExternalAccountProviderClient.class);
                assertThat(provider.requestRevoke(null)).isFalse();
                assertThat(provider.checkRevokeConfirmation(null))
                        .isEqualTo(ExternalAccountProviderClient.RevokeConfirmationStatus.UNKNOWN);
                assertThat(context.getBeansOfType(MockExternalAccountProviderClientImpl.class)).isEmpty();
            }
        }
    }

    @Test
    void missingOrUnknownProviderFailsClosedWithoutFallbackToMock() {
        for (String configuredProvider : new String[]{null, "unrecognized"}) {
            try (var context = context(new String[]{"prod"}, configuredProvider)) {
                ExternalAccountProviderClient provider = context.getBean(ExternalAccountProviderClient.class);
                assertThat(provider).isInstanceOf(FailClosedExternalAccountProviderClient.class);
                assertThat(provider.requestRevoke(null)).isFalse();
                assertThat(provider.checkRevokeConfirmation(null))
                        .isEqualTo(ExternalAccountProviderClient.RevokeConfirmationStatus.UNKNOWN);
                assertThat(context.getBeansOfType(MockExternalAccountProviderClientImpl.class)).isEmpty();
            }
        }
    }

    @Test
    void unknownProviderOutsideProdAlsoDoesNotSilentlySelectMock() {
        try (var context = context(new String[]{"dev"}, "unrecognized")) {
            assertThat(context.getBean(ExternalAccountProviderClient.class))
                    .isInstanceOf(FailClosedExternalAccountProviderClient.class);
        }
    }

    private AnnotationConfigApplicationContext context(String[] profiles, String provider) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        if (provider != null) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("test-provider", Map.of("external-account.provider", provider)));
        }
        context.register(MockExternalAccountProviderClientImpl.class, FailClosedExternalAccountProviderClient.class);
        context.refresh();
        return context;
    }
}
