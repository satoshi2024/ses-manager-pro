package com.ses.privacy.foundation;

import com.ses.privacy.foundation.PrivacyJournalPort.JournalAck;

/**
 * hold 解除の Journal への入口（NF07-REL）。{@link PrivacyJournalPort#append} は HOLD_RELEASED を常に拒否し、解除は
 * 検証済みの proof を受け取る本 port だけが append する。本番実装は {@code ProductionPrivacyJournalAdapter}。合成 adapter は
 * 本 port を実装しない（I-6。合成層の解除の拒否は変えない）。
 */
public interface HoldReleaseJournalPort {

    /**
     * HOLD_RELEASED を append する。同じ command ID・同じ内容の再送は元の ACK を返す（{@code replayed=true}）。
     *
     * @throws JournalConflictException     同じ command ID の別内容、head・hold revision・hold ID の不一致、hold が無効
     * @throws HoldReleaseRejectedException proof の期限切れ
     * @throws JournalUnavailableException  ACK を確認できない（同じ proof で照会・再送する）
     * @throws JournalAccessDeniedException principal に append の権限が無い（本番 adapter。文の実行の前の拒否で append は起きていない。
     *                                      ただし ACK 不明の後の再送で返った時は、先の試行の結果は不明のまま。review rb-fe-c P3-1・P3-N1）
     */
    JournalAck appendHoldRelease(VerifiedHoldRelease release);
}
