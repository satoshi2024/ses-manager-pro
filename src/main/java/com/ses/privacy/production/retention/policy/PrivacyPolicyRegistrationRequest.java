package com.ses.privacy.production.retention.policy;

/**
 * 署名済み policy envelope の登録の要求（{@code POST /api/privacy/policies}、NF07-REG）。
 *
 * <p>issuer が D-03 の鍵で署名した canonical bytes（{@code ApprovalEnvelopeCanonicalizer} の v1 形式）と署名をそのまま渡す。
 * 登録する行の key（policy ID・revision・module・data element・action・provider）は検証した envelope から取り、要求の別の値は
 * 受け付けない（署名されていない値で行を作らない）。署名は system の外（D-03 で決める方式）で行い、system は鍵を持たない。
 *
 * @param envelope  canonical bytes の base64（64KB 以下）
 * @param signature canonical bytes への Ed25519 署名の base64
 */
public record PrivacyPolicyRegistrationRequest(String envelope, String signature) {
}
