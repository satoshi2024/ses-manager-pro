package com.ses.privacy.foundation;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * policy envelope の登録・有効化の検証（NF07-REG）。package-private で、{@link ProductionPrivacyComponents} からだけ生成する
 * （prod profile 必須）。Spring Bean には登録しない。状態を持たず、trust anchor と失効状態は検証のたびに読む。
 *
 * <p>判定は照会（{@code ProductionPolicyReadonlyQueryService}）と同じ部品を使う: canonical bytes の厳格な復号
 * （{@link ProductionPolicyEnvelopeDecoder}）、{@link ProductionApprovalEnvelopeVerifier#verifyForRegistration}
 * （audience・tenant・schema・trust anchor・範囲・時刻・失効状態・署名・SoD）。失敗は全て
 * {@link PolicyRegistrationRejectedException}（固定 code）にする。
 */
final class ProductionPolicyRegistrationVerifier implements PolicyRegistrationVerifier {

    private final ProductionPolicyTrustRegistry registry;
    private final RevocationStatusProvider revocations;
    private final ProductionEnvironmentGuard environmentGuard;
    private final Clock clock;

    ProductionPolicyRegistrationVerifier(ProductionPolicyTrustRegistry registry, RevocationStatusProvider revocations,
                                         ProductionEnvironmentGuard environmentGuard, Clock clock) {
        this.registry = registry;
        this.revocations = revocations;
        this.environmentGuard = Objects.requireNonNull(environmentGuard, "environmentGuard");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public VerifiedPolicyRegistration verify(String callerTenant, byte[] canonical, byte[] signature) {
        environmentGuard.requireProductionProfile();
        try {
            PolicyApprovalEnvelope envelope = ProductionPolicyEnvelopeDecoder.decode(canonical, signature);
            Map<String, TrustedAuthority> anchors;
            try {
                anchors = registry == null ? null : registry.anchors();
            } catch (RuntimeException unavailable) {
                anchors = null;
            }
            if (anchors == null || anchors.isEmpty()) {
                throw new PolicyRegistrationRejectedException(PolicyFailureReason.UNKNOWN_TRUST_ANCHOR);
            }
            VerifiedPolicyMetadata verified = new ProductionApprovalEnvelopeVerifier(anchors, revocations,
                    environmentGuard, clock).verifyForRegistration(callerTenant, envelope);
            return new VerifiedPolicyRegistration(envelope, canonical, verified);
        } catch (PolicySecurityException rejected) {
            throw new PolicyRegistrationRejectedException(rejected.reason());
        }
    }

    @Override
    public java.time.Instant signedIssuedAt(byte[] canonical, byte[] signature) {
        environmentGuard.requireProductionProfile();
        try {
            return ProductionPolicyEnvelopeDecoder.decode(canonical, signature).metadata().issuedAt();
        } catch (PolicySecurityException rejected) {
            throw new PolicyRegistrationRejectedException(rejected.reason());
        }
    }

    @Override
    public boolean trustReady() {
        try {
            Map<String, TrustedAuthority> anchors = registry == null ? null : registry.anchors();
            return anchors != null && PrivacyTrustReadiness.activePolicyAnchors(anchors, revocations, 1) >= 1;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
