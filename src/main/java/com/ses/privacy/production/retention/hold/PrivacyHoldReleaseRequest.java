package com.ses.privacy.production.retention.hold;

/**
 * hold 解除の要求（{@code POST /api/privacy/holds/{holdId}/release}、NF07-REL）。
 *
 * <p>{@code binding} は発行者・承認者が署名した束縛（sf-w2-protocol.md §11.1）をそのまま提示したもの、{@code issuer}・
 * {@code approver} は署名付き authority claim。署名は system の外（D-03 で決める方式）で行い、ここには署名済みの値だけを
 * 渡す。理由本文は保存せず hash だけを束縛と照合する。subject 参照・partition は要求本文だけで受け取る（URL に入れない）。
 *
 * @param reasonCode 理由コード（英大文字・数字・下線）
 * @param reasonText 理由本文（保存しない。SHA-256 を束縛の {@code reasonHash} と照合する）
 * @param ticketRef  外部 ticket 参照
 * @param binding    署名された束縛
 * @param issuer     発行者の claim
 * @param approver   承認者の claim
 */
public record PrivacyHoldReleaseRequest(String reasonCode, String reasonText, String ticketRef, Binding binding,
                                        Claim issuer, Claim approver) {

    /**
     * 署名された束縛。
     *
     * @param subjectRef              Journal の partition の subject 参照（DATA_ELEMENT hold は {@code DATA_ELEMENT#<id>}）
     * @param expectedHoldRevision    解除する hold の開始の revision
     * @param expectedJournalRevision 解除の時点の journal head の revision
     * @param expectedJournalHash     解除の時点の journal head の event hash
     * @param requesterRef            解除の要求者（{@code sys_user:<id>}）
     */
    public record Binding(String tenantId, String subjectRef, String holdId, Long expectedHoldRevision,
                          String reasonHash, String commandId, Long expectedJournalRevision,
                          String expectedJournalHash, String requesterRef) {
    }

    /**
     * 署名付き authority claim（役割は issuer・approver の位置で決まり、署名の対象に入る）。
     *
     * @param issuedAt  発行日時（ISO-8601）
     * @param expiresAt 期限（ISO-8601）
     * @param signature Ed25519 署名（base64）
     */
    public record Claim(String authorityId, String keyId, String issuedAt, String expiresAt, String signature) {
    }
}
