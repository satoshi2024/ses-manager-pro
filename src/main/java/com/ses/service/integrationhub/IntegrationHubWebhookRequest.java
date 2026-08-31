package com.ses.service.integrationhub;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** 送信直前に組み立てる不変Webhook request。secretは保持しない。 */
public record IntegrationHubWebhookRequest(
        URI endpoint,
        byte[] body,
        Map<String, String> headers) {

    public static final int MAX_BODY_BYTES = 1_048_576;
    private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");

    public IntegrationHubWebhookRequest {
        if (endpoint == null || body == null || body.length == 0 || body.length > MAX_BODY_BYTES
                || headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException("invalid webhook request");
        }
        headers.forEach((name, value) -> {
            if (name == null || value == null || !HEADER_NAME.matcher(name).matches()
                    || value.length() > 1024 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("invalid webhook header");
            }
        });
        body = body.clone();
        headers = Map.copyOf(new LinkedHashMap<>(headers));
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
