package com.ses.privacy.foundation;

import com.ses.privacy.foundation.PrivacyJournalPort.CommandType;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

/**
 * 本番 hold projection（NF07-A1H）が Journal の event を projection へ書く前に、foundation の chain 検証
 * （{@link JournalProjectionService#verifyChain}）を通すための入口（production-bridge-phased-plan.md §3.4.6 の5）。
 *
 * <p>{@code JournalProjectionService} は package-private で、既存の foundation の class は変更しない（I-6）。本 class は
 * 新規の静的委譲だけを持ち、状態・Spring の登録・DB 接続を持たない。本番 adapter の {@code head}・{@code events} も
 * 読み取り時に同じ規則で chain を検証するが、projection の側では別実装（foundation の検証）でもう一度確かめ、
 * どちらかの乖離を projection の停止（fail-closed）として扱う。
 *
 * <p>HOLD_RELEASED を含む chain（NF07-REL）: foundation の検証は合成層の規則として解除 event を受け付けない（変更しない）。
 * そのため、最初の解除 event より前の prefix を foundation の検証に通し、chain 全体は同じ正規形の event hash と hold の
 * 状態遷移（解除は有効な hold に対してだけ、その hold ID で1回。held な間の新規認可なし。head の held と一致）で
 * 本 class が検証する。解除 event を含まない chain の判定は従来どおり foundation の検証だけで決まる。
 */
public final class ProductionJournalChainVerification {

    private static final String EVENT_DOMAIN = "SES-SF-W2-EVENT-V1";
    private static final String ZERO_HASH = "0".repeat(64);

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
            List<JournalEvent> copy = List.copyOf(events);
            int firstRelease = firstReleaseIndex(copy);
            if (firstRelease < 0) {
                return JournalProjectionService.verifyChain(partition, head, copy);
            }
            List<JournalEvent> prefix = copy.subList(0, firstRelease);
            JournalHead prefixHead = new JournalHead(partition, prefix.size(),
                    prefix.isEmpty() ? ZERO_HASH : prefix.get(prefix.size() - 1).eventHash(), true, 0, null, null);
            return JournalProjectionService.verifyChain(partition, prefixHead, prefix)
                    && verifyWithRelease(partition, head, copy);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static int firstReleaseIndex(List<JournalEvent> events) {
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).type() == CommandType.HOLD_RELEASED) {
                return i;
            }
        }
        return -1;
    }

    /** 解除 event を含む chain の全体検証。 */
    private static boolean verifyWithRelease(JournalPartition partition, JournalHead head, List<JournalEvent> events) {
        if (!partition.equals(head.partition()) || head.revision() != events.size()) {
            return false;
        }
        String previous = ZERO_HASH;
        long revision = 1;
        boolean held = false;
        String activeHoldId = null;
        for (JournalEvent event : events) {
            if (event.revision() != revision || !previous.equals(event.previousHash()) || !isHash(event.eventHash())
                    || !isHash(event.payloadHash()) || event.commandId() == null || event.commandId().isBlank()
                    || event.type() == null || event.createdAt() == null
                    || !eventHash(partition, event, previous).equals(event.eventHash())) {
                return false;
            }
            switch (event.type()) {
                case HOLD_STARTED -> {
                    if (held || event.caseId() == null) {
                        return false;
                    }
                    held = true;
                    activeHoldId = event.caseId();
                }
                case HOLD_RELEASED -> {
                    if (!held || !event.caseId().equals(activeHoldId) || !isHash(event.requestHash())) {
                        return false;
                    }
                    held = false;
                    activeHoldId = null;
                }
                case DISPATCH_AUTHORIZED -> {
                    if (held || event.actionId() == null || event.actionId().isBlank()) {
                        return false;
                    }
                }
                case LEASE_CLAIMED, LEASE_TAKEN_OVER -> {
                    // lease の規則は adapter の読み取り時の検証が持つ（foundation の検証と同じく hash と連番だけを見る）
                }
            }
            previous = event.eventHash();
            revision++;
        }
        return head.eventHash().equals(previous) && head.held() == held;
    }

    /** foundation・本番 adapter と同じ event hash の正規形。 */
    private static String eventHash(JournalPartition partition, JournalEvent event, String previous) {
        StringBuilder bytes = new StringBuilder();
        field(bytes, EVENT_DOMAIN);
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
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
    }

    private static boolean isHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
