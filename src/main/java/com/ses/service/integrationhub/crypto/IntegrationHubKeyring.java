package com.ses.service.integrationhub.crypto;

/** NF-05環境注入keyring。DBやログから鍵を取得しない。 */
public interface IntegrationHubKeyring {
    String currentKeyVersion();

    byte[] key(String keyVersion);
}
