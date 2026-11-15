package com.ses.service.ai.copilot.dispatch;

/**
 * {@link CopilotDispatchClaimService#claim} の結果。
 * <ul>
 *   <li>{@link Status#CLAIMED}: claim transactionのcommitを確認した。{@link #permit()} が非null。</li>
 *   <li>{@link Status#NOT_CLAIMED}: commitされていないことが確定した（rollback・送信前拒否）。permitなし。
 *       同じticketでは再claimしない。</li>
 *   <li>{@link Status#UNCERTAIN}: callbackは完了したがcommitの成否を確認できなかった。permitなし。
 *       別transactionの読み直し結果を {@link #observedState()} に置く（読めなければnull）。
 *       同じattemptは再claimしない（計画 §4 #6）。</li>
 * </ul>
 *
 * @param transactionAttempts claim transactionを開始した回数（1213/1205・bucket欠落・日付境界の再試行を含む。最大3）
 */
public record ClaimOutcome(Status status, String reasonCode, DispatchState observedState, ClaimPermit permit,
                           int transactionAttempts) {

    public enum Status { CLAIMED, NOT_CLAIMED, UNCERTAIN }

    public ClaimOutcome {
        if (status == null) {
            throw new IllegalArgumentException("statusが必要です");
        }
        if ((status == Status.CLAIMED) != (permit != null)) {
            throw new IllegalArgumentException("permitはCLAIMEDの場合だけ持つ");
        }
    }

    public boolean claimed() {
        return status == Status.CLAIMED;
    }
}
