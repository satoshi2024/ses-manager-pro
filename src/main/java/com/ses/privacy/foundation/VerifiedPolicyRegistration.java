package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.Objects;

/**
 * 検証済みの policy envelope（NF07-REG の verify-then-store）。
 *
 * <p>constructor は package-private で、{@link PolicyRegistrationVerifier}（{@link ProductionPrivacyComponents#policyRegistrationVerifier}
 * 経由・prod 専用）だけが生成する。値は trust anchor で署名を確かめた envelope の canonical bytes から復号したもので、
 * 呼出し元が指定した値・登録行の列からは作れない。登録する行の key（tenant・policy ID・revision・module（= envelope の
 * scope）・data element・action・provider）と、保存する canonical bytes・署名・digest・authority・key は全てここから取る。
 */
public final class VerifiedPolicyRegistration {

    private final String tenantId;
    private final String policyId;
    private final String revision;
    private final String policyVersion;
    private final String scope;
    private final String dataElementId;
    private final String action;
    private final String providerId;
    private final String authorityId;
    private final String keyId;
    private final int schemaVersion;
    private final String digest;
    private final byte[] canonical;
    private final byte[] signature;
    private final Instant issuedAt;
    private final Instant effectiveAt;
    private final Instant expiresAt;

    VerifiedPolicyRegistration(PolicyApprovalEnvelope envelope, byte[] canonical, VerifiedPolicyMetadata verified) {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(verified, "verified");
        this.tenantId = verified.tenantId();
        this.policyId = verified.policyId();
        this.revision = envelope.metadata().revision();
        this.policyVersion = verified.policyVersion();
        this.scope = verified.scope();
        this.dataElementId = verified.dataElementId();
        this.action = verified.action();
        this.providerId = verified.providerId();
        this.authorityId = envelope.metadata().authorityId();
        this.keyId = envelope.metadata().keyId();
        this.schemaVersion = Integer.parseInt(envelope.metadata().schemaVersion());
        this.digest = verified.policyDigest();
        this.canonical = Objects.requireNonNull(canonical, "canonical").clone();
        this.signature = envelope.signature();
        this.issuedAt = envelope.metadata().issuedAt();
        this.effectiveAt = verified.effectiveAt();
        this.expiresAt = verified.expiresAt();
    }

    /** envelope の発行日時（署名の範囲内。有効化で current より後に署名されたかの比較に使う）。 */
    public Instant issuedAt() {
        return issuedAt;
    }

    public String tenantId() {
        return tenantId;
    }

    public String policyId() {
        return policyId;
    }

    /** envelope の revision（{@code m_retention_policy.policy_revision}。数値比較しない）。 */
    public String revision() {
        return revision;
    }

    public String policyVersion() {
        return policyVersion;
    }

    /** envelope の scope（{@code m_retention_policy.module_code}。照会の catalog は module code を scope として照合する）。 */
    public String scope() {
        return scope;
    }

    public String dataElementId() {
        return dataElementId;
    }

    public String action() {
        return action;
    }

    public String providerId() {
        return providerId;
    }

    public String authorityId() {
        return authorityId;
    }

    public String keyId() {
        return keyId;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    /** canonical bytes の SHA-256（小文字 hex）。 */
    public String digest() {
        return digest;
    }

    /** 検証した canonical bytes（保存する値。複製を返す）。 */
    public byte[] canonical() {
        return canonical.clone();
    }

    /** 検証した署名（保存する値。複製を返す）。 */
    public byte[] signature() {
        return signature.clone();
    }

    public Instant effectiveAt() {
        return effectiveAt;
    }

    /** 有効期限（envelope の期限と trust anchor の有効期間の終わりの早い方）。 */
    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "VerifiedPolicyRegistration{policyId=" + policyId + ", revision=" + revision + "}";
    }
}
