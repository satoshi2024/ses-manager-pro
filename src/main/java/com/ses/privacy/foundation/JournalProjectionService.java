package com.ses.privacy.foundation;

import com.ses.privacy.foundation.PrivacyJournalPort.JournalAck;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** durable Journal ACK後に限り、検証済みevent prefixを別transactionでprojectionする。 */
final class JournalProjectionService {
    private static final String ZERO_HASH = "0".repeat(64);
    private final PrivacyJournalPort journal;
    private final PrivacyProjectionPort projection;

    JournalProjectionService(PrivacyJournalPort journal, PrivacyProjectionPort projection) {
        this.journal = Objects.requireNonNull(journal, "journal");
        this.projection = Objects.requireNonNull(projection, "projection");
    }

    PrivacyProjectionPort.ProjectionResult projectAcknowledged(JournalPartition partition, JournalAck ack) {
        if (partition == null || ack == null || ack.commandId() == null || ack.commandId().isBlank()
                || ack.revision() < 1 || !isHash(ack.eventHash())) {
            return quarantine(0, null, PrivacyProjectionPort.QuarantineCode.ACK_INVALID);
        }
        final JournalSnapshot source;
        try {
            source = readStableJournal(partition);
        } catch (RuntimeException unavailable) {
            return quarantine(ack.revision(), ack.eventHash(), PrivacyProjectionPort.QuarantineCode.JOURNAL_UNAVAILABLE);
        }
        if (source == null) return quarantine(ack.revision(), ack.eventHash(), PrivacyProjectionPort.QuarantineCode.JOURNAL_UNAVAILABLE);
        List<JournalEvent> events = source.events();
        if (!verifyChain(partition, source.head(), events)) {
            return quarantine(ack.revision(), ack.eventHash(), PrivacyProjectionPort.QuarantineCode.JOURNAL_CHAIN_INVALID);
        }
        int ackIndex;
        try { ackIndex = Math.toIntExact(ack.revision() - 1); }
        catch (ArithmeticException outOfRange) {
            return quarantine(ack.revision(), ack.eventHash(), PrivacyProjectionPort.QuarantineCode.ACK_INVALID);
        }
        if (ackIndex < 0 || ackIndex >= events.size()) {
            return quarantine(ack.revision(), ack.eventHash(), PrivacyProjectionPort.QuarantineCode.ACK_NOT_FOUND);
        }
        JournalEvent acknowledged = events.get(ackIndex);
        if (acknowledged.revision() != ack.revision() || !ack.commandId().equals(acknowledged.commandId())
                || !ack.eventHash().equals(acknowledged.eventHash())) {
            return quarantine(ack.revision(), ack.eventHash(), PrivacyProjectionPort.QuarantineCode.ACK_NOT_FOUND);
        }

        PrivacyProjectionPort.ProjectionSnapshot current;
        try {
            current = projection.snapshot(partition);
        } catch (RuntimeException unavailable) {
            return quarantine(ack.revision(), ack.eventHash(), PrivacyProjectionPort.QuarantineCode.PROJECTION_UNAVAILABLE);
        }
        if (!snapshotMatchesJournal(partition, current, events)) {
            return quarantine(ack.revision(), ack.eventHash(), PrivacyProjectionPort.QuarantineCode.PROJECTION_MISMATCH);
        }
        if (current.revision() >= ack.revision()) {
            return PrivacyProjectionPort.ProjectionResult.noOp(current.revision(), current.eventHash());
        }

        boolean applied = false;
        while (current.revision() < ack.revision()) {
            JournalEvent event = events.get(Math.toIntExact(current.revision()));
            PrivacyProjectionPort.ProjectionResult result = null;
            try {
                result = projection.apply(new VerifiedJournalEvent(partition, event));
            } catch (RuntimeException uncertain) {
                // commit応答不明は下の独立snapshot read-backで収束判定する。
            }

            PrivacyProjectionPort.ProjectionSnapshot observed;
            try {
                observed = projection.snapshot(partition);
            } catch (RuntimeException unavailable) {
                return quarantine(ack.revision(), ack.eventHash(), PrivacyProjectionPort.QuarantineCode.PROJECTION_UNAVAILABLE);
            }
            if (!snapshotMatchesJournal(partition, observed, events) || observed.revision() < current.revision()) {
                return quarantine(ack.revision(), ack.eventHash(), PrivacyProjectionPort.QuarantineCode.PROJECTION_MISMATCH);
            }
            if (observed.revision() == current.revision()) {
                PrivacyProjectionPort.QuarantineCode reason = result != null && result.status() == PrivacyProjectionPort.Status.QUARANTINED
                        ? result.reason() : PrivacyProjectionPort.QuarantineCode.PROJECTION_UNAVAILABLE;
                return quarantine(ack.revision(), ack.eventHash(), reason);
            }
            applied |= result != null && result.status() == PrivacyProjectionPort.Status.APPLIED;
            current = observed;
        }

        if (!snapshotMatchesJournal(partition, current, events) || current.revision() < ack.revision()
                || (current.revision() == ack.revision() && !current.eventHash().equals(ack.eventHash()))) {
            return quarantine(ack.revision(), ack.eventHash(), PrivacyProjectionPort.QuarantineCode.PROJECTION_MISMATCH);
        }
        return current.revision() > ack.revision() || !applied
                ? PrivacyProjectionPort.ProjectionResult.noOp(current.revision(), current.eventHash())
                : PrivacyProjectionPort.ProjectionResult.applied(current.revision(), current.eventHash());
    }

