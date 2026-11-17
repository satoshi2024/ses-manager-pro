package com.ses.service.integrationhub.crypto;

/** NF-05 AES-256-GCM envelope abstraction。 */
public interface IntegrationHubSecretCryptoService {
    String encrypt(String clientId, int credentialVersion, String purpose, String plaintext);

    String decrypt(String clientId, int credentialVersion, String purpose, String envelope);

    /**
     * credential secretの保存用hash（N5-P3-2）。環境注入のpepperを鍵にしたHMAC-SHA256（小文字hex 64桁）で、
     * clientIdとcredential versionへ束縛する。ソルトの無いSHA-256は辞書照合に弱いため使わない。認証はenvelopeの復号で行い、
     * このhashは照合に使わない（DB行の改ざん・取り違えの調査用）。
     */
    String credentialHash(String clientId, int credentialVersion, String plaintext);
}
