package com.ses.service.compliance;

/**
 * G2 compliance gate credential key provider (§6.5).
 * Resolves AES-256 key bytes by key version.
 */
public interface ComplianceGateCredentialKeyProvider {
    /**
     * Get the active current key version.
     */
    String getCurrentKeyVersion();

    /**
     * Get the 32-byte key for the specified key version.
     * @throws RuntimeException if key version is unknown or invalid.
     */
    byte[] getKey(String keyVersion);
}