    private JournalSnapshot readStableJournal(JournalPartition partition) {
        for (int attempt = 0; attempt < 3; attempt++) {
            List<JournalEvent> events = List.copyOf(journal.events(partition));
            JournalHead head = journal.head(partition);
            if (head == null || !partition.equals(head.partition()) || head.revision() != events.size()) continue;
            String lastHash = events.isEmpty() ? ZERO_HASH : events.get(events.size() - 1).eventHash();
            if (lastHash.equals(head.eventHash())) return new JournalSnapshot(head, events);
        }
        return null;
    }

    private static boolean verifyChain(JournalPartition partition, JournalHead head, List<JournalEvent> events) {
        if (head == null || !partition.equals(head.partition()) || head.revision() != events.size()) return false;
        String previous = ZERO_HASH;
        long revision = 1;
        for (JournalEvent event : events) {
            if (event == null || event.revision() != revision || !previous.equals(event.previousHash())
                    || !isHash(event.eventHash()) || !isHash(event.payloadHash())
                    || event.commandId() == null || event.commandId().isBlank() || event.type() == null
                    || event.createdAt() == null || event.type() == PrivacyJournalPort.CommandType.HOLD_RELEASED
                    || (event.type() == PrivacyJournalPort.CommandType.DISPATCH_AUTHORIZED
                    && (event.actionId() == null || event.actionId().isBlank()))
                    || !eventHash(partition, event, previous).equals(event.eventHash())) return false;
            previous = event.eventHash();
            revision++;
        }
        return head.eventHash().equals(previous);
    }

    private static boolean snapshotMatchesJournal(JournalPartition partition,
            PrivacyProjectionPort.ProjectionSnapshot snapshot, List<JournalEvent> events) {
        if (snapshot == null || !partition.equals(snapshot.partition()) || snapshot.revision() < 0
                || snapshot.revision() > events.size()) return false;
        ProjectedState expected = ProjectedState.initial();
        String hash = ZERO_HASH;
        for (int i = 0; i < snapshot.revision(); i++) {
            JournalEvent event = events.get(i);
            expected = expected.after(event);
            hash = event.eventHash();
        }
        return Objects.equals(snapshot.eventHash(), hash) && snapshot.held() == expected.held()
                && Objects.equals(snapshot.lastActionId(), expected.lastActionId())
                && Objects.equals(snapshot.lastActionState(), expected.lastActionState());
    }

    private static String eventHash(JournalPartition partition, JournalEvent event, String previous) {
        StringBuilder bytes = new StringBuilder();
        field(bytes, "SES-SF-W2-EVENT-V1");
        field(bytes, partition.tenantId());
        field(bytes, partition.subjectRef());
        field(bytes, Long.toString(event.revision()));
        field(bytes, event.commandId());
        field(bytes, event.payloadHash());
        field(bytes, event.type().name());
        field(bytes, event.caseId());
        field(bytes, event.actionId());
        field(bytes, event.requestHash());
        field(bytes, previous);
        field(bytes, event.workerId());
        field(bytes, Long.toString(event.expectedLeaseGeneration()));
        field(bytes, event.leaseUntil() == null ? null : event.leaseUntil().toString());
        field(bytes, event.createdAt().toString());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(bytes.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256が利用できません", impossible);
        }
    }

    private static void field(StringBuilder target, String value) {
        if (value == null) target.append("-1:");
        else target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }

    private static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private static PrivacyProjectionPort.ProjectionResult quarantine(long revision, String hash,
            PrivacyProjectionPort.QuarantineCode code) {
        return PrivacyProjectionPort.ProjectionResult.quarantine(revision, hash, code);
    }

    private record JournalSnapshot(JournalHead head, List<JournalEvent> events) { }

    private record ProjectedState(boolean held, String lastActionId, String lastActionState) {
        private static ProjectedState initial() { return new ProjectedState(false, null, "NONE"); }
        private ProjectedState after(JournalEvent event) {
            return switch (event.type()) {
                case HOLD_STARTED -> new ProjectedState(true, lastActionId, lastActionState);
                case HOLD_RELEASED -> throw new IllegalArgumentException("hold解除eventはprojection対象外です");
                case DISPATCH_AUTHORIZED -> new ProjectedState(held, event.actionId(), "AUTHORIZED");
                case LEASE_CLAIMED, LEASE_TAKEN_OVER -> this;
            };
        }
    }

    static final class VerifiedJournalEvent {
        private final JournalPartition partition;
        private final JournalEvent event;
        private VerifiedJournalEvent(JournalPartition partition, JournalEvent event) {
            this.partition = partition;
            this.event = event;
        }
        JournalPartition partition() { return partition; }
        JournalEvent event() { return event; }
    }
}


