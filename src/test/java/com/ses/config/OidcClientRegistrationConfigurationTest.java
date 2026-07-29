package com.ses.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class OidcClientRegistrationConfigurationTest {

    @Test
    void providerが到達不能でも固定metadataだけで起動用registrationを構築できる() {
        OidcSecurityProperties properties = new OidcSecurityProperties();
        properties.setEnabled(true);
        properties.setIssuerUri("http://127.0.0.1:1/unreachable");
        properties.setAuthorizationUri("http://127.0.0.1:1/authorize");
        properties.setTokenUri("http://127.0.0.1:1/token");
        properties.setJwkSetUri("http://127.0.0.1:1/jwks");
        properties.setUserInfoUri("http://127.0.0.1:1/userinfo");
        properties.setClientId("test-client");
        properties.setClientSecret("test-secret");
        OidcClientRegistrationConfiguration configuration = new OidcClientRegistrationConfiguration();

        ClientRegistrationRepository repository = assertTimeoutPreemptively(Duration.ofSeconds(1),
                () -> configuration.oidcClientRegistrationRepository(properties));
        ClientRegistration registration = repository.findByRegistrationId("enterprise");

        assertEquals("http://127.0.0.1:1/unreachable", registration.getProviderDetails().getIssuerUri());
        assertEquals("http://127.0.0.1:1/jwks", registration.getProviderDetails().getJwkSetUri());
    }
}
