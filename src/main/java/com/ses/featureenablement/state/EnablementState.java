package com.ses.featureenablement.state;

/**
 * 開放単位の開放状態（{@code t_feature_enablement_state.state}。requirements FE-RQ-002）。「申請中」は未決の申請があることを表す
 * 表示上の状態で、この値ではない。行の無い開放単位は {@link #CLOSED}。
 */
public enum EnablementState {

    /** 未開放。 */
    CLOSED,
    /** 開放中。 */
    OPEN,
    /** 再起動待ち（NF05 だけ。次の起動で反映する）。実効値は閉。 */
    OPEN_PENDING_RESTART,
    /** 自動閉鎖（理由の code を持つ）。 */
    AUTO_CLOSED,
    /** 手動閉鎖。 */
    MANUALLY_CLOSED;

    /** 開く遷移の後の状態（閉鎖・自動閉鎖の対象）か。 */
    public boolean openLike() {
        return this == OPEN || this == OPEN_PENDING_RESTART;
    }

    /** 申請を受け付ける状態か（FE-RQ-002）。 */
    public boolean requestable() {
        return this == CLOSED || this == AUTO_CLOSED || this == MANUALLY_CLOSED;
    }

    /** DB の値（不明な値は閉の側の {@link #CLOSED} にしない。呼出し側で fail-closed にする）。 */
    public static EnablementState of(String value) {
        return value == null ? CLOSED : EnablementState.valueOf(value);
    }
}
