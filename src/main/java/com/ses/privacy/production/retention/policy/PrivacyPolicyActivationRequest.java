package com.ses.privacy.production.retention.policy;

/**
 * 登録済み policy（CANDIDATE）の有効化の要求（{@code POST /api/privacy/policies/{id}/activate}、NF07-REG）。
 *
 * <p>{@code expectedCurrentId} は有効化者が確認した、同じ key の現在の current の行 ID（無ければ null）。有効化の transaction の
 * 中で key の行を lock して実際の current と比べ（CAS）、違えば 409 にする（別の有効化と競合した・確認の後に current が
 * 替わった場合に、確認していない policy を SUPERSEDED にしない）。
 *
 * @param expectedCurrentId 有効化者が確認した current の行 ID（無ければ null）
 */
public record PrivacyPolicyActivationRequest(Long expectedCurrentId) {
}
