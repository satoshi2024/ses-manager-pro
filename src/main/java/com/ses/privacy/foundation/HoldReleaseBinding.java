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
 * commandId || expectedJournalHead} に、解除の要求者（{@code requesterRef}）と hold の開始者（{@code holdStarterRef}）を
 * 加えたもの。expected journal head は revision と event hash の両方で表す。発行者と承認者はこの束縛の digest に署名する
 * （{@link HoldReleaseAuthorityClaim}）。
 * <ul>
 *   <li>{@code subjectRef} は Journal の partition の subject 参照（DATA_ELEMENT hold では {@code DATA_ELEMENT#<id>}）。</li>
 *   <li>{@code reasonHash} は理由コード・ticket・理由本文の正規形の SHA-256（{@link #reasonHash}。hold 開始の request hash と
 *       同じく長さ付き field 列）。監査に残る理由コード・ticket も署名の範囲に入る。</li>
 *   <li>{@code requesterRef} は {@link HoldReleaseTrustAnchor#PRINCIPAL} の形（{@code sys_user:<id>}）だけ。
 *       {@code holdStarterRef} は同じ形か、開始者が不明（reconciler が Journal から復元した行）を示す
 *       {@link #UNKNOWN_STARTER}。どちらも正規化済みの値として比較する。</li>
 * </ul>
 * 値は全て opaque な識別子と hash で、氏名・理由本文を含まない。正規形の test vector は deploy-runbook の NF07-REL の節と
 * {@code HoldReleaseProofVerifierTest} にある。
 */
public record HoldReleaseBinding(String tenantId, String subjectRef, String holdId, long expectedHoldRevision,
                                 String reasonHash, String commandId, long expectedJournalRevision,
                                 String expectedJournalHash, String requesterRef, String holdStarterRef) {

    /** 束縛する action。 */
    public static final String ACTION = "HOLD_RELEASE";
    /** 開始者が不明（{@code t_legal_hold.requested_by = 0}。reconciler が Journal から復元し、まだ開始者の値が戻っていない行）。 */
    public static final String UNKNOWN_STARTER = "sys_user:0";
    static final String DOMAIN = "NF07-HOLD-RELEASE-BINDING-V1";
    static final String REASON_DOMAIN = "NF07-HOLD-RELEASE-REASON-V1";
    private static final int MAX_IDENTIFIER_LENGTH = 128;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    public HoldReleaseBinding {
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(subjectRef, "subjectRef");
        requireIdentifier(holdId, "holdId");
        requireIdentifier(commandId, "commandId");
        if (requesterRef == null || !HoldReleaseTrustAnchor.PRINCIPAL.matcher(requesterRef).matches()) {
            throw new IllegalArgumentException("解除の束縛の requesterRef が不正です");
        }
        if (holdStarterRef == null || !(UNKNOWN_STARTER.equals(holdStarterRef)
                || HoldReleaseTrustAnchor.PRINCIPAL.matcher(holdStarterRef).matches())) {
            throw new IllegalArgumentException("解除の束縛の holdStarterRef が不正です");
        }
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

    /** hold の開始者が分かっているか（{@link #UNKNOWN_STARTER} でない）。 */
    public boolean starterKnown() {
        return !UNKNOWN_STARTER.equals(holdStarterRef);
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
        field(canonical, holdStarterRef);
        return canonical.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** 正規形の SHA-256（小文字 hex）。HOLD_RELEASED command の {@code request_hash} になる。 */
    public String digest() {
        return sha256(canonicalBytes());
    }

    /**
     * 理由の hash: domain・理由コード・ticket・理由本文の長さ付き field 列（UTF-8 の byte 長と {@code :} と値）の SHA-256。
     *
     * @throws IllegalArgumentException いずれかが null
     */
    public static String reasonHash(String reasonCode, String ticketRef, String reasonText) {
        if (reasonCode == null || ticketRef == null || reasonText == null) {
            throw new IllegalArgumentException("理由の hash の項目が不足しています");
        }
        StringBuilder canonical = new StringBuilder();
        field(canonical, REASON_DOMAIN);
        field(canonical, reasonCode);
        field(canonical, ticketRef);
        field(canonical, reasonText);
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    static void field(StringBuilder target, String value) {
        target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank() || value.length() > MAX_IDENTIFIER_LENGTH) {
            throw new IllegalArgumentException("解除の束縛の " + name + " が不正です");
        }
    }
}
