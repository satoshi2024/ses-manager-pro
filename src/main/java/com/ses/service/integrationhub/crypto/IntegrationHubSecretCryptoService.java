package com.ses.service.integrationhub.crypto;

/** NF-05 AES-256-GCM envelope abstraction。 */
public interface IntegrationHubSecretCryptoService {
    String encrypt(String clientId, int credentialVersion, String purpose, String plaintext);

    String decrypt(String clientId, int credentialVersion, String purpose, String envelope);

    String sha256Hex(String plaintext);
}
