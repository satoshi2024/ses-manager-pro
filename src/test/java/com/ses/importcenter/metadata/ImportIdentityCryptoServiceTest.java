package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.ImportEntity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImportIdentityCryptoServiceTest {
    private final ImportIdentityCryptoService crypto = new ImportIdentityCryptoService(new TestKeys());

    @Test
    void encryptsWithPinnedLookupVersionAndAuthenticatesNamespace() {
        var first = crypto.encrypt("tenant-a", 42, ImportEntity.CUSTOMER, "lookup-v1", "Acme-01");
        var second = crypto.encrypt("tenant-a", 42, ImportEntity.CUSTOMER, "lookup-v1", "Acme-01");

        assertEquals("lookup-v1", first.lookupKeyVersion());
        assertArrayEquals(first.lookupDigest(), second.lookupDigest());
        assertFalse(java.util.Arrays.equals(first.nonce(), second.nonce()));
        assertTrue(crypto.matches(first, "tenant-a", 42, ImportEntity.CUSTOMER, "Acme-01"));
        assertFalse(crypto.matches(first, "tenant-a", 42, ImportEntity.CUSTOMER, "acme-01"));
        assertThrows(IllegalStateException.class,
                () -> crypto.decrypt("tenant-b", 42, ImportEntity.CUSTOMER, first.lookupKeyVersion(),
                        first.lookupDigest(), first.cipherFormatVersion(), first.encryptionKeyVersion(),
                        first.nonce(), first.ciphertext()));
    }

    @Test
    void rejectsUnknownPinnedKeyVersion() {
        assertThrows(IllegalStateException.class,
                () -> crypto.encrypt("tenant-a", 42, ImportEntity.CUSTOMER, "retired-key", "key-1"));
    }

    @Test
    void rejectsExternalKeysWithSurroundingWhitespace() {
        assertThrows(IllegalArgumentException.class,
                () -> crypto.encrypt("tenant-a", 42, ImportEntity.CUSTOMER, "lookup-v1", " Acme-01 "));
    }

    private static final class TestKeys implements ImportIdentityKeyProvider {
        private final byte[] lookup = new byte[32];
        private final byte[] encryption = new byte[32];

        private TestKeys() {
            java.util.Arrays.fill(lookup, (byte) 0x31);
            java.util.Arrays.fill(encryption, (byte) 0x52);
        }

        @Override public String currentLookupKeyVersion() { return "lookup-v2"; }
        @Override public byte[] lookupKey(String version) {
            if (!Map.of("lookup-v1", true, "lookup-v2", true).containsKey(version)) {
                throw new IllegalStateException("test lookup key missing");
            }
            return lookup.clone();
        }
        @Override public String currentEncryptionKeyVersion() { return "encryption-v1"; }
        @Override public byte[] encryptionKey(String version) {
            if (!"encryption-v1".equals(version)) throw new IllegalStateException("test encryption key missing");
            return encryption.clone();
        }
    }
}
