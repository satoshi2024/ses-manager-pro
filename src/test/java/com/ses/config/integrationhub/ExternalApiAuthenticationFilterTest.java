package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.integrationhub.ApiClient;
import com.ses.entity.integrationhub.CredentialVersion;
import com.ses.service.integrationhub.ApiClientService;
import com.ses.service.integrationhub.ApiNonceReplayService;
import com.ses.service.integrationhub.CredentialVersionService;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalApiAuthenticationFilterTest {
    private static final Instant NOW = Instant.parse("2026-08-30T00:00:00Z");
    private static final String TIMESTAMP = "1788048000";
    private static final byte[] BODY = "{}".getBytes(StandardCharsets.UTF_8);
    private static final String TARGET = "/external-api/v1/projects?filter=active";

    @AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void validClientIsAuthenticatedAfterIpSignatureAndNonceChecks() throws Exception {
        ApiClientService clientService = mock(ApiClientService.class);
        CredentialVersionService credentialService = mock(CredentialVersionService.class);
        ApiNonceReplayService nonceService = mock(ApiNonceReplayService.class);
        IntegrationHubSecretCryptoService crypto = mock(IntegrationHubSecretCryptoService.class);
        ExternalApiAuthenticationFilter filter = filter(clientService, credentialService, nonceService, crypto);

        ApiClient client = ApiClient.builder().id(7L).clientId("client-a").tenantId("tenant-a")
                .dataScopeJson("{\"engineerIds\":[\"e-1\"]}").allowedCidrs("203.0.113.0/24")
                .clientTier("STANDARD").status("ACTIVE").build();
        CredentialVersion credential = CredentialVersion.builder().apiClientId(7L).credentialVersion(1)
                .keyId("key-1").encryptedSecret("envelope").status("ACTIVE")
                .issuedAt(LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC))
                .expiresAt(LocalDateTime.ofInstant(NOW.plusSeconds(86_400), ZoneOffset.UTC)).build();
        when(clientService.getByClientId("client-a")).thenReturn(client);
        when(credentialService.getByClientAndVersion(7L, 1)).thenReturn(credential);
        when(crypto.decrypt("client-a", 1, "credential", "envelope")).thenReturn("test-secret");
        when(nonceService.accept(eq("client-a"), eq(1), any(byte[].class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(true);

        MockHttpServletRequest request = signedRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        FilterChain chain = (req, res) -> chainCalled.set(true);

        filter.doFilter(request, response, chain);

        assertTrue(chainCalled.get());
        assertTrue(org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication() instanceof ExternalApiAuthenticationToken);
        assertEquals("client-a", org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication().getName());
        verify(nonceService).accept(eq("client-a"), eq(1), any(byte[].class), any(LocalDateTime.class), any(LocalDateTime.class));
    }

    @Test
    void replayIsRejectedAndDoesNotReachController() throws Exception {
        ApiClientService clientService = mock(ApiClientService.class);
        CredentialVersionService credentialService = mock(CredentialVersionService.class);
        ApiNonceReplayService nonceService = mock(ApiNonceReplayService.class);
        IntegrationHubSecretCryptoService crypto = mock(IntegrationHubSecretCryptoService.class);
        ExternalApiAuthenticationFilter filter = filter(clientService, credentialService, nonceService, crypto);
        when(clientService.getByClientId("client-a")).thenReturn(activeClient());
        when(credentialService.getByClientAndVersion(7L, 1)).thenReturn(activeCredential());
        when(crypto.decrypt("client-a", 1, "credential", "envelope")).thenReturn("test-secret");
        when(nonceService.accept(eq("client-a"), eq(1), any(byte[].class), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(false);

        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        filter.doFilter(signedRequest(), response, (req, res) -> chainCalled.set(true));

        assertFalse(chainCalled.get());
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTHENTICATION_FAILED"));
        assertFalse(response.getContentAsString().contains("test-secret"));
    }

    @Test
    void browserAndSessionBoundRequestsAreRejectedBeforeDatabaseLookups() throws Exception {
        ApiClientService clientService = mock(ApiClientService.class);
        ExternalApiAuthenticationFilter filter = filter(clientService, mock(CredentialVersionService.class),
                mock(ApiNonceReplayService.class), mock(IntegrationHubSecretCryptoService.class));
        MockHttpServletRequest request = signedRequest();
        request.addHeader("Origin", "https://evil.example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertEquals(401, response.getStatus());
        verify(clientService, org.mockito.Mockito.never()).getByClientId(any());
        assertTrue(response.getContentAsString().contains("AUTHENTICATION_FAILED"));
    }

    private ExternalApiAuthenticationFilter filter(ApiClientService clientService,
                                                   CredentialVersionService credentialService,
                                                   ApiNonceReplayService nonceService,
                                                   IntegrationHubSecretCryptoService crypto) {
        IntegrationHubExternalApiProperties properties = new IntegrationHubExternalApiProperties();
        properties.getPublicApi().setEnabled(true);
        properties.getExternalTransport().setEnabled(false);
        properties.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);
        return new ExternalApiAuthenticationFilter(providerOf(properties), providerOf(new ExternalApiSourceIpResolver()),
                providerOf(clientService), providerOf(credentialService), providerOf(nonceService), providerOf(crypto),
                providerOf(Clock.fixed(NOW, ZoneOffset.UTC)), new ObjectMapper());
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private MockHttpServletRequest signedRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/external-api/v1/projects");
        request.setRemoteAddr("203.0.113.10");
        request.setContent(BODY);
        request.setAttribute(ExternalApiCanonicalRequest.RAW_TARGET_ATTRIBUTE,
                TARGET.getBytes(StandardCharsets.US_ASCII));
        request.addHeader("X-Client-ID", "client-a");
        request.addHeader("X-Credential-Version", "1");
        request.addHeader("X-Key-ID", "key-1");
        request.addHeader("X-Timestamp", TIMESTAMP);
        request.addHeader("X-Nonce", "AQIDBAUGBwgJCgsMDQ4PEA");
        request.addHeader("X-Client-Signature", signature());
        return request;
    }

    private String signature() throws Exception {
        String canonicalTarget = ExternalApiCanonicalRequest.canonicalizeTarget(TARGET);
        String digest = com.ses.service.integrationhub.IntegrationHubDigest.sha256Hex(BODY);
        byte[] bytes = ExternalApiCanonicalRequest.signedBytes("client-a", "1", "key-1", TIMESTAMP,
                "AQIDBAUGBwgJCgsMDQ4PEA", "GET", canonicalTarget, digest);
        var mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(bytes));
    }

    private ApiClient activeClient() {
        return ApiClient.builder().id(7L).clientId("client-a").tenantId("tenant-a")
                .dataScopeJson("{\"engineerIds\":[\"e-1\"]}").allowedCidrs("203.0.113.0/24")
                .clientTier("STANDARD").status("ACTIVE").build();
    }

    private CredentialVersion activeCredential() {
        return CredentialVersion.builder().apiClientId(7L).credentialVersion(1).keyId("key-1")
                .encryptedSecret("envelope").status("ACTIVE")
                .issuedAt(LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC))
                .expiresAt(LocalDateTime.ofInstant(NOW.plusSeconds(86_400), ZoneOffset.UTC)).build();
    }
}
