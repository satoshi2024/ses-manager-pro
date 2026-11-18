package com.ses.service.integrationhub;

/** 未送信が確定した結果だけclaim時の仮計数を同じ結果CASで返却する。不明な通信障害は消費する。 */
public final class IntegrationHubWebhookAttemptAccounting {
    private IntegrationHubWebhookAttemptAccounting() { }
    public static final String UNSENT_SQL = "attempt_count = attempt_count - CASE WHEN #{errorCode} IN "
            + "('TRANSPORT_BUSY','TIMEOUT_BEFORE_SEND','NETWORK_UNAVAILABLE','TRANSPORT_CLOSED',"
            + "'LEASE_MARGIN_INSUFFICIENT','SUBSCRIPTION_INVALID','PAYLOAD_INVALID','PRIMARY_BINDING_INVALID',"
            + "'SIGNING_INVALID','CURRENT_SCOPE_REJECTED','KILL_SWITCH','DESTINATION_REJECTED',"
            + "'DNS_UNAVAILABLE','UNSENT_AGE_EXCEEDED','REQUEST_INVALID','REQUEST_HEADER_REJECTED') AND attempt_count > 0 THEN 1 ELSE 0 END, ";
    public static boolean definitelyUnsent(String code) {
        if (code == null) return false;
        return switch (code) {
            case "TRANSPORT_BUSY", "TIMEOUT_BEFORE_SEND", "NETWORK_UNAVAILABLE", "TRANSPORT_CLOSED",
                 "LEASE_MARGIN_INSUFFICIENT", "SUBSCRIPTION_INVALID", "PAYLOAD_INVALID", "PRIMARY_BINDING_INVALID",
                 "SIGNING_INVALID", "CURRENT_SCOPE_REJECTED", "KILL_SWITCH", "DESTINATION_REJECTED",
                 "DNS_UNAVAILABLE", "UNSENT_AGE_EXCEEDED", "REQUEST_INVALID", "REQUEST_HEADER_REJECTED" -> true;
            default -> false;
        };
    }
}
