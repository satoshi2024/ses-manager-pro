package com.ses.service.certification;

/**
 * 資格番号暗号化用 AES-256 キー提供（DG-03-1）。
 */
public interface CertificationNumberKeyProvider {

    String getCurrentKeyVersion();

    byte[] getKey(String keyVersion);
}
