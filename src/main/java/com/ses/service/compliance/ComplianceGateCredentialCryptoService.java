package com.ses.service.compliance;

/**
 * G2 compliance gate credential crypto service (§6.5, §6.3).
 */
public interface ComplianceGateCredentialCryptoService {
    /**
     * Encrypt credential into CGC1 envelope format.
     */
    String encrypt(String tenantId, Long mappingId, String mappingVersion, String operationId, String credentialRaw);

    /**
     * Decrypt CGC1 envelope string into plain credential string.
     * @throws com.ses.common.exception.BusinessException 409 compliance.gate.credentialUnavailable on decryption failure.
     */
    String decrypt(String tenantId, Long mappingId, String mappingVersion, String operationId, String envelopeString);

    /**
     * Compute canonical identity hash (§6.3) using NFC normalization and canonical JSON object.
     */
    String computeIdentityHash(String reviewerTypeCode, String credentialRaw, String organization, String reviewerName);
}
