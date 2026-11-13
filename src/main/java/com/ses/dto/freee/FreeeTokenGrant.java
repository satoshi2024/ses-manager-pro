package com.ses.dto.freee;

/**
 * freee OAuth token endpointで認可codeを交換した結果（保存前）。
 *
 * <p>給与（t_freee_connection）と会計（m_integration_connection）の両接続が同じOAuth clientを使うため、
 * code交換と応答の必須項目検証だけを共通化し、保存先・事業所検証は呼出側が行う。</p>
 *
 * <p>tokenを含むため toString でマスクする（ログ・例外messageへの混入防止）。</p>
 */
public record FreeeTokenGrant(String accessToken, String refreshToken, long expiresIn, long companyId) {

    @Override
    public String toString() {
        return "FreeeTokenGrant[companyId=" + companyId + ", expiresIn=" + expiresIn + ", tokens=***]";
    }
}
