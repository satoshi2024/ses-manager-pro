package com.ses.privacy.foundation;

import java.util.Objects;

/**
 * current policy source が1回の一貫した読取で返した snapshot。
 *
 * <p>envelope は保存された canonical bytes と署名のまま受け取り、復号・正規形の再確認は foundation 側
 * （{@code ProductionPolicyEnvelopeDecoder}）が行う。source の実装が envelope を組み立てて検証を省けないようにするため。
 * {@link CurrentStatus#CURRENT} 以外では bytes・revision・digest を持たない。
 */
public record CurrentPolicySnapshot(CurrentStatus status, String policyId, String revision, String digest,
                                    byte[] envelopeCanonical, byte[] envelopeSignature) {

    /** source の判定。0件=NOT_FOUND、2件以上・current pointer 競合=CONFLICT、照会不能=UNAVAILABLE。 */
    public enum CurrentStatus {
        CURRENT,
        NOT_FOUND,
        UNAPPROVED,
        CONFLICT,
        UNAVAILABLE
    }

    public CurrentPolicySnapshot {
        Objects.requireNonNull(status, "statusが必要です");
        envelopeCanonical = envelopeCanonical == null ? null : envelopeCanonical.clone();
        envelopeSignature = envelopeSignature == null ? null : envelopeSignature.clone();
    }

    public static CurrentPolicySnapshot current(String policyId, String revision, String digest,
                                                byte[] envelopeCanonical, byte[] envelopeSignature) {
        return new CurrentPolicySnapshot(CurrentStatus.CURRENT, policyId, revision, digest,
                envelopeCanonical, envelopeSignature);
    }

    public static CurrentPolicySnapshot of(CurrentStatus status) {
        if (status == CurrentStatus.CURRENT) {
            throw new IllegalArgumentException("CURRENTはcurrent(...)で作成してください");
        }
        return new CurrentPolicySnapshot(status, null, null, null, null, null);
    }

    @Override
    public byte[] envelopeCanonical() {
        return envelopeCanonical == null ? null : envelopeCanonical.clone();
    }

    @Override
    public byte[] envelopeSignature() {
        return envelopeSignature == null ? null : envelopeSignature.clone();
    }
}
