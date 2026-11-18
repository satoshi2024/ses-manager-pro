package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.provider.PrivacySubjectRef;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * hold と Journal partition・command の対応（NF07-A1H）。
 *
 * <ul>
 *   <li>SUBJECT hold は本人の partition（{@code (tenant, <種別>:<ID>)}）に HOLD_STARTED を入れる（SF-W2 の
 *       {@code appendHoldStart} と同じ。本人の partition が held になり、以降の新規 dispatch 認可を Journal 自身が拒否する）。</li>
 *   <li>DATA_ELEMENT hold は {@code DATA_ELEMENT#<data element ID>} の partition に入れる（{@code #} は subject 参照に
 *       現れないため本人の partition と重ならない）。</li>
 *   <li>hold ID は {@code ph-} + 32桁 hex、command ID は同じ乱数の {@code hs-} + 32桁 hex（どちらも 64 文字以下。
 *       command の {@code case_id} に hold ID を入れ、Journal の event から hold ID を復元できるようにする）。</li>
 * </ul>
 * どの値にも氏名等は入らない（subject 参照は opaque な種別付き ID）。hold ID は監査に出してよいが、command ID・partition は
 * 監査・log に出さない（partition は subject 参照を含むため）。
 */
final class PrivacyHoldPartitions {

    static final String DATA_ELEMENT_PREFIX = "DATA_ELEMENT#";
    static final int MAX_PARTITION_LENGTH = 128;
    static final Pattern HOLD_ID = Pattern.compile("ph-[0-9a-f]{32}");
    static final Pattern COMMAND_ID = Pattern.compile("hs-[0-9a-f]{32}");
    /**
     * 解除の command ID（NF07-REL）。発行者・承認者が署名する束縛に入るため、署名の前に要求者側で決める（{@code hr-} +
     * 32桁 hex）。hold ID とは別の乱数で、command ID から hold ID は導かない（解除 event の {@code case_id} が hold ID）。
     */
    static final Pattern RELEASE_COMMAND_ID = Pattern.compile("hr-[0-9a-f]{32}");
    static final Pattern DATA_ELEMENT_ID = Pattern.compile("[A-Z][A-Z0-9_]*(:[A-Z][A-Z0-9_]*)?");

    private static final SecureRandom RANDOM = new SecureRandom();

    private PrivacyHoldPartitions() {
    }

    /** 新しい hold ID と command ID の組。 */
    static Ids newIds() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        return new Ids("ph-" + hex, "hs-" + hex);
    }

    /** command ID に対応する hold ID（形式が違えば空）。 */
    static Optional<String> holdIdOfCommand(String commandId) {
        if (commandId == null || !COMMAND_ID.matcher(commandId).matches()) {
            return Optional.empty();
        }
        return Optional.of("ph-" + commandId.substring(3));
    }

    /** 解除の command ID か（NF07-REL）。 */
    static boolean isReleaseCommand(String commandId) {
        return commandId != null && RELEASE_COMMAND_ID.matcher(commandId).matches();
    }

    /** SUBJECT hold の partition（subject 参照が不正なら null）。 */
    static String subjectPartition(String subjectRef) {
        return PrivacySubjectRef.parse(subjectRef).map(PrivacySubjectRef::ref).orElse(null);
    }

    /** DATA_ELEMENT hold の partition（ID が不正・長すぎれば null）。 */
    static String dataElementPartition(String dataElementId) {
        if (!isValidDataElementId(dataElementId)) {
            return null;
        }
        return DATA_ELEMENT_PREFIX + dataElementId;
    }

    static boolean isValidDataElementId(String dataElementId) {
        return dataElementId != null && DATA_ELEMENT_ID.matcher(dataElementId).matches()
                && DATA_ELEMENT_PREFIX.length() + dataElementId.length() <= MAX_PARTITION_LENGTH;
    }

    /** partition から hold の対象を復元する（reconciler。どちらでもなければ空）。 */
    static Optional<Target> targetOf(String partitionRef) {
        if (partitionRef == null) {
            return Optional.empty();
        }
        if (partitionRef.startsWith(DATA_ELEMENT_PREFIX)) {
            String id = partitionRef.substring(DATA_ELEMENT_PREFIX.length());
            return isValidDataElementId(id) ? Optional.of(new Target(PrivacyHoldTargetType.DATA_ELEMENT, id))
                    : Optional.empty();
        }
        return PrivacySubjectRef.parse(partitionRef)
                .filter(subject -> subject.ref().equals(partitionRef))
                .map(subject -> new Target(PrivacyHoldTargetType.SUBJECT, subject.ref()));
    }

    /** hold ID と command ID。 */
    record Ids(String holdId, String commandId) {
    }

    /** hold の対象（SUBJECT なら subject 参照、DATA_ELEMENT なら data element ID）。 */
    record Target(PrivacyHoldTargetType type, String value) {
    }
}
