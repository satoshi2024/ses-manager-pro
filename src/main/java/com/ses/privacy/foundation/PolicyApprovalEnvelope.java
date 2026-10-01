package com.ses.privacy.foundation;

import java.util.Objects;

/** 承認booleanを持たず、単独署名される不変のpolicy承認。 */
public final class PolicyApprovalEnvelope {
    private final ApprovalEnvelopeMetadata metadata;
    private final String policyId;
    private final String policyVersion;
    private final String dataElementId;
    private final String action;
    private final String providerId;
    private final String purpose;
    private final String trigger;
    private final String retentionRule;
    private final String disposition;
    private final byte[] signature;

    public PolicyApprovalEnvelope(ApprovalEnvelopeMetadata metadata, String policyId, String policyVersion,
                                  String dataElementId, String action, String providerId, String purpose,
                                  String trigger, String retentionRule, String disposition, byte[] signature) {
        this.metadata = Objects.requireNonNull(metadata, "metadataが必要です");
        this.policyId = required(policyId, "policyId");
        this.policyVersion = required(policyVersion, "policyVersion");
        this.dataElementId = required(dataElementId, "dataElementId");
        this.action = required(action, "action");
        this.providerId = required(providerId, "providerId");
        this.purpose = required(purpose, "purpose");
        this.trigger = required(trigger, "trigger");
        this.retentionRule = required(retentionRule, "retentionRule");
        this.disposition = required(disposition, "disposition");
        this.signature = Objects.requireNonNull(signature, "署名が必要です").clone();
    }

    public ApprovalEnvelopeMetadata metadata() { return metadata; }
    public String policyId() { return policyId; }
    public String policyVersion() { return policyVersion; }
    public String dataElementId() { return dataElementId; }
    public String action() { return action; }
    public String providerId() { return providerId; }
    public String purpose() { return purpose; }
    public String trigger() { return trigger; }
    public String retentionRule() { return retentionRule; }
    public String disposition() { return disposition; }
    public byte[] signature() { return signature.clone(); }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "は必須です");
        return value;
    }
}
