package com.ses.privacy.foundation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;

/**
 * hold 解除の発行者・承認者の署名付き authority claim（NF07-REL、sf-w2-protocol.md §11.1）。
 *
 * <p>署名の対象は {@link #signedBytes(HoldReleaseBinding)}（domain・audience・action・role・authority・key・発行日時・期限と、
 * 束縛の digest）で、鍵は {@link HoldReleaseTrustAnchor} が登録した Ed25519 公開鍵で検証する。claim は
 * 「誰が」を自分で名乗らない: 主体（principal）は trust anchor の登録値だけを使い、actor 名・role 文字列・caller の
 * 真偽値から proof を作らない。署名は system の外（D-03 で決める方式）で行い、本 class は鍵を持たない。
 */
public record HoldReleaseAuthorityClaim(Role role, String authorityId, String keyId, Instant issuedAt,
                                        Instant expiresAt, byte[] signature) {

    static final String DOMAIN = "NF07-HOLD-RELEASE-AUTHORITY-V1";

    /** claim の役割。 */
    public enum Role {
        /** 解除の発行者。 */
        ISSUER,
        /** 解除の承認者。 */
        APPROVER
    }

    public HoldReleaseAuthorityClaim {
        if (role == null || authorityId == null || authorityId.isBlank() || keyId == null || keyId.isBlank()
                || issuedAt == null || expiresAt == null || signature == null || signature.length == 0) {
            throw new IllegalArgumentException("解除の authority claim の項目が不足しています");
        }
        signature = signature.clone();
    }

    @Override
    public byte[] signature() {
        return signature.clone();
    }

    /** 署名の対象（この claim の値と束縛）。 */
    public byte[] signedBytes(HoldReleaseBinding binding) {
        return signedBytes(binding, role, authorityId, keyId, issuedAt, expiresAt);
    }

    /**
     * 署名の対象を組み立てる（外部の署名手順と test が同じ正規形を使う）。
     *
     * @throws IllegalArgumentException 引数のいずれかが欠ける
     */
    public static byte[] signedBytes(HoldReleaseBinding binding, Role role, String authorityId, String keyId,
                                     Instant issuedAt, Instant expiresAt) {
        if (binding == null || role == null || authorityId == null || keyId == null || issuedAt == null
                || expiresAt == null) {
            throw new IllegalArgumentException("署名の対象が不足しています");
        }
        StringBuilder canonical = new StringBuilder();
        HoldReleaseBinding.field(canonical, DOMAIN);
        HoldReleaseBinding.field(canonical, ProductionEnvironmentGuard.PRODUCTION_AUDIENCE);
        HoldReleaseBinding.field(canonical, HoldReleaseBinding.ACTION);
        HoldReleaseBinding.field(canonical, role.name());
        HoldReleaseBinding.field(canonical, authorityId);
        HoldReleaseBinding.field(canonical, keyId);
        HoldReleaseBinding.field(canonical, issuedAt.toString());
        HoldReleaseBinding.field(canonical, expiresAt.toString());
        HoldReleaseBinding.field(canonical, binding.digest());
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** trust anchor の key（{@code authorityId + ":" + keyId}）。 */
    public String anchorId() {
        return authorityId + ":" + keyId;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HoldReleaseAuthorityClaim that && role == that.role
                && authorityId.equals(that.authorityId) && keyId.equals(that.keyId) && issuedAt.equals(that.issuedAt)
                && expiresAt.equals(that.expiresAt) && Arrays.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, authorityId, keyId, issuedAt, expiresAt) * 31 + Arrays.hashCode(signature);
    }

    @Override
    public String toString() {
        return "HoldReleaseAuthorityClaim{role=" + role + ", authorityId=" + authorityId + ", keyId=" + keyId + "}";
    }
}
