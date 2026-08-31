package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.integrationhub.ApiClient;
import com.ses.entity.integrationhub.CredentialVersion;
import com.ses.service.integrationhub.ApiClientService;
import com.ses.service.integrationhub.ApiNonceReplayService;
import com.ses.service.integrationhub.CredentialVersionService;
import com.ses.service.integrationhub.crypto.IntegrationHubSecretCryptoService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Enumeration;

/** HMAC認証、source IP、client CIDR、nonce commitを固定順序で行う。 */
@Component
@RequiredArgsConstructor
public class ExternalApiAuthenticationFilter extends OncePerRequestFilter {
    public static final String SIGNED_TIMESTAMP_ATTRIBUTE = "external.signed-timestamp";
    private static final int MAX_HEADER_BYTES = 16_384;
    private static final int MAX_HEADER_FIELDS = 32;
    private static final int MAX_HEADER_VALUE_BYTES = 256;
    private static final int MAX_BODY_BYTES = 1_048_576;
    private static final long CLOCK_SKEW_SECONDS = 300;

    private final ObjectProvider<IntegrationHubExternalApiProperties> propertiesProvider;
    private final ObjectProvider<ExternalApiSourceIpResolver> sourceIpResolverProvider;
    private final ObjectProvider<ApiClientService> apiClientServiceProvider;
    private final ObjectProvider<CredentialVersionService> credentialVersionServiceProvider;
    private final ObjectProvider<ApiNonceReplayService> nonceReplayServiceProvider;
    private final ObjectProvider<IntegrationHubSecretCryptoService> cryptoServiceProvider;
    private final ObjectProvider<Clock> clockProvider;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!ExternalApiRouteCatalog.isExternalApiPath(request.getRequestURI())) {
            filterChain.doFilter(request, response);
            return;
        }
        IntegrationHubExternalApiProperties properties = configuredProperties();
        if (!properties.getPublicApi().getEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            rejectSessionAndBrowserBoundaries(request);
            byte[] body = readAndValidateBody(request);
            validateHeaderEnvelope(request);
            ExternalApiSourceIpResolver sourceIpResolver = required(sourceIpResolverProvider);
            String sourceIp = sourceIpResolver.resolve(request, properties.getSecurity().getTrustedProxies());
            ExternalApiCanonicalRequest.Parsed parsed = ExternalApiCanonicalRequest.parse(request, body);
            ExternalApiRouteCatalog.Route preAuthRoute = ExternalApiRouteCatalog.resolve(request.getMethod(),
                    parsed.canonicalPath());
            request.setAttribute(ExternalApiErrorWriter.ROUTE_ATTRIBUTE,
                    preAuthRoute == null ? "EXTERNAL_UNKNOWN_ROUTE" : preAuthRoute.template());
            ExternalApiAuditTrail.route(request,
                    preAuthRoute == null ? "EXTERNAL_UNKNOWN_ROUTE" : preAuthRoute.template());

            String clientId = requiredHeader(request, "X-Client-ID");
            String versionText = requiredHeader(request, "X-Credential-Version");
            String keyId = requiredHeader(request, "X-Key-ID");
            String timestampText = requiredHeader(request, "X-Timestamp");
            String nonceText = requiredHeader(request, "X-Nonce");
            String signatureText = requiredHeader(request, "X-Client-Signature");
            validateClientId(clientId);
            int credentialVersion = parseCredentialVersion(versionText);
            validateKeyId(keyId);
            long timestamp = parseTimestamp(timestampText);
            byte[] rawNonce = decodeBase64Url(nonceText, 22, 43, 16, 32, "NONCE_INVALID");
            byte[] suppliedSignature = decodeBase64Url(signatureText, 43, 43, 32, 32, "SIGNATURE_INVALID");

            LocalDateTime now = nowUtc();
            ApiClientService apiClientService = required(apiClientServiceProvider);
            CredentialVersionService credentialVersionService = required(credentialVersionServiceProvider);
            ApiNonceReplayService nonceReplayService = required(nonceReplayServiceProvider);
            IntegrationHubSecretCryptoService cryptoService = required(cryptoServiceProvider);
            ApiClient client = apiClientService.getByClientId(clientId);
            if (!usableClient(client, now) || !clientId.equals(client.getClientId())) {
                throw ExternalApiSecurityException.authentication("CLIENT_INVALID");
            }
            CredentialVersion credential = credentialVersionService
                    .getByClientAndVersion(client.getId(), credentialVersion);
            if (!usableCredential(credential, client.getId(), keyId, now)) {
                throw ExternalApiSecurityException.authentication("CREDENTIAL_INVALID");
            }

            String plaintextSecret = null;
            byte[] secretBytes = null;
            try {
                plaintextSecret = cryptoService.decrypt(clientId, credentialVersion, "credential",
                        credential.getEncryptedSecret());
                secretBytes = plaintextSecret.getBytes(StandardCharsets.UTF_8);
                byte[] expected = calculateSignature(secretBytes, clientId, versionText, keyId,
                        timestampText, nonceText, request.getMethod(), parsed);
                if (!MessageDigest.isEqual(expected, suppliedSignature)) {
                    throw ExternalApiSecurityException.authentication("SIGNATURE_INVALID");
                }
            } catch (ExternalApiSecurityException e) {
                throw e;
            } catch (Exception e) {
                throw ExternalApiSecurityException.authentication("CREDENTIAL_INVALID");
            } finally {
                if (secretBytes != null) {
                    java.util.Arrays.fill(secretBytes, (byte) 0);
                }
                if (plaintextSecret != null) {
                    char[] chars = plaintextSecret.toCharArray();
                    java.util.Arrays.fill(chars, '\0');
                }
            }

            if (!ExternalApiCidrMatcher.matchesAny(sourceIp, client.getAllowedCidrs())) {
                throw ExternalApiSecurityException.authentication("CLIENT_IP_NOT_ALLOWED");
            }
            LocalDateTime signedAt = LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneOffset.UTC);
            if (!nonceReplayService.accept(clientId, credentialVersion, rawNonce, signedAt, now)) {
                throw ExternalApiSecurityException.authentication("NONCE_REPLAY");
            }
            request.setAttribute(SIGNED_TIMESTAMP_ATTRIBUTE, signedAt);
            ExternalApiPrincipal principal = new ExternalApiPrincipal(client.getClientId(), client.getId(),
                    client.getTenantId(), client.getLegalEntityId(), client.getDataScopeJson(),
                    credentialVersion, keyId, client.getClientTier());
            ExternalApiAuditTrail.principal(request, principal);
            ExternalApiAuditTrail.mark(request, "authentication", "AUTHENTICATED");
            request.setAttribute(ExternalApiErrorWriter.PRINCIPAL_ATTRIBUTE, principal);
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "AUTHENTICATED");
            request.setAttribute(ExternalApiCanonicalRequest.class.getName(), parsed);
            SecurityContextHolder.getContext().setAuthentication(new ExternalApiAuthenticationToken(principal));
            filterChain.doFilter(new ExternalApiCachedBodyRequest(request, body), response);
        } catch (ExternalApiSecurityException e) {
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, e.getDecision());
            ExternalApiAuditTrail.mark(request, "authentication", e.getDecision());
            ExternalApiErrorWriter.writeException(response, objectMapper, correlationId(request), e);
        } catch (IOException e) {
            ExternalApiSecurityException failure = ExternalApiSecurityException.invalid("REQUEST_INVALID");
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, failure.getDecision());
            ExternalApiAuditTrail.mark(request, "authentication", failure.getDecision());
            ExternalApiErrorWriter.writeException(response, objectMapper, correlationId(request), failure);
        } catch (RuntimeException e) {
            ExternalApiSecurityException failure = new ExternalApiSecurityException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "INTERNAL_ERROR");
            request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, failure.getDecision());
            ExternalApiAuditTrail.mark(request, "authentication", failure.getDecision());
            ExternalApiErrorWriter.writeException(response, objectMapper, correlationId(request), failure);
        }
    }

    private void rejectSessionAndBrowserBoundaries(HttpServletRequest request) {
        if (request.getRequestedSessionId() != null || request.getSession(false) != null) {
            throw ExternalApiSecurityException.authentication("SESSION_NOT_ALLOWED");
        }
        if ("OPTIONS".equals(request.getMethod()) || hasHeader(request, "Origin")
                || hasHeader(request, "Access-Control-Request-Method")
                || hasHeader(request, "Access-Control-Request-Headers")) {
            throw ExternalApiSecurityException.authentication("BROWSER_REQUEST_NOT_ALLOWED");
        }
    }

    private byte[] readAndValidateBody(HttpServletRequest request) throws IOException {
        String contentLength = optionalSingleHeader(request, "Content-Length");
        if (contentLength != null && !contentLength.matches("0|[1-9][0-9]{0,6}")) {
            throw ExternalApiSecurityException.invalid("CONTENT_LENGTH_INVALID");
        }
        if (contentLength != null && Long.parseLong(contentLength) > MAX_BODY_BYTES) {
            throw ExternalApiSecurityException.invalid("BODY_TOO_LARGE");
        }
        String contentEncoding = optionalSingleHeader(request, "Content-Encoding");
        if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding)) {
            throw ExternalApiSecurityException.invalid("CONTENT_ENCODING_UNSUPPORTED");
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES
                || (contentLength != null && Long.parseLong(contentLength) != body.length)) {
            throw ExternalApiSecurityException.invalid("BODY_LENGTH_INVALID");
        }
        return body;
    }

    private void validateHeaderEnvelope(HttpServletRequest request) {
        Enumeration<String> names = request.getHeaderNames();
        int fields = 0;
        int bytes = 0;
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                Enumeration<String> values = request.getHeaders(name);
                if (values == null) {
                    continue;
                }
                while (values.hasMoreElements()) {
                    String value = values.nextElement();
                    fields++;
                    if (fields > MAX_HEADER_FIELDS || name == null || value == null) {
                        throw ExternalApiSecurityException.invalid("HEADER_BLOCK_INVALID");
                    }
                    bytes += name.getBytes(StandardCharsets.UTF_8).length
                            + value.getBytes(StandardCharsets.UTF_8).length;
                    if (bytes > MAX_HEADER_BYTES || value.getBytes(StandardCharsets.UTF_8).length > MAX_HEADER_VALUE_BYTES) {
                        throw ExternalApiSecurityException.invalid("HEADER_BLOCK_TOO_LARGE");
                    }
                }
            }
        }
    }

    private byte[] calculateSignature(byte[] secret, String clientId, String version, String keyId,
                                      String timestamp, String nonce, String method,
                                      ExternalApiCanonicalRequest.Parsed parsed) throws Exception {
        if (method == null || !method.matches("[A-Za-z]{1,16}")) {
            throw ExternalApiSecurityException.invalid("METHOD_INVALID");
        }
        byte[] canonical = ExternalApiCanonicalRequest.signedBytes(clientId, version, keyId, timestamp, nonce,
                method.toUpperCase(java.util.Locale.ROOT), parsed.canonicalTarget(), parsed.bodySha256());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        return mac.doFinal(canonical);
    }

    private boolean usableClient(ApiClient client, LocalDateTime now) {
        return client != null && "ACTIVE".equals(client.getStatus())
                && client.getClientId() != null
                && client.getClientId().matches("[A-Za-z0-9._~-]{1,64}")
                && client.getTenantId() != null && !client.getTenantId().isBlank()
                && client.getLegalEntityId() != null
                && client.getDataScopeJson() != null && !client.getDataScopeJson().isBlank()
                && client.getRevokedAt() == null && (client.getExpiresAt() == null || client.getExpiresAt().isAfter(now));
    }

    private boolean usableCredential(CredentialVersion credential, Long clientId, String keyId, LocalDateTime now) {
        if (credential == null || !clientId.equals(credential.getApiClientId()) || !keyId.equals(credential.getKeyId())
                || !("ACTIVE".equals(credential.getStatus()) || "OVERLAP".equals(credential.getStatus()))
                || credential.getIssuedAt() == null || credential.getExpiresAt() == null
                || credential.getIssuedAt().isAfter(now) || !credential.getExpiresAt().isAfter(now)
                || credential.getRevokedAt() != null) {
            return false;
        }
        return !"OVERLAP".equals(credential.getStatus())
                || credential.getOverlapUntil() != null && credential.getOverlapUntil().isAfter(now);
    }

    private String requiredHeader(HttpServletRequest request, String name) {
        String value = optionalSingleHeader(request, name);
        if (value == null || value.isBlank()) {
            throw ExternalApiSecurityException.authentication("AUTH_HEADER_MISSING");
        }
        return value;
    }

    private String optionalSingleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null || !values.hasMoreElements()) {
            return null;
        }
        String value = values.nextElement();
        if (values.hasMoreElements()) {
            throw ExternalApiSecurityException.authentication("AUTH_HEADER_DUPLICATE");
        }
        return value;
    }

    private boolean hasHeader(HttpServletRequest request, String name) {
        return optionalSingleHeader(request, name) != null;
    }

    private void validateClientId(String value) {
        if (value.length() > 64 || !value.matches("[A-Za-z0-9._~-]{1,64}")) {
            throw ExternalApiSecurityException.authentication("CLIENT_ID_INVALID");
        }
    }

    private void validateKeyId(String value) {
        if (value.length() > 100 || !value.matches("[A-Za-z0-9._~-]{1,100}")) {
            throw ExternalApiSecurityException.authentication("KEY_ID_INVALID");
        }
    }

    private int parseCredentialVersion(String value) {
        if (!value.matches("[1-9][0-9]{0,9}")) {
            throw ExternalApiSecurityException.authentication("CREDENTIAL_VERSION_INVALID");
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw ExternalApiSecurityException.authentication("CREDENTIAL_VERSION_INVALID");
        }
    }

    private long parseTimestamp(String value) {
        if (!value.matches("[0-9]{10}")) {
            throw ExternalApiSecurityException.authentication("TIMESTAMP_INVALID");
        }
        try {
            long timestamp = Long.parseLong(value);
            long now = clock().instant().getEpochSecond();
            if (Math.abs(now - timestamp) > CLOCK_SKEW_SECONDS) {
                throw ExternalApiSecurityException.authentication("TIMESTAMP_INVALID");
            }
            return timestamp;
        } catch (NumberFormatException e) {
            throw ExternalApiSecurityException.authentication("TIMESTAMP_INVALID");
        }
    }

    private byte[] decodeBase64Url(String value, int minChars, int maxChars,
                                   int minBytes, int maxBytes, String decision) {
        if (value == null || value.length() < minChars || value.length() > maxChars
                || !value.matches("[A-Za-z0-9_-]+")) {
            throw ExternalApiSecurityException.authentication(decision);
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(value);
            if (decoded.length < minBytes || decoded.length > maxBytes) {
                throw ExternalApiSecurityException.authentication(decision);
            }
            return decoded;
        } catch (IllegalArgumentException e) {
            throw ExternalApiSecurityException.authentication(decision);
        }
    }

    private LocalDateTime nowUtc() {
        return LocalDateTime.ofInstant(clock().instant(), ZoneOffset.UTC);
    }

    private IntegrationHubExternalApiProperties configuredProperties() {
        IntegrationHubExternalApiProperties configured = propertiesProvider.getIfAvailable();
        if (configured != null) {
            return configured;
        }
        IntegrationHubExternalApiProperties safeDefault = new IntegrationHubExternalApiProperties();
        safeDefault.setPublicApi(new IntegrationHubExternalApiProperties.PublicApi());
        safeDefault.getPublicApi().setEnabled(false);
        safeDefault.setExternalTransport(new IntegrationHubExternalApiProperties.ExternalTransport());
        safeDefault.getExternalTransport().setEnabled(false);
        safeDefault.setProvider(new IntegrationHubExternalApiProperties.Provider());
        safeDefault.getProvider().setMode(IntegrationHubExternalApiProperties.ProviderMode.MOCK);
        return safeDefault;
    }

    private Clock clock() {
        Clock configured = clockProvider.getIfAvailable();
        return configured != null ? configured : Clock.systemUTC();
    }

    private <T> T required(ObjectProvider<T> provider) {
        T service = provider.getIfAvailable();
        if (service == null) {
            throw new ExternalApiSecurityException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "INTERNAL_ERROR", "INTERNAL_ERROR");
        }
        return service;
    }

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE);
        return value instanceof String id ? id : "unavailable";
    }
}
