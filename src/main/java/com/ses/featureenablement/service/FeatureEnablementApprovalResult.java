package com.ses.featureenablement.service;

import java.util.List;

/**
 * 承認の結果（design §5.3 の review P3-8）。{@code STALE}・{@code EXPIRED}・{@code CONDITIONS_UNSATISFIED} は 409 だが、申請の終了・
 * 監査・失効の先行の閉鎖を commit してから返すため、例外ではなく結果の値で返す（controller が 409 にする）。
 *
 * @param outcome   結果
 * @param requestId 申請
 * @param codes     成り立たない条件の code（{@code CONDITIONS_UNSATISFIED} の時）
 */
public record FeatureEnablementApprovalResult(Outcome outcome, Long requestId, List<String> codes) {

    public FeatureEnablementApprovalResult {
        codes = codes == null ? List.of() : List.copyOf(codes);
    }

    /** 承認の結果の種類と、409 にする時の message key。 */
    public enum Outcome {
        APPROVED(null),
        /** 指紋が申請の時と違う（申請を STALE で終えた）。 */
        STALE("error.featureEnablement.requestStale"),
        /** 期限を過ぎていた（申請を EXPIRED で終えた）。 */
        EXPIRED("error.featureEnablement.requestExpired"),
        /** 条件が成り立たない（申請は未決のまま）。 */
        CONDITIONS_UNSATISFIED("error.featureEnablement.conditionsUnsatisfied");

        private final String messageKey;

        Outcome(String messageKey) {
            this.messageKey = messageKey;
        }

        public String messageKey() {
            return messageKey;
        }
    }

    public static FeatureEnablementApprovalResult approved(Long requestId) {
        return new FeatureEnablementApprovalResult(Outcome.APPROVED, requestId, List.of());
    }

    public boolean approved() {
        return outcome == Outcome.APPROVED;
    }
}
