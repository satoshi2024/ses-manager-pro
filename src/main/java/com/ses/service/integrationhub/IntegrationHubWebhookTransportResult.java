package com.ses.service.integrationhub;

/** 外部応答のsafe metadata。response bodyは保持・返却しない。 */
public record IntegrationHubWebhookTransportResult(
        int httpStatus,
        String providerRequestId,
        String errorCode,
        boolean retryable) {

    public boolean success() {
        return httpStatus >= 200 && httpStatus < 300 && errorCode == null;
    }

    public static IntegrationHubWebhookTransportResult success(int status, String providerRequestId) {
        if (status < 200 || status >= 300) {
            throw new IllegalArgumentException("success status is not 2xx");
        }
        return new IntegrationHubWebhookTransportResult(status, safeProviderRequestId(providerRequestId), null, false);
    }

    public static IntegrationHubWebhookTransportResult failure(int status, String errorCode, boolean retryable) {
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("invalid webhook error code");
        }
        return new IntegrationHubWebhookTransportResult(status, null, errorCode, retryable);
    }

    private static String safeProviderRequestId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > 128 || !value.matches("[A-Za-z0-9._:-]{1,128}")) {
            return null;
        }
        return value;
    }
}
