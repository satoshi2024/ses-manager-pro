package com.ses.service.integrationhub;

/** delivery enqueue/replay時のscope binding digest。raw scope JSONは保持しない。 */
public final class IntegrationHubWebhookScopeDigest {
    private IntegrationHubWebhookScopeDigest() {
    }

    public static String of(String clientId, String scopeCode, String tenantId) {
        if (clientId == null || scopeCode == null || tenantId == null
                || clientId.isBlank() || scopeCode.isBlank() || tenantId.isBlank()) {
            throw new IllegalArgumentException("delivery scope binding is missing");
        }
        return IntegrationHubDigest.sha256Hex(clientId + "|" + scopeCode + "|" + tenantId);
    }
}
