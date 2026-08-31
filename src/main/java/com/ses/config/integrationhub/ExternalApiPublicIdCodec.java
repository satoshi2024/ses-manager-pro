package com.ses.config.integrationhub;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** client/tenant/resourceにbindしたopaque public ID。内部DB IDを外部へ返さない。 */
@Component
@RequiredArgsConstructor
public class ExternalApiPublicIdCodec {
    private static final String PURPOSE = "integration-hub-public-id-v1";
    private final IntegrationHubExternalApiProperties properties;

    public String encode(ExternalApiPrincipal principal, String resourceType, Long internalId) {
        if (principal == null || resourceType == null || resourceType.isBlank()
                || internalId == null || internalId < 1) {
            throw new IllegalArgumentException("public id input is invalid");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(keyBytes(), "HmacSHA256"));
            String message = String.join("|", PURPOSE, principal.clientId(), principal.tenantId(),
                    resourceType, Long.toString(internalId));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("public id codec is unavailable", e);
        }
    }

    public boolean matches(ExternalApiPrincipal principal, String resourceType, Long internalId,
                           String publicId) {
        if (publicId == null || !publicId.matches("[A-Za-z0-9_-]{43}")) {
            return false;
        }
        return MessageDigest.isEqual(encode(principal, resourceType, internalId)
                .getBytes(StandardCharsets.US_ASCII), publicId.getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] keyBytes() {
        String key = properties.getPublicApi() == null ? null : properties.getPublicApi().getPublicIdKey();
        if (key == null || key.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("integration.hub.public-api.public-id-key is not configured");
        }
        return key.getBytes(StandardCharsets.UTF_8);
    }
}
