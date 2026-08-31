package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** inbound event/replay用のclient-bound opaque reference。内部DB IDを外部へ返さない。 */
@Component
@RequiredArgsConstructor
public class InboundEventAdminReferenceCodec {
    private static final String EVENT_PURPOSE = "integration-hub-inbound-event-ref-v1";
    private static final String REPLAY_PURPOSE = "integration-hub-inbound-replay-ref-v1";
    private final IntegrationHubExternalApiProperties properties;

    public String eventReference(String clientId, String providerName, String providerEventId) {
        return encode(EVENT_PURPOSE, clientId, providerName, providerEventId);
    }

    public String replayReference(String eventReference, int generation) {
        return encode(REPLAY_PURPOSE, eventReference, Integer.toString(generation));
    }

    public boolean matchesEvent(String reference, String clientId, String providerName, String providerEventId) {
        return reference != null && reference.equals(eventReference(clientId, providerName, providerEventId));
    }

    private String encode(String purpose, String... values) {
        if (purpose == null || values.length == 0) {
            throw new IllegalArgumentException("opaque reference input is invalid");
        }
        for (String value : values) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException("opaque reference value is invalid");
            }
        }
        String message = purpose + "|" + String.join("|", values);
        try {
            String key = properties.getPublicApi() == null ? null : properties.getPublicApi().getPublicIdKey();
            if (key == null || key.getBytes(StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException("opaque reference key is not configured");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("opaque reference codec is unavailable", e);
        }
    }
}
