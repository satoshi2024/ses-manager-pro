package com.ses.privacy.foundation;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 独立journalのtyped境界。W2aではsynthetic adapter以外の実装を提供しない。
 * ACKは合成eventの線形化記録であり、VerifiedDispatchFenceや実policy承認ではない。
 */
public interface PrivacyJournalPort {
    JournalAck append(JournalCommand command);

    JournalHead head(JournalPartition partition);

    List<JournalEvent> events(JournalPartition partition);

    record JournalPartition(String tenantId, String subjectRef) {
        public JournalPartition {
            if (tenantId == null || tenantId.isBlank() || subjectRef == null || subjectRef.isBlank()) {
                throw new IllegalArgumentException("partitionは必須です");
            }
        }
    }

    enum CommandType { HOLD_STARTED, HOLD_RELEASED, DISPATCH_AUTHORIZED, LEASE_CLAIMED, LEASE_TAKEN_OVER }

    record JournalCommand(String commandId, String payloadHash, JournalPartition partition,
                          long expectedRevision, CommandType type, String caseId,
                          String actionId, String requestHash, String workerId,
                          long expectedLeaseGeneration, Instant leaseUntil) {
        public JournalCommand {
            if (commandId == null || commandId.isBlank() || payloadHash == null || !payloadHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("command識別子/hashが不正です");
            if (partition == null || type == null || expectedRevision < 0)
                throw new IllegalArgumentException("command必須項目が不足しています");
            if (leaseUntil != null) leaseUntil = leaseUntil.truncatedTo(ChronoUnit.MICROS);
            if ((type == CommandType.DISPATCH_AUTHORIZED) &&
                    (caseId == null || caseId.isBlank() || actionId == null || actionId.isBlank()
                            || requestHash == null || !requestHash.matches("[0-9a-f]{64}")
                            || workerId == null || workerId.isBlank() || expectedLeaseGeneration < 1))
                throw new IllegalArgumentException("認可eventにはcase/action/request hashとlease fenceが必要です");
            if ((type == CommandType.LEASE_CLAIMED || type == CommandType.LEASE_TAKEN_OVER)
                    && (workerId == null || workerId.isBlank() || leaseUntil == null))
                throw new IllegalArgumentException("lease情報が不足しています");
            if (!payloadHash.equals(commandPayloadHash(partition, expectedRevision, type, caseId, actionId,
                    requestHash, workerId, expectedLeaseGeneration, leaseUntil)))
                throw new IllegalArgumentException("payload hashがtyped command内容と一致しません");
        }

        public static JournalCommand create(String commandId, JournalPartition partition, long expectedRevision,
                                            CommandType type, String caseId, String actionId, String requestHash,
                                            String workerId, long expectedLeaseGeneration, Instant leaseUntil) {
            return new JournalCommand(commandId, commandPayloadHash(partition, expectedRevision, type, caseId,
                    actionId, requestHash, workerId, expectedLeaseGeneration, leaseUntil), partition,
                    expectedRevision, type, caseId, actionId, requestHash, workerId, expectedLeaseGeneration,
                    leaseUntil);
        }
    }

    record JournalAck(String commandId, long revision, String eventHash, boolean replayed) { }
    record JournalHead(JournalPartition partition, long revision, String eventHash, boolean held,
                       long leaseGeneration, String leaseOwner, Instant leaseUntil) { }
    record JournalEvent(long revision, String commandId, String payloadHash, CommandType type,
                        String caseId, String actionId, String requestHash, String workerId,
                        long expectedLeaseGeneration, Instant leaseUntil, String previousHash,
                        String eventHash, Instant createdAt) { }

    private static String commandPayloadHash(JournalPartition partition, long revision, CommandType type,
                                             String caseId, String actionId, String requestHash,
                                             String workerId, long generation, Instant leaseUntil) {
        if (leaseUntil != null) leaseUntil = leaseUntil.truncatedTo(ChronoUnit.MICROS);
        StringBuilder canonical = new StringBuilder();
        payloadField(canonical, "SES-SF-W2-COMMAND-V1");
        payloadField(canonical, partition == null ? null : partition.tenantId());
        payloadField(canonical, partition == null ? null : partition.subjectRef());
        payloadField(canonical, Long.toString(revision));
        payloadField(canonical, type == null ? null : type.name());
        payloadField(canonical, caseId);
        payloadField(canonical, actionId);
        payloadField(canonical, requestHash);
        payloadField(canonical, workerId);
        payloadField(canonical, Long.toString(generation));
        payloadField(canonical, leaseUntil == null ? null : leaseUntil.toString());
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    private static void payloadField(StringBuilder target, String value) {
        if (value == null) target.append("-1:");
        else target.append(value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).append(':').append(value);
    }
}
