package com.ses.service.integrationhub.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-05 F1: AES-256-GCM envelope、AAD、rotation overlap、fail closed。 */
class IntegrationHubSecretCryptoServiceTest {
    @Test
    void IHG1はAADへclientとcredentialVersionとpurposeをbindする() {
        TestKeyring keyring = new TestKeyring();
        AesGcmIntegrationHubSecretCryptoService crypto = new AesGcmIntegrationHubSecretCryptoService(keyring);

        String envelope = crypto.encrypt("client-a", 1, "credential", "one-time-secret");
        assertTrue(envelope.startsWith("IHG1:v1:"));
        assertFalse(envelope.contains("one-time-secret"));
        assertEquals("one-time-secret", crypto.decrypt("client-a", 1, "credential", envelope));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt("client-b", 1, "credential", envelope));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt("client-a", 2, "credential", envelope));
        assertThrows(IllegalStateException.class, () -> crypto.decrypt("client-a", 1, "signing", envelope));
    }

    @Test
    void 鍵rotation後も旧keyはoverlap期間の復号に使え新規暗号化は現行keyを使う() {
        TestKeyring keyring = new TestKeyring();
        AesGcmIntegrationHubSecretCryptoService crypto = new AesGcmIntegrationHubSecretCryptoService(keyring);

        String oldEnvelope = crypto.encrypt("client-a", 1, "credential", "old-secret");
        keyring.current = "v2";
        String newEnvelope = crypto.encrypt("client-a", 2, "credential", "new-secret");

        assertEquals("old-secret", crypto.decrypt("client-a", 1, "credential", oldEnvelope));
        assertEquals("new-secret", crypto.decrypt("client-a", 2, "credential", newEnvelope));
        assertTrue(newEnvelope.startsWith("IHG1:v2:"));
    }

    @Test
    void envelope改竄と未知keyはfailClosedする() {
        TestKeyring keyring = new TestKeyring();
        AesGcmIntegrationHubSecretCryptoService crypto = new AesGcmIntegrationHubSecretCryptoService(keyring);
        String envelope = crypto.encrypt("client-a", 1, "credential", "secret");
        String[] parts = envelope.split(":", 4);
        String tamperedCipher = (parts[3].charAt(0) == 'A' ? "B" : "A") + parts[3].substring(1);
        assertThrows(IllegalStateException.class,
                () -> crypto.decrypt("client-a", 1, "credential", parts[0] + ":" + parts[1] + ":" + parts[2] + ":" + tamperedCipher));
        String unknownKey = parts[0] + ":v-unknown:" + parts[2] + ":" + parts[3];
        assertThrows(IllegalStateException.class, () -> crypto.decrypt("client-a", 1, "credential", unknownKey));
    }

    private static final class TestKeyring implements IntegrationHubKeyring {
        private final Map<String, byte[]> keys = new HashMap<>(Map.of(
                "v1", "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8),
                "v2", "abcdefghijklmnopqrstuvwxyz012345".getBytes(StandardCharsets.UTF_8)));
        private String current = "v1";

        @Override
        public String currentKeyVersion() {
            return current;
        }

        @Override
        public byte[] key(String keyVersion) {
            byte[] key = keys.get(keyVersion);
            if (key == null) {
                throw new IllegalStateException("unknown key");
            }
            return key.clone();
        }
    }
}
