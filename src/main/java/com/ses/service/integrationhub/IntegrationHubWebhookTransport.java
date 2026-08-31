package com.ses.service.integrationhub;

/**
 * NF-05 B1 outbound transport boundary。
 *
 * <p>このinterfaceの呼出し元はDB transactionを保持してはならない。実装は
 * MOCK/STUB/LOOPBACKのいずれかに限定し、production providerを表す実装を持たない。
 */
public interface IntegrationHubWebhookTransport {
    IntegrationHubWebhookTransportResult send(IntegrationHubWebhookRequest request);
}
