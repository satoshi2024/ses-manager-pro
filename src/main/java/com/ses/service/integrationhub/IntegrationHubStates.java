package com.ses.service.integrationhub;

/** NF-05 F1で共有するcanonical state/retention値。別名状態を作らない。 */
public final class IntegrationHubStates {
    private IntegrationHubStates() {
    }

    public static final String IDEMPOTENCY_IN_PROGRESS = "IN_PROGRESS";
    public static final String IDEMPOTENCY_SUCCEEDED = "SUCCEEDED";
    public static final String IDEMPOTENCY_FAILED = "FAILED";
    public static final String IDEMPOTENCY_CONFLICT = "CONFLICT";

    public static final String DELIVERY_PENDING = "PENDING";
    public static final String DELIVERY_CLAIMED = "CLAIMED";
    public static final String DELIVERY_RETRYABLE = "RETRYABLE";
    public static final String DELIVERY_SUCCEEDED = "SUCCEEDED";
    public static final String DELIVERY_FAILED = "FAILED";
    public static final String DELIVERY_DLQ = "DLQ";

    public static final String INBOUND_RECEIVED = "RECEIVED";
    public static final String INBOUND_PROCESSING = "PROCESSING";
    public static final String INBOUND_PROCESSED = "PROCESSED";
    public static final String INBOUND_DUPLICATE = "DUPLICATE";
    public static final String INBOUND_CONFLICT = "CONFLICT";
    public static final String INBOUND_DLQ = "DLQ";

    public static final String RETENTION_SUCCEEDED_30D = "SUCCEEDED_PAYLOAD_30D";
    public static final String RETENTION_FAILED_90D = "FAILED_DLQ_PAYLOAD_90D";
    public static final String RETENTION_AUDIT_1Y = "AUDIT_METADATA_1Y";
}
