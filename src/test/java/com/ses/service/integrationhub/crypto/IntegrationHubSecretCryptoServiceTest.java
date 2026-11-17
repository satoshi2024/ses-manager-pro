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

    /** N5-P3-2: credential hashはpepperを鍵にしたHMACで、client・version・pepperへ束縛し、ソルトの無いSHA-256と一致しない。 */
    @Test
    void credentialHashはpepper付きHMACでclientとversionへ束縛しpepperを使用後に消去する() throws Exception {
        TestKeyring keyring = new TestKeyring();
        keyring.pepper = "synthetic-nf05-test-only-pepper!".getBytes(StandardCharsets.US_ASCII);
        AesGcmIntegrationHubSecretCryptoService crypto = new AesGcmIntegrationHubSecretCryptoService(keyring);
        String secret = "synthetic_rotation_secret_v1_base64url_43ch";

        String hash = crypto.credentialHash("client-a", 1, secret);

        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(independentHmac(keyring.pepper, "client-a", 1, secret), hash, "長さ前置の正規形で計算する");
        assertFalse(hash.equals(com.ses.service.integrationhub.IntegrationHubDigest.sha256Hex(secret)),
                "ソルトの無いSHA-256を保存しない");
        assertFalse(hash.equals(crypto.credentialHash("client-b", 1, secret)));
        assertFalse(hash.equals(crypto.credentialHash("client-a", 2, secret)));
        assertTrue(java.util.Arrays.equals(new byte[32], keyring.lastIssuedPepper), "pepperの複製は使用後に0で消す");
        keyring.pepper = "another-nf05-test-only-pepper!!!".getBytes(StandardCharsets.US_ASCII);
        assertFalse(hash.equals(crypto.credentialHash("client-a", 1, secret)), "pepperが違えば同じsecretでも別のhash");

        AesGcmIntegrationHubSecretCryptoService unconfigured = new AesGcmIntegrationHubSecretCryptoService(new TestKeyring());
        assertThrows(IllegalStateException.class, () -> unconfigured.credentialHash("client-a", 1, secret),
                "pepper未設定ではcredentialを発行できない");
        assertThrows(IllegalArgumentException.class, () -> crypto.credentialHash("client-a", 1, " "));
    }

    @Test
    void 環境のpepperは32byteのbase64urlだけを受け付け値を例外へ出さない() {
        org.springframework.mock.env.MockEnvironment environment = new org.springframework.mock.env.MockEnvironment();
        EnvironmentIntegrationHubKeyring keyring = new EnvironmentIntegrationHubKeyring(environment);
        assertThrows(IllegalStateException.class, keyring::credentialHashPepper);
        environment.setProperty(EnvironmentIntegrationHubKeyring.CREDENTIAL_HASH_PEPPER_PROPERTY,
                "c3ludGhldGljLW5mMDUtdGVzdC1vbmx5LXBlcHBlciE");
        assertEquals("synthetic-nf05-test-only-pepper!",
                new String(keyring.credentialHashPepper(), StandardCharsets.US_ASCII));
        for (String invalid : java.util.List.of("c3ludGhldGljLW5mMDUtdGVzdC1vbmx5LXBlcHBlciE=", "c2hvcnQ",
                "c3ludGhl dGljLW5mMDUtdGVzdC1vbmx5LXBlcHBlciE", "!!!!")) {
            environment.setProperty(EnvironmentIntegrationHubKeyring.CREDENTIAL_HASH_PEPPER_PROPERTY, invalid);
            IllegalStateException failure = assertThrows(IllegalStateException.class, keyring::credentialHashPepper);
            assertFalse(failure.getMessage().contains(invalid), "例外へpepperの値を出さない");
        }
    }

    private static String independentHmac(byte[] pepper, String clientId, int version, String secret) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(pepper, "HmacSHA256"));
        for (String part : new String[]{"SES-NF05-CREDENTIAL-HASH-V1", clientId, Integer.toString(version), secret}) {
            byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
            mac.update(new byte[]{(byte) (bytes.length >>> 24), (byte) (bytes.length >>> 16),
                    (byte) (bytes.length >>> 8), (byte) bytes.length});
            mac.update(bytes);
        }
        return java.util.HexFormat.of().formatHex(mac.doFinal());
    }

    private static final class TestKeyring implements IntegrationHubKeyring {
        private final Map<String, byte[]> keys = new HashMap<>(Map.of(
                "v1", "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8),
                "v2", "abcdefghijklmnopqrstuvwxyz012345".getBytes(StandardCharsets.UTF_8)));
        private String current = "v1";
        /** nullなら既定（未設定でfail closed）。 */
        private byte[] pepper;
        private byte[] lastIssuedPepper;

        @Override
        public byte[] credentialHashPepper() {
            if (pepper == null) {
                return IntegrationHubKeyring.super.credentialHashPepper();
            }
            lastIssuedPepper = pepper.clone();
            return lastIssuedPepper;
        }

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
