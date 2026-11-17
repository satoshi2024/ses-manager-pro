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
 *       journal head・command ID・理由 hash・要求者・開始者）。</li>
 *   <li>claim の有無と役割（issuer の位置に ISSUER、approver の位置に APPROVER）。</li>
 *   <li>trust anchor の登録（{@code authorityId:keyId}）と識別子の一致。</li>
 *   <li>trust anchor の範囲（tenant・役割・対象種別）。</li>
 *   <li>時刻（claim の発行日時〜期限と trust anchor の有効期間。現在時刻は注入された時計）。発行日時の側だけ
 *       {@link HoldReleaseRules#notBeforeTolerance()} の未来を許し、期限の側は広げない。</li>
 *   <li>Ed25519 署名（束縛の digest を含む {@link HoldReleaseAuthorityClaim#signedBytes}）。主体に関わる判定は署名を
 *       確かめた後に行う（署名の無い要求で「ある authority の主体が自分か」を探れないようにする）。</li>
 *   <li>要求者・発行者・承認者の分離（主体は trust anchor の登録値。authority・鍵の共有も拒否）。</li>
 *   <li>hold の開始者（D-05 の規則 {@link HoldReleaseRules}）: 開始者が不明なら既定で {@code HOLD_STARTER_UNKNOWN}。分かって
 *       いれば既定で発行者・承認者は開始者と別の主体（{@code SEPARATION_OF_DUTIES}）。要求者は開始者と同じでもよい。</li>
 *   <li>失効状態（両 trust anchor が {@code ACTIVE} であること。UNKNOWN・provider 無し・照会不能は拒否）。</li>
 * </ol>
 * 主体の比較は正規形（{@link HoldReleaseTrustAnchor#PRINCIPAL}）の文字列の完全一致だけで行う（anchor・束縛の constructor が
 * 正規形以外を受け付けない）。発行する proof の command は両 claim の digest（{@link HoldReleaseAuthorityClaim#claimsDigest}）を
 * {@code action_id} に持つ（誰が署名したかを Journal に残す。REL review P3-6）。
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
                                      HoldReleaseRules rules, HoldReleaseAuthorityClaim issuer,
                                      HoldReleaseAuthorityClaim approver) {
        environmentGuard.requireProductionProfile();
        if (presented == null || expected == null || targetType == null) {
            throw reject(HoldReleaseRejection.CLAIM_MISSING);
        }
        HoldReleaseRules effective = rules == null ? HoldReleaseRules.strict() : rules;
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
        requireTimeWindow(issuerAnchor, issuer, now, effective.notBeforeTolerance());
        requireTimeWindow(approverAnchor, approver, now, effective.notBeforeTolerance());

        verifySignature(issuerAnchor, issuer, expected);
        verifySignature(approverAnchor, approver, expected);

        String requester = expected.requesterRef();
        String issuerPrincipal = issuerAnchor.principalRef();
        String approverPrincipal = approverAnchor.principalRef();
        if (requester.equals(issuerPrincipal) || requester.equals(approverPrincipal)
                || issuerPrincipal.equals(approverPrincipal)
                || issuerAnchor.authorityId().equals(approverAnchor.authorityId())
                || issuerAnchor.publicKey().equals(approverAnchor.publicKey())) {
            throw reject(HoldReleaseRejection.SEPARATION_OF_DUTIES);
        }
        if (!expected.starterKnown()) {
            if (effective.rejectUnknownStarter()) {
                throw reject(HoldReleaseRejection.HOLD_STARTER_UNKNOWN);
            }
        } else if (effective.starterSeparationRequired()
                && (expected.holdStarterRef().equals(issuerPrincipal)
                || expected.holdStarterRef().equals(approverPrincipal))) {
            throw reject(HoldReleaseRejection.SEPARATION_OF_DUTIES);
        }

        requireActive(issuerAnchor.anchorId());
        requireActive(approverAnchor.anchorId());

        Instant expiresAt = min(min(issuer.expiresAt(), approver.expiresAt()),
                min(issuerAnchor.validUntil(), approverAnchor.validUntil()));
        return new VerifiedHoldRelease(expected, targetType, issuerAnchor.anchorId(), approverAnchor.anchorId(),
                HoldReleaseAuthorityClaim.claimsDigest(issuer, approver), expiresAt, now);
    }

    @Override
    public boolean trustReady() {
        try {
            Map<String, HoldReleaseTrustAnchor> anchors = registry == null ? null : registry.anchors();
            // 解除には authority・主体・鍵を共有しない発行者と承認者の組が要るため、その組が ACTIVE と確認できるときだけ開く
            // （anchor の件数ではなく組で数える。同じ authority の2つの鍵では開かない。RB-NF07-REG review P3-1）
            return PrivacyTrustReadiness.releasePairAvailable(anchors, revocations);
        } catch (RuntimeException e) {
            return false;
        }
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
        if (!presented.holdStarterRef().equals(expected.holdStarterRef())) {
            throw reject(HoldReleaseRejection.HOLD_STARTER_MISMATCH);
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

    /**
     * claim の発行日時〜期限と trust anchor の有効期間。発行日時が検証時刻より未来のものは {@code tolerance} の幅までだけ
     * 受け付ける（署名者の時計の進み）。期限・anchor の有効期間の終わりは広げない。
     */
    private static void requireTimeWindow(HoldReleaseTrustAnchor anchor, HoldReleaseAuthorityClaim claim, Instant now,
                                          java.time.Duration tolerance) {
        if (!claim.expiresAt().isAfter(claim.issuedAt())) {
            throw reject(HoldReleaseRejection.EXPIRED);
        }
        if (now.plus(tolerance).isBefore(claim.issuedAt()) || now.isBefore(anchor.validFrom())
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
