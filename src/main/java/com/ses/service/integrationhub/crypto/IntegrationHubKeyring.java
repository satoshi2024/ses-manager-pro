package com.ses.service.integrationhub.crypto;

/** NF-05環境注入keyring。DBやログから鍵を取得しない。 */
public interface IntegrationHubKeyring {
    String currentKeyVersion();

    byte[] key(String keyVersion);

    /**
     * credential secretの保存用hash（HMAC-SHA256）に使うpepper（N5-P3-2）。暗号鍵とは別の32 byte。
     * 未設定の実装はfail closedする（credentialを発行できない）。返す配列は呼出元が使用後に消去する。
     */
    default byte[] credentialHashPepper() {
        throw new IllegalStateException("integration hub credential hash pepper is not configured");
    }
}
