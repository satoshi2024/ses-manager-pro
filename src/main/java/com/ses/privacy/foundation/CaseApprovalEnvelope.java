package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.Objects;

/** Policy承認とは独立して署名される不変のcase承認。 */
public final class CaseApprovalEnvelope {
    private final ApprovalEnvelopeMetadata metadata;
    private final String caseId;
    private final String subjectReference;
    private final String targetId;
    private final String targetVersion;
    private final String targetHash;
    private final String policyDigest;
    private final String action;
    private final String providerId;
    private final Instant identityVerifiedAt;
    private final byte[] signature;

    public CaseApprovalEnvelope(ApprovalEnvelopeMetadata metadata, String caseId, String subjectReference,
                                String targetId, String targetVersion, String targetHash, String policyDigest,
                                String action, String providerId, Instant identityVerifiedAt, byte[] signature) {
        this.metadata = Objects.requireNonNull(metadata, "metadataが必要です");
        this.caseId = required(caseId, "caseId");
        this.subjectReference = required(subjectReference, "subjectReference");
        this.targetId = required(targetId, "targetId");
        this.targetVersion = required(targetVersion, "targetVersion");
        this.targetHash = required(targetHash, "targetHash");
        this.policyDigest = required(policyDigest, "policyDigest");
        this.action = required(action, "action");
        this.providerId = required(providerId, "providerId");
        this.identityVerifiedAt = Objects.requireNonNull(identityVerifiedAt, "本人確認時刻が必要です");
        this.signature = Objects.requireNonNull(signature, "署名が必要です").clone();
    }

    public ApprovalEnvelopeMetadata metadata() { return metadata; }
    public String caseId() { return caseId; }
    public String subjectReference() { return subjectReference; }
    public String targetId() { return targetId; }
    public String targetVersion() { return targetVersion; }
    public String targetHash() { return targetHash; }
    public String policyDigest() { return policyDigest; }
    public String action() { return action; }
    public String providerId() { return providerId; }
    public Instant identityVerifiedAt() { return identityVerifiedAt; }
    public byte[] signature() { return signature.clone(); }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + "は必須です");
        return value;
    }
}
