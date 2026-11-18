package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.PrivacyJournalPort;

import java.time.Instant;
import java.util.List;

/**
 * tenant 内の hold の Journal 上の位置を引く読み取り専用の索引（NF07-A1H）。
 *
 * <p>{@code PrivacyJournalPort} は partition（{@code (tenant, subject_ref)}）単位の操作しか持たないため、
 * 「command ID で Journal を照会する」（production-bridge-phased-plan.md §3.4.4 の reconciler）と「tenant の hold event と
 * head を projection と突き合わせる」（gate）にはこの索引を使う。索引は位置と hash だけを返し、projection へ書く内容は
 * 必ず port の {@code events}・{@code head}（adapter の chain 検証）と foundation の chain 検証を通した event から作る。
 * 照会できない場合は {@code JournalUnavailableException} を投げる（成功・空のどちらとも推定しない）。
 */
interface PrivacyHoldJournalIndex {

    /** command ID の位置（同じ tenant で command ID が partition をまたいで重なれば複数件）。 */
    List<CommandLocation> findCommand(String tenantId, String commandId);

    /** tenant の HOLD_STARTED・HOLD_RELEASED event と全 partition の head を同じ読み取り snapshot で返す。 */
    TenantView tenantView(String tenantId);

    /** Journal instance の {@code @@server_uuid}（application DB と同じ instance への誤接続を実行中に検出する。Review P3-8）。 */
    String serverUuid();

    /** command の位置。 */
    record CommandLocation(String partitionRef, long revision, String eventHash) {
    }

    /**
     * hold の event の位置（{@code caseId} は hold ID）。{@code type} は HOLD_STARTED か HOLD_RELEASED、{@code createdAt} は
     * Journal の event 時刻（解除の projection の {@code released_at} と照合する。NF07-REL）。
     */
    record HoldEvent(String partitionRef, long revision, String commandId, String caseId, String eventHash,
                     PrivacyJournalPort.CommandType type, Instant createdAt) {

        boolean start() {
            return type == PrivacyJournalPort.CommandType.HOLD_STARTED;
        }

        boolean release() {
            return type == PrivacyJournalPort.CommandType.HOLD_RELEASED;
        }
    }

    /** partition の head。 */
    record HeadState(String partitionRef, long revision, String eventHash, boolean held) {
    }

    /** tenant の hold event と head。 */
    record TenantView(List<HoldEvent> holdEvents, List<HeadState> heads) {
        public TenantView {
            holdEvents = List.copyOf(holdEvents);
            heads = List.copyOf(heads);
        }
    }
}
