package com.ses.service.ai.copilot.dispatch;

/**
 * {@code t_ai_recommendation_run.dispatch_state}（V194）。
 * <ul>
 *   <li>{@link #NOT_APPLICABLE}: query・legacy・local summary行（既存行の既定値）。claimできない。</li>
 *   <li>{@link #READY}: preflight許可後に発行時bindingを固定したsummary dispatch attempt。一度だけclaimできる。</li>
 *   <li>{@link #CLAIMED}: claimがcommitされた。送信の有無にかかわらず {@code READY} / {@code NOT_SENT} へ戻さず、
 *       再claim・再送しない。完了記録が無いまま期限を過ぎた行は意味上UNKNOWNとして照合する。</li>
 *   <li>{@link #UNKNOWN}: 照合（B2e）が送信不明を確定させた状態。B2c-1では書かない。</li>
 * </ul>
 */
public enum DispatchState {
    NOT_APPLICABLE, READY, CLAIMED, UNKNOWN;

    /** DB値を読む。未知の値は例外（fail-closed）。 */
    static DispatchState of(String value) {
        if (value == null) {
            throw new IllegalStateException("dispatch_stateがありません");
        }
        return DispatchState.valueOf(value);
    }
}
