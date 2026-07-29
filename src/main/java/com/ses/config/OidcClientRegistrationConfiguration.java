package com.ses.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.util.StringUtils;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.endpoint.DefaultAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/** OIDC有効時も起動時のremote discoveryへ依存しない固定metadata設定。 */
@Configuration
@EnableConfigurationProperties({OidcSecurityProperties.class, MfaSecurityProperties.class,
        PersistentSessionProperties.class})
public class OidcClientRegistrationConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "app.security.oidc", name = "enabled", havingValue = "true")
    ClientRegistrationRepository oidcClientRegistrationRepository(OidcSecurityProperties properties) {
        if (!StringUtils.hasText(properties.getIssuerUri())
                || !StringUtils.hasText(properties.getAuthorizationUri())
                || !StringUtils.hasText(properties.getTokenUri())
                || !StringUtils.hasText(properties.getJwkSetUri())
                || !StringUtils.hasText(properties.getUserInfoUri())
                || !StringUtils.hasText(properties.getClientId())
                || !StringUtils.hasText(properties.getClientSecret())) {
            throw new IllegalStateException("OIDC有効時はissuerと固定provider metadata、client credentialが必須です");
        }
        ClientRegistration registration = ClientRegistration.withRegistrationId(properties.getProviderRegistrationId())
                .clientId(properties.getClientId())
                .clientSecret(properties.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri(properties.getAuthorizationUri())
                .tokenUri(properties.getTokenUri())
                .jwkSetUri(properties.getJwkSetUri())
                .userInfoUri(properties.getUserInfoUri())
                .userNameAttributeName("sub")
                .issuerUri(properties.getIssuerUri())
                .clientName("Enterprise OIDC")
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.security.oidc", name = "enabled", havingValue = "true")
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> oidcTokenResponseClient(
            OidcSecurityProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.max(100, properties.getConnectTimeoutMillis()));
        requestFactory.setReadTimeout(Math.max(100, properties.getReadTimeoutMillis()));
        RestTemplate restTemplate = new RestTemplate(List.of(
                new FormHttpMessageConverter(), new OAuth2AccessTokenResponseHttpMessageConverter()));
        restTemplate.setRequestFactory(requestFactory);
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        DefaultAuthorizationCodeTokenResponseClient client = new DefaultAuthorizationCodeTokenResponseClient();
        client.setRestOperations(restTemplate);
        return client;
    }
}
