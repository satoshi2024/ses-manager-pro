package com.ses.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

/** OIDC有効時だけissuer discoveryを行うclient registration設定。 */
@Configuration
@EnableConfigurationProperties({OidcSecurityProperties.class, MfaSecurityProperties.class,
        PersistentSessionProperties.class})
public class OidcClientRegistrationConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.security.oidc", name = "enabled", havingValue = "true")
    ClientRegistrationRepository oidcClientRegistrationRepository(OidcSecurityProperties properties) {
        if (properties.getIssuerUri() == null || properties.getIssuerUri().isBlank()
                || properties.getClientId() == null || properties.getClientId().isBlank()
                || properties.getClientSecret() == null || properties.getClientSecret().isBlank()) {
            throw new IllegalStateException("OIDC有効時はissuer-uri、client-id、client-secretが必須です");
        }
        ClientRegistration registration = ClientRegistrations.fromIssuerLocation(properties.getIssuerUri())
                .registrationId(properties.getProviderRegistrationId())
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .scope("openid", "profile", "email")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }
}
