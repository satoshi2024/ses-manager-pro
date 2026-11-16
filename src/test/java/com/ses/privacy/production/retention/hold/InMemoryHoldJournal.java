package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.JournalConflictException;
import com.ses.privacy.foundation.JournalUnavailableException;
import com.ses.privacy.foundation.PrivacyJournalPort;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * test 用の Journal（port と索引）。本番 adapter と同じ判定順（command の再送 → revision CAS → held）と、foundation の
 * chain 検証と同じ event hash（{@code SES-SF-W2-EVENT-V1}）を持つ。障害の注入（不達・ACK 不明・競合）と append の
 * 前に走る hook を持つ。
 */
final class InMemoryHoldJournal implements PrivacyJournalPort, PrivacyHoldJournalIndex {

    private static final String ZERO = "0".repeat(64);

    private final Map<String, List<JournalEvent>> partitions = new LinkedHashMap<>();
    private final Clock clock;
    final AtomicInteger appendCalls = new AtomicInteger();
    volatile boolean unavailable;
    /** 次の N 回の append を「確定させてから ACK 不明にする」。 */
    final AtomicInteger commitThenFail = new AtomicInteger();
    /** 次の N 回の append を「確定させずに ACK 不明にする」。 */
    final AtomicInteger failWithoutCommit = new AtomicInteger();
    volatile Runnable beforeAppend = () -> { };

    InMemoryHoldJournal(Clock clock) {
        this.clock = clock;
    }

    @Override
    public synchronized JournalAck append(JournalCommand command) {
        appendCalls.incrementAndGet();
        beforeAppend.run();
        requireAvailable();
        if (failWithoutCommit.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new JournalUnavailableException("test: append before commit failed", null);
        }
        JournalAck ack = appendOrReplay(command);
        if (commitThenFail.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new JournalUnavailableException("test: ACK lost after commit", null);
        }
        return ack;
    }

    private JournalAck appendOrReplay(JournalCommand command) {
        String key = key(command.partition());
        List<JournalEvent> events = partitions.computeIfAbsent(key, k -> new ArrayList<>());
        for (JournalEvent event : events) {
            if (event.commandId().equals(command.commandId())) {
                if (!event.payloadHash().equals(command.payloadHash())) {
                    throw new JournalConflictException("command IDの再利用内容が一致しません");
                }
                return new JournalAck(command.commandId(), event.revision(), event.eventHash(), true);
            }
        }
        if (events.size() != command.expectedRevision()) {
            throw new JournalConflictException("journal revisionが競合しました");
        }
        if (command.type() == CommandType.HOLD_STARTED && held(events)) {
            throw new JournalConflictException("holdは既に有効です");
        }
        long revision = events.size() + 1L;
        String previous = events.isEmpty() ? ZERO : events.get(events.size() - 1).eventHash();
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        JournalEvent draft = new JournalEvent(revision, command.commandId(), command.payloadHash(), command.type(),
                command.caseId(), command.actionId(), command.requestHash(), command.workerId(),
                command.expectedLeaseGeneration(), command.leaseUntil(), previous, null, createdAt);
        String hash = eventHash(command.partition(), draft, previous);
        events.add(new JournalEvent(revision, command.commandId(), command.payloadHash(), command.type(),
                command.caseId(), command.actionId(), command.requestHash(), command.workerId(),
                command.expectedLeaseGeneration(), command.leaseUntil(), previous, hash, createdAt));
        return new JournalAck(command.commandId(), revision, hash, false);
    }

    @Override
    public synchronized JournalHead head(JournalPartition partition) {
        requireAvailable();
        List<JournalEvent> events = partitions.getOrDefault(key(partition), List.of());
        String hash = events.isEmpty() ? ZERO : events.get(events.size() - 1).eventHash();
        return new JournalHead(partition, events.size(), hash, held(events), 0, null, null);
    }

    @Override
    public synchronized List<JournalEvent> events(JournalPartition partition) {
        requireAvailable();
        return List.copyOf(partitions.getOrDefault(key(partition), List.of()));
    }

    @Override
    public synchronized List<CommandLocation> findCommand(String tenantId, String commandId) {
        requireAvailable();
        List<CommandLocation> locations = new ArrayList<>();
        partitions.forEach((key, events) -> {
            if (key.startsWith(tenantId + "\u0000")) {
                for (JournalEvent event : events) {
                    if (event.commandId().equals(commandId)) {
                        locations.add(new CommandLocation(subjectOf(key), event.revision(), event.eventHash()));
                    }
                }
            }
        });
        return locations;
    }

    @Override
    public synchronized TenantView tenantView(String tenantId) {
        requireAvailable();
        List<HoldEvent> holds = new ArrayList<>();
        List<HeadState> heads = new ArrayList<>();
        partitions.forEach((key, events) -> {
            if (key.startsWith(tenantId + "\u0000") && !events.isEmpty()) {
                for (JournalEvent event : events) {
                    if (event.type() == CommandType.HOLD_STARTED) {
                        holds.add(new HoldEvent(subjectOf(key), event.revision(), event.commandId(), event.caseId(),
                                event.eventHash()));
                    }
                }
                JournalEvent last = events.get(events.size() - 1);
                heads.add(new HeadState(subjectOf(key), last.revision(), last.eventHash(), held(events)));
            }
        });
        return new TenantView(holds, heads);
    }

    synchronized int eventCount(String tenantId, String subjectRef) {
        return partitions.getOrDefault(tenantId + "\u0000" + subjectRef, List.of()).size();
    }

    private void requireAvailable() {
        if (unavailable) {
            throw new JournalUnavailableException("test: journal unreachable", null);
        }
    }

    private static boolean held(List<JournalEvent> events) {
        boolean held = false;
        for (JournalEvent event : events) {
            if (event.type() == CommandType.HOLD_STARTED) {
                held = true;
            } else if (event.type() == CommandType.HOLD_RELEASED) {
                held = false;
            }
        }
        return held;
    }

    private static String key(JournalPartition partition) {
        return partition.tenantId() + "\u0000" + partition.subjectRef();
    }

    private static String subjectOf(String key) {
        return key.substring(key.indexOf('\u0000') + 1);
    }

    /** foundation の {@code JournalProjectionService.eventHash} と同じ正規形。 */
    static String eventHash(JournalPartition partition, JournalEvent event, String previous) {
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
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void field(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
    }
}
