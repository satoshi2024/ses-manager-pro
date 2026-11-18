package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * trust anchor と失効 provider の組が、要求を受け付けられる状態かの判定（開放の gate。NF07-REG・NF07-REL 共用）。
 *
 * <p>数えるのは、registry の key が anchor 自身の {@code authorityId:keyId} と一致し、鍵が Ed25519 で（{@link Ed25519PublicKeys}）、
 * 判定の時刻に有効期間内（{@code validFrom <= now < validUntil}。verifier の有効期間の検査と同じ半開区間）で、授権 tenant
 * （呼出し側の権限評価の設定 tenant）を範囲に含み、失効 provider が {@link RevocationStatus#ACTIVE} と答える anchor だけ。
 * UNKNOWN・REVOKED・null・例外・provider 無しは数えない（第1期の常に UNKNOWN の provider では常に開かない）。D-03 の方式の adapter が
 * {@code Configured*TrustRegistry} を使わずに registry を実装しても、key の食い違い・Ed25519 以外の鍵の anchor で入口が開かない
 * （RB-NF07-REG review P3-1・P3-2）。有効期間と tenant は anchor だけで決まる verifier の条件で、これを見ないと唯一の組が期限切れ・
 * tenant 外でも入口が開き、要求が毎回403（{@code EXPIRED}／{@code SCOPE_MISMATCH}）になる（RB-NF07-REG delta review P3-1）。
 * 時刻は verifier と同じ clock の値を呼出し側が渡す。targetType・要求者・開始者の分離は要求に依存するため verifier に残す。
 * 必要な分が見つかった時点で照会をやめ、同じ anchor を2回照会しない。状態を持たない。
 */
final class PrivacyTrustReadiness {

    private PrivacyTrustReadiness() {
    }

    /**
     * policy envelope の anchor のうち、上の条件を満たすものの数（{@code enough} に達したら打ち切る）。
     *
     * @param anchors     registry の anchor（key は {@code authorityId:keyId}）
     * @param revocations 失効 provider（null なら0件）
     * @param enough      必要数（1以上）
     * @param now         判定の時刻（verifier と同じ clock の値。null なら0件）
     * @param tenantId    授権 tenant（null・空なら0件）
     */
    static int activePolicyAnchors(Map<String, TrustedAuthority> anchors, RevocationStatusProvider revocations,
                                   int enough, Instant now, String tenantId) {
        if (anchors == null || anchors.isEmpty() || revocations == null || enough < 1 || now == null
                || tenantId == null || tenantId.isBlank()) {
            return 0;
        }
        int active = 0;
        for (Map.Entry<String, TrustedAuthority> entry : anchors.entrySet()) {
            TrustedAuthority anchor = entry.getValue();
            if (anchor == null || !(anchor.authorityId() + ":" + anchor.keyId()).equals(entry.getKey())
                    || !Ed25519PublicKeys.isEd25519(anchor.publicKey())
                    || !withinValidity(anchor.validFrom(), anchor.validUntil(), now)
                    || !anchor.tenants().contains(tenantId)) {
                continue;
            }
            if (isActive(revocations, entry.getKey()) && ++active >= enough) {
                return active;
            }
        }
        return active;
    }

    /**
     * hold 解除に要る発行者と承認者の組が取れるか（REL review P3-9、RB-NF07-REG review P3-1）。上の条件を満たす anchor のうち、
     * 役割に ISSUER を持つものと APPROVER を持つものが、authority・主体・鍵を共有せずに1組以上あること（verifier の分離の規則と
     * 同じ。同じ authority の2つの鍵（rotation の重複期間）や、ISSUER だけの2件では開かない）。
     *
     * @param anchors     registry の anchor（key は {@code authorityId:keyId}）
     * @param revocations 失効 provider（null なら false）
     * @param now         判定の時刻（verifier と同じ clock の値。null なら false）
     * @param tenantId    授権 tenant（null・空なら false）
     */
    static boolean releasePairAvailable(Map<String, HoldReleaseTrustAnchor> anchors,
                                        RevocationStatusProvider revocations, Instant now, String tenantId) {
        if (anchors == null || anchors.size() < 2 || revocations == null || now == null || tenantId == null
                || tenantId.isBlank()) {
            return false;
        }
        List<HoldReleaseTrustAnchor> issuers = new ArrayList<>();
        List<HoldReleaseTrustAnchor> approvers = new ArrayList<>();
        for (Map.Entry<String, HoldReleaseTrustAnchor> entry : anchors.entrySet()) {
            HoldReleaseTrustAnchor anchor = entry.getValue();
            if (anchor == null || !anchor.anchorId().equals(entry.getKey())
                    || !Ed25519PublicKeys.isEd25519(anchor.publicKey())
                    || !withinValidity(anchor.validFrom(), anchor.validUntil(), now)
                    || !anchor.tenants().contains(tenantId)) {
                continue;
            }
            if (anchor.roles().contains(HoldReleaseAuthorityClaim.Role.ISSUER)) {
                issuers.add(anchor);
            }
            if (anchor.roles().contains(HoldReleaseAuthorityClaim.Role.APPROVER)) {
                approvers.add(anchor);
            }
        }
        Map<String, Boolean> checked = new HashMap<>();
        for (HoldReleaseTrustAnchor issuer : issuers) {
            for (HoldReleaseTrustAnchor approver : approvers) {
                if (issuer.authorityId().equals(approver.authorityId())
                        || issuer.principalRef().equals(approver.principalRef())
                        || issuer.publicKey().equals(approver.publicKey())) {
                    continue;
                }
                if (checked.computeIfAbsent(issuer.anchorId(), id -> isActive(revocations, id))
                        && checked.computeIfAbsent(approver.anchorId(), id -> isActive(revocations, id))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** verifier（{@code PurePolicyVerificationKernel.verifyTimeWindow}・{@code HoldReleaseProofVerifier}）と同じ半開区間。 */
    private static boolean withinValidity(Instant validFrom, Instant validUntil, Instant now) {
        return validFrom != null && validUntil != null && !now.isBefore(validFrom) && now.isBefore(validUntil);
    }

    private static boolean isActive(RevocationStatusProvider revocations, String anchorId) {
        try {
            return revocations.statusOf(anchorId) == RevocationStatus.ACTIVE;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
