package com.ses.importcenter.metadata;

/** source identity用途別の鍵versionと鍵bytesを解決する境界。鍵欠落時にfallbackしない。 */
public interface ImportIdentityKeyProvider {
    String currentLookupKeyVersion();

    byte[] lookupKey(String keyVersion);

    String currentEncryptionKeyVersion();

    byte[] encryptionKey(String keyVersion);
}
