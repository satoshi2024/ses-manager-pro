package com.ses.privacy.foundation;

import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * hold 解除 proof の正規束縛（NF07-REL、sf-w2-protocol.md §11.1）。
 *
 * <p>§11.1 の {@code tenant || subjectRef || holdId || expectedHoldRevision || action=HOLD_RELEASE || reasonHash ||
 * commandId || expectedJournalHead} に、解除の要求者（{@code requesterRef}）を加えたもの。expected journal head は
 * revision と event hash の両方で表す。発行者と承認者はこの束縛の digest に署名する（{@link HoldReleaseAuthorityClaim}）。
 * {@code subjectRef} は Journal の partition の subject 参照（DATA_ELEMENT hold では {@code DATA_ELEMENT#<id>}）。
 * 値は全て opaque な識別子と hash で、氏名・理由本文を含まない。
 */
public record HoldReleaseBinding(String tenantId, String subjectRef, String holdId, long expectedHoldRevision,
                                 String reasonHash, String commandId, long expectedJournalRevision,
                                 String expectedJournalHash, String requesterRef) {

    /** 束縛する action。 */
    public static final String ACTION = "HOLD_RELEASE";
    static final String DOMAIN = "NF07-HOLD-RELEASE-BINDING-V1";
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public HoldReleaseBinding {
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(subjectRef, "subjectRef");
        requireIdentifier(holdId, "holdId");
        requireIdentifier(commandId, "commandId");
        requireIdentifier(requesterRef, "requesterRef");
        if (reasonHash == null || !HASH.matcher(reasonHash).matches()
                || expectedJournalHash == null || !HASH.matcher(expectedJournalHash).matches()) {
            throw new IllegalArgumentException("解除の束縛の hash が不正です");
        }
        if (expectedHoldRevision < 1 || expectedJournalRevision < expectedHoldRevision) {
            throw new IllegalArgumentException("解除の束縛の revision が不正です");
        }
    }

    /** 束縛する Journal の partition。 */
    public JournalPartition partition() {
        return new JournalPartition(tenantId, subjectRef);
    }

    /** 正規形（domain 付きの長さ付き field 列。null は持たない）。 */
    public byte[] canonicalBytes() {
        StringBuilder canonical = new StringBuilder();
        field(canonical, DOMAIN);
        field(canonical, tenantId);
        field(canonical, subjectRef);
        field(canonical, holdId);
        field(canonical, Long.toString(expectedHoldRevision));
        field(canonical, ACTION);
        field(canonical, reasonHash);
        field(canonical, commandId);
        field(canonical, Long.toString(expectedJournalRevision));
        field(canonical, expectedJournalHash);
        field(canonical, requesterRef);
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 正規形の SHA-256（小文字 hex）。HOLD_RELEASED command の {@code request_hash} になる。 */
    public String digest() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    static void field(StringBuilder target, String value) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank() || value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException("解除の束縛の " + name + " が不正です");
        }
    }
}
