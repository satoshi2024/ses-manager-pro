package com.ses.common.constant;

/**
 * 契約更新カレンダーの更新状態定数（FR-06）。
 * 表示用の状態は5種類だが、エスカレーション判定の「対応済み」は
 * CONFIRMED / CONTINUE / END_SCHEDULED のみ（DRAFTはまだ未確定のため対応済み扱いしない）。
 */
public final class RenewalState {

    /** 未対応（更新期限が近いが何も対応していない） */
    public static final String UNHANDLED = "UNHANDLED";

    /** 更新ドラフト有（自動生成された後続契約ドラフトが未確定） */
    public static final String DRAFT = "DRAFT";

    /** 確定（更新ドラフトが確定済み） */
    public static final String CONFIRMED = "CONFIRMED";

    /** 継続確定（更新不要の明示フラグ、同一契約を継続） */
    public static final String CONTINUE = "CONTINUE";

    /** 終了予定（更新しない明示フラグ） */
    public static final String END_SCHEDULED = "END_SCHEDULED";

    /** {@code Contract.renewalDecision} に保存する値 */
    public static final String DECISION_CONTINUE = "CONTINUE";
    public static final String DECISION_END = "END";

    private RenewalState() {
    }

    /** エスカレーション通知を停止してよい「対応済み」かどうか。 */
    public static boolean isHandled(String state) {
        return CONFIRMED.equals(state) || CONTINUE.equals(state) || END_SCHEDULED.equals(state);
    }
}
