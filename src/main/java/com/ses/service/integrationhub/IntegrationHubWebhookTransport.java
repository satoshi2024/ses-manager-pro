package com.ses.service.integrationhub;

/**
 * NF-05 B1 outbound transport boundary。
 *
 * <p>このinterfaceの呼出し元はDB transactionを保持してはならない。実装は
 * MOCK/STUB/LOOPBACKに加え、承認済み公開宛先だけを許可するHTTPSを持つ。
 * 本番outboundの開放はHTTPSだけを対象に、DG-05の独立承認とegress制御を必要とする。
 */
public interface IntegrationHubWebhookTransport {
    IntegrationHubWebhookTransportResult send(IntegrationHubWebhookRequest request);
}
