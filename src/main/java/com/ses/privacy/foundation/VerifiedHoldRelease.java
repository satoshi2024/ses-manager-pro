package com.ses.privacy.foundation;

import com.ses.privacy.foundation.PrivacyJournalPort.CommandType;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalCommand;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 検証済みの hold 解除 proof（NF07-REL、sf-w2-protocol.md §11.1 の {@code VerifiedHoldRelease}）。
 *
 * <p>constructor は package-private で、{@link HoldReleaseProofVerifier}（{@link ProductionPrivacyComponents#holdReleaseVerifier}
 * 経由・prod 専用）だけが生成する。束縛（{@link HoldReleaseBinding}）は trust anchor で検証した発行者・承認者の署名で
 * 確かめたもので、caller の真偽値・actor 名・role 文字列からは作れない。HOLD_RELEASED command は束縛から一意に決まり
 * （{@link #command()}。{@code case_id} は hold ID、{@code request_hash} は束縛の digest）、proof と command の取り違えは
 * 起こらない。Journal は append の transaction の中で {@link #requireAppendable} を呼び、head と hold revision を CAS する。
 */
public final class VerifiedHoldRelease {

    private final HoldReleaseBinding binding;
    private final String targetType;
    private final String issuerAnchorId;
    private final String approverAnchorId;
    private final Instant expiresAt;
    private final Instant verifiedAt;
    private final JournalCommand command;

    VerifiedHoldRelease(HoldReleaseBinding binding, String targetType, String issuerAnchorId, String approverAnchorId,
                        Instant expiresAt, Instant verifiedAt) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.targetType = Objects.requireNonNull(targetType, "targetType");
        this.issuerAnchorId = Objects.requireNonNull(issuerAnchorId, "issuerAnchorId");
        this.approverAnchorId = Objects.requireNonNull(approverAnchorId, "approverAnchorId");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.verifiedAt = Objects.requireNonNull(verifiedAt, "verifiedAt");
        this.command = JournalCommand.create(binding.commandId(), binding.partition(),
                binding.expectedJournalRevision(), CommandType.HOLD_RELEASED, binding.holdId(), null,
                binding.digest(), null, 0, null);
    }

    public HoldReleaseBinding binding() {
        return binding;
    }

    /** 解除する hold の対象種別（{@code SUBJECT} / {@code DATA_ELEMENT}）。 */
    public String targetType() {
        return targetType;
    }

    /** 発行者の trust anchor（{@code authorityId:keyId}）。 */
    public String issuerAnchorId() {
        return issuerAnchorId;
    }

    /** 承認者の trust anchor（{@code authorityId:keyId}）。 */
    public String approverAnchorId() {
        return approverAnchorId;
    }

    /** proof の有効期限（両 claim の期限と両 trust anchor の有効期間の終わりの最小値）。 */
    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant verifiedAt() {
        return verifiedAt;
    }

    /** 束縛から決まる HOLD_RELEASED command。 */
    public JournalCommand command() {
        return command;
    }

    /**
     * Journal の append transaction の中（head を lock し、chain を検証した後・command の再送の判定の後）での判定。
     * 本番 adapter と test の Journal が同じ規則を使う。
     *
     * @param head    lock した head
     * @param events  検証済みの event 列
     * @param eventAt append する event の時刻（Journal の時計）
     * @throws JournalConflictException     head・hold revision・hold ID が一致しない、hold が有効でない
     * @throws HoldReleaseRejectedException proof の期限切れ（{@link HoldReleaseRejection#EXPIRED}）
     */
    public void requireAppendable(JournalHead head, List<JournalEvent> events, Instant eventAt) {
        if (head == null || events == null || !binding.partition().equals(head.partition())) {
            throw new JournalConflictException("解除するpartitionが一致しません");
        }
        if (eventAt == null || !eventAt.isBefore(expiresAt)) {
            throw new HoldReleaseRejectedException(HoldReleaseRejection.EXPIRED);
        }
        if (head.revision() != binding.expectedJournalRevision()
                || !Objects.equals(head.eventHash(), binding.expectedJournalHash())) {
            throw new JournalConflictException("journal headが解除proofの期待値と一致しません");
        }
        if (!head.held()) {
            throw new JournalConflictException("解除するholdが有効ではありません");
        }
        JournalEvent active = activeHoldStart(events);
        if (active == null || !binding.holdId().equals(active.caseId())) {
            throw new JournalConflictException("有効なholdのIDが解除proofと一致しません");
        }
        if (active.revision() != binding.expectedHoldRevision()) {
            throw new JournalConflictException("hold revisionが解除proofと一致しません");
        }
    }

    /** 有効な hold の HOLD_STARTED（最後の HOLD_STARTED の後に HOLD_RELEASED が無ければそれ。無ければ null）。 */
    static JournalEvent activeHoldStart(List<JournalEvent> events) {
        JournalEvent active = null;
        for (JournalEvent event : events) {
            if (event.type() == CommandType.HOLD_STARTED) {
                active = event;
            } else if (event.type() == CommandType.HOLD_RELEASED) {
                active = null;
            }
        }
        return active;
    }

    @Override
    public String toString() {
        return "VerifiedHoldRelease{targetType=" + targetType + "}";
    }
}
