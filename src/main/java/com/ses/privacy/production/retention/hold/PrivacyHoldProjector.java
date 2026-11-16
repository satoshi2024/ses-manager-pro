package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.JournalUnavailableException;
import com.ses.privacy.foundation.PrivacyJournalPort;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;
import com.ses.privacy.foundation.ProductionJournalChainVerification;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * ACK 済みの HOLD_STARTED を projection へ書く共通部（hold 開始と reconciler の両方が使う。§3.4.4 の4）。
 *
 * <ul>
 *   <li>event は port の {@code events}・{@code head}（adapter の chain 検証）を同じ revision で読み、foundation の chain 検証
 *       （{@link ProductionJournalChainVerification}）を通したものだけを使う（§3.4.6 の5）。append と競合して revision が動いた
 *       場合は3回まで読み直し、揃わなければ Journal 不達として扱う。</li>
 *   <li>projection は application DB の独立 transaction（{@code REQUIRES_NEW}）で、追随位置の行を locking read で取ってから書く。
 *       ACK 済みの event の projection は pending の有無に関係なく正しいので常に書き、pending は自分の command のときだけ消す。
 *       同じ hold ID の行が既にあれば書き直さない（再実行で二重にならない）。</li>
 * </ul>
 * port の呼出しは application の transaction の外で行う（本番 adapter は ambient transaction 内からの呼出しを拒否する）。
 */
final class PrivacyHoldProjector {

    private static final int READ_ATTEMPTS = 3;

    private final PrivacyHoldProjectionStore store;
    private final PrivacyHoldJournal journal;
    private final TransactionTemplate requiresNew;
    private final PrivacyHoldAudit audit;
    private final Clock clock;

    PrivacyHoldProjector(PrivacyHoldProjectionStore store, PrivacyHoldJournal journal, TransactionTemplate requiresNew,
                         PrivacyHoldAudit audit, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.journal = Objects.requireNonNull(journal, "journal");
        this.requiresNew = Objects.requireNonNull(requiresNew, "requiresNew");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * partition の chain を検証し、command の event を返す（無ければ null）。
     *
     * @throws JournalUnavailableException 照会できない・chain が検証できない
     */
    JournalEvent acknowledgedEvent(String tenantId, String partitionRef, String commandId) {
        JournalPartition partition = new JournalPartition(tenantId, partitionRef);
        PrivacyJournalPort port = journal.port();
        for (int attempt = 0; attempt < READ_ATTEMPTS; attempt++) {
            List<JournalEvent> events = List.copyOf(port.events(partition));
            JournalHead head = port.head(partition);
            if (head == null || head.revision() != events.size()) {
                continue;
            }
            if (!ProductionJournalChainVerification.verify(partition, head, events)) {
                throw new JournalUnavailableException("journal chainを検証できません", null);
            }
            for (JournalEvent event : events) {
                if (commandId.equals(event.commandId())) {
                    return event;
                }
            }
            return null;
        }
        throw new JournalUnavailableException("journalの読み取りが安定しません", null);
    }

    /** event が hold 開始の HOLD_STARTED として解釈できるか（hold ID は {@code case_id}）。 */
    static boolean isHoldStart(JournalEvent event, String holdId) {
        return event != null && event.type() == PrivacyJournalPort.CommandType.HOLD_STARTED
                && holdId != null && holdId.equals(event.caseId());
    }

    /**
     * ACK 済みの hold を projection へ書き、追随位置を進める。
     *
     * @param clearCommandId pending がこの command なら消す（null なら pending に触れない）
     * @param auditEvent     required 監査の event 名
     * @param auditFields    監査本文（subject 識別子を含めない）
     * @return 新たに行を書いたら true（同じ hold ID の行が既にあれば false）
     */
    boolean project(String tenantId, PrivacyHoldProjectionStore.NewHold hold, String clearCommandId, String username,
                    String auditEvent, Map<String, String> auditFields) {
        String journalId = journal.journalId();
        Boolean inserted = requiresNew.execute(status -> {
            Instant now = clock.instant();
            if (store.lockState(tenantId, journalId).isEmpty()) {
                store.ensureStateLocked(tenantId, journalId, now);
            }
            boolean written = false;
            if (!store.holdExists(tenantId, hold.holdId())) {
                store.insertHold(tenantId, hold);
                written = true;
            } else if (!PrivacyHoldService.RECONCILED.equals(hold.reasonCode())) {
                // ACK と projection の間に reconciler が先に復元した行は、開始者自身の値に置き換える（Review P3-2）
                store.adoptReconciledMetadata(tenantId, hold);
            }
            List<PrivacyHoldProjectionStore.HoldRow> holds = store.holds(tenantId, true);
            store.advance(tenantId, journalId, holds.size(), PrivacyHoldProjectionStore.projectionDigest(holds),
                    clearCommandId, now);
            audit.record(username, auditEvent, auditFields, 200, true);
            return written;
        });
        return Boolean.TRUE.equals(inserted);
    }
}
