package com.ses.privacy.foundation;

import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * hold 解除 proof の検証（NF07-REL、sf-w2-protocol.md §11.1）。package-private で、{@link ProductionPrivacyComponents}
 * からだけ生成する（prod profile 必須）。Spring Bean には登録しない。状態を持たず、registry と失効状態は検証のたびに読む。
 *
 * <p>判定の順序（最初に当たった理由を返す）:
 * <ol>
 *   <li>提示された束縛と、信頼できる現在値から組み立てた束縛の項目ごとの一致（tenant・subject・hold ID・hold revision・
 *       journal head・command ID・理由 hash・要求者）。</li>
 *   <li>claim の有無と役割（issuer の位置に ISSUER、approver の位置に APPROVER）。</li>
 *   <li>trust anchor の登録（{@code authorityId:keyId}）と識別子の一致。</li>
 *   <li>trust anchor の範囲（tenant・役割・対象種別）。</li>
 *   <li>時刻（claim の発行日時〜期限と trust anchor の有効期間。現在時刻は注入された時計）。</li>
 *   <li>要求者・発行者・承認者の分離（主体は trust anchor の登録値。authority・鍵の共有も拒否）。</li>
 *   <li>Ed25519 署名（束縛の digest を含む {@link HoldReleaseAuthorityClaim#signedBytes}）。</li>
 *   <li>失効状態（両 trust anchor が {@code ACTIVE} であること。UNKNOWN・provider 無し・照会不能は拒否）。</li>
 * </ol>
 */
final class HoldReleaseProofVerifier implements HoldReleaseVerifier {

    private final HoldReleaseTrustRegistry registry;
    private final RevocationStatusProvider revocations;
    private final ProductionEnvironmentGuard environmentGuard;
    private final Clock clock;

    HoldReleaseProofVerifier(HoldReleaseTrustRegistry registry, RevocationStatusProvider revocations,
                             ProductionEnvironmentGuard environmentGuard, Clock clock) {
        this.registry = registry;
        this.revocations = revocations;
        this.environmentGuard = Objects.requireNonNull(environmentGuard, "environmentGuard");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public VerifiedHoldRelease verify(HoldReleaseBinding presented, HoldReleaseBinding expected, String targetType,
                                      HoldReleaseAuthorityClaim issuer, HoldReleaseAuthorityClaim approver) {
        environmentGuard.requireProductionProfile();
        if (presented == null || expected == null || targetType == null) {
            throw reject(HoldReleaseRejection.CLAIM_MISSING);
        }
        compareBindings(presented, expected);
        if (issuer == null || approver == null) {
            throw reject(HoldReleaseRejection.CLAIM_MISSING);
        }
        if (issuer.role() != HoldReleaseAuthorityClaim.Role.ISSUER
                || approver.role() != HoldReleaseAuthorityClaim.Role.APPROVER) {
            throw reject(HoldReleaseRejection.ROLE_MISMATCH);
        }
        Map<String, HoldReleaseTrustAnchor> anchors;
        try {
            anchors = registry == null ? null : registry.anchors();
        } catch (RuntimeException e) {
            anchors = null;
        }
        if (anchors == null || anchors.isEmpty()) {
            throw reject(HoldReleaseRejection.TRUST_ANCHOR_UNKNOWN);
        }
        HoldReleaseTrustAnchor issuerAnchor = anchorOf(anchors, issuer);
        HoldReleaseTrustAnchor approverAnchor = anchorOf(anchors, approver);

        requireScope(issuerAnchor, issuer, expected.tenantId(), targetType);
        requireScope(approverAnchor, approver, expected.tenantId(), targetType);

        Instant now = clock.instant();
        requireTimeWindow(issuerAnchor, issuer, now);
        requireTimeWindow(approverAnchor, approver, now);

        String requester = expected.requesterRef();
        if (requester.equals(issuerAnchor.principalRef()) || requester.equals(approverAnchor.principalRef())
                || issuerAnchor.principalRef().equals(approverAnchor.principalRef())
                || issuerAnchor.authorityId().equals(approverAnchor.authorityId())
                || issuerAnchor.publicKey().equals(approverAnchor.publicKey())) {
            throw reject(HoldReleaseRejection.SEPARATION_OF_DUTIES);
        }

        verifySignature(issuerAnchor, issuer, expected);
        verifySignature(approverAnchor, approver, expected);

        requireActive(issuerAnchor.anchorId());
        requireActive(approverAnchor.anchorId());

        Instant expiresAt = min(min(issuer.expiresAt(), approver.expiresAt()),
                min(issuerAnchor.validUntil(), approverAnchor.validUntil()));
        return new VerifiedHoldRelease(expected, targetType, issuerAnchor.anchorId(), approverAnchor.anchorId(),
                expiresAt, now);
    }

    private static void compareBindings(HoldReleaseBinding presented, HoldReleaseBinding expected) {
        if (!presented.tenantId().equals(expected.tenantId())) {
            throw reject(HoldReleaseRejection.TENANT_MISMATCH);
        }
        if (!presented.subjectRef().equals(expected.subjectRef())) {
            throw reject(HoldReleaseRejection.SUBJECT_MISMATCH);
        }
        if (!presented.holdId().equals(expected.holdId())) {
            throw reject(HoldReleaseRejection.HOLD_MISMATCH);
        }
        if (presented.expectedHoldRevision() != expected.expectedHoldRevision()) {
            throw reject(HoldReleaseRejection.HOLD_REVISION_MISMATCH);
        }
        if (presented.expectedJournalRevision() != expected.expectedJournalRevision()
                || !presented.expectedJournalHash().equals(expected.expectedJournalHash())) {
            throw reject(HoldReleaseRejection.JOURNAL_HEAD_MISMATCH);
        }
        if (!presented.commandId().equals(expected.commandId())) {
            throw reject(HoldReleaseRejection.COMMAND_MISMATCH);
        }
        if (!presented.reasonHash().equals(expected.reasonHash())) {
            throw reject(HoldReleaseRejection.REASON_HASH_MISMATCH);
        }
        if (!presented.requesterRef().equals(expected.requesterRef())) {
            throw reject(HoldReleaseRejection.REQUESTER_MISMATCH);
        }
    }

    private static HoldReleaseTrustAnchor anchorOf(Map<String, HoldReleaseTrustAnchor> anchors,
                                                   HoldReleaseAuthorityClaim claim) {
        HoldReleaseTrustAnchor anchor = anchors.get(claim.anchorId());
        if (anchor == null || !anchor.authorityId().equals(claim.authorityId()) || !anchor.keyId().equals(claim.keyId())) {
            throw reject(HoldReleaseRejection.TRUST_ANCHOR_UNKNOWN);
        }
        return anchor;
    }

    private static void requireScope(HoldReleaseTrustAnchor anchor, HoldReleaseAuthorityClaim claim, String tenantId,
                                     String targetType) {
        if (!anchor.tenants().contains(tenantId) || !anchor.roles().contains(claim.role())
                || !anchor.targetTypes().contains(targetType)) {
            throw reject(HoldReleaseRejection.SCOPE_MISMATCH);
        }
    }

    private static void requireTimeWindow(HoldReleaseTrustAnchor anchor, HoldReleaseAuthorityClaim claim, Instant now) {
        if (!claim.expiresAt().isAfter(claim.issuedAt())) {
            throw reject(HoldReleaseRejection.EXPIRED);
        }
        if (now.isBefore(claim.issuedAt()) || now.isBefore(anchor.validFrom())
                || claim.issuedAt().isBefore(anchor.validFrom())) {
            throw reject(HoldReleaseRejection.NOT_YET_VALID);
        }
        if (!now.isBefore(claim.expiresAt()) || !now.isBefore(anchor.validUntil())) {
            throw reject(HoldReleaseRejection.EXPIRED);
        }
    }

    private static void verifySignature(HoldReleaseTrustAnchor anchor, HoldReleaseAuthorityClaim claim,
                                        HoldReleaseBinding binding) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(anchor.publicKey());
            verifier.update(claim.signedBytes(binding));
            if (!verifier.verify(claim.signature())) {
                throw reject(HoldReleaseRejection.SIGNATURE_INVALID);
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw reject(HoldReleaseRejection.SIGNATURE_INVALID);
        }
    }

    private void requireActive(String anchorId) {
        if (revocations == null) {
            throw reject(HoldReleaseRejection.REVOCATION_UNKNOWN);
        }
        RevocationStatus status;
        try {
            status = revocations.statusOf(anchorId);
        } catch (RuntimeException e) {
            throw reject(HoldReleaseRejection.REVOCATION_UNKNOWN);
        }
        if (status == RevocationStatus.REVOKED) {
            throw reject(HoldReleaseRejection.REVOKED);
        }
        if (status != RevocationStatus.ACTIVE) {
            throw reject(HoldReleaseRejection.REVOCATION_UNKNOWN);
        }
    }

    private static Instant min(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private static HoldReleaseRejectedException reject(HoldReleaseRejection reason) {
        return new HoldReleaseRejectedException(reason);
    }
}
