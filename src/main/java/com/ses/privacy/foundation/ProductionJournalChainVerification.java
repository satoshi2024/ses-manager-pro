package com.ses.privacy.foundation;

import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;

import java.util.List;

/**
 * 本番 hold projection（NF07-A1H）が Journal の event を projection へ書く前に、foundation の chain 検証
 * （{@link JournalProjectionService#verifyChain}）を通すための入口（production-bridge-phased-plan.md §3.4.6 の5）。
 *
 * <p>{@code JournalProjectionService} は package-private で、既存の foundation の class は変更しない（I-6）。本 class は
 * 新規の静的委譲だけを持ち、状態・Spring の登録・DB 接続を持たない。本番 adapter の {@code head}・{@code events} も
 * 読み取り時に同じ規則で chain を検証するが、projection の側では別実装（foundation の検証）でもう一度確かめ、
 * どちらかの乖離を projection の停止（fail-closed）として扱う。
 */
public final class ProductionJournalChainVerification {

    private ProductionJournalChainVerification() {
    }

    /**
     * head と event 列が partition の chain として整合するか。
     *
     * @return 整合すれば true。引数のいずれかが null、または検証できなければ false
     */
    public static boolean verify(JournalPartition partition, JournalHead head, List<JournalEvent> events) {
        if (partition == null || head == null || events == null) {
            return false;
        }
        try {
            return JournalProjectionService.verifyChain(partition, head, List.copyOf(events));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
