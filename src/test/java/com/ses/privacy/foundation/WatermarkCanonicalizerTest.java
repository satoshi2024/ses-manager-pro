package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class WatermarkCanonicalizerTest {
    private static final String HASH = "a".repeat(64);

    @Test
    void canonicalBytesはdomain固定順序UTF8長prefixとmicrosecond精度を使う() {
        var checkpoint = checkpoint("subject-雪", Instant.parse("2026-10-02T00:00:00.123456789Z"));
        byte[] first = WatermarkCanonicalizer.canonicalBytes(checkpoint);
        byte[] second = WatermarkCanonicalizer.canonicalBytes(checkpoint("subject-雪",
                Instant.parse("2026-10-02T00:00:00.123456001Z")));
        assertArrayEquals(first, second);
        assertEquals(1, countField(first, WatermarkCheckpoint.DOMAIN));
        assertTrue(containsField(first, "subject-雪"));
        assertFalse(java.util.Arrays.equals(first, WatermarkCanonicalizer.canonicalBytes(
                checkpoint("subject-雪x", Instant.parse("2026-10-02T00:00:00.123456Z")))));
    }

    @Test
    void typedRecordは不正revisionhashと空identityを拒否する() {
        assertThrows(IllegalArgumentException.class, () -> new WatermarkCheckpoint("j", "t", "s", 0,
                1, HASH, HASH, Instant.EPOCH, "k"));
        assertThrows(IllegalArgumentException.class, () -> new WatermarkCheckpoint("j", "t", "s", 1,
                1, "A".repeat(64), HASH, Instant.EPOCH, "k"));
        assertThrows(IllegalArgumentException.class, () -> new WatermarkCheckpoint("j", "t", " ", 1,
                1, HASH, HASH, Instant.EPOCH, "k"));
    }

    @Test
    void testOnlyEd25519fixtureは改変payloadを検知しprofile変更後は署名不可() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        SyntheticAttestorKeyFixture fixture = SyntheticAttestorKeyFixture.generate(environment);
        byte[] payload = WatermarkCanonicalizer.canonicalBytes(checkpoint("subject-1", Instant.EPOCH));
        byte[] signature = fixture.sign(environment, payload);
        assertEquals(SyntheticAttestorKeyFixture.AUDIENCE, fixture.audience());
        assertTrue(verify(fixture, environment, payload, signature));
        byte[] tampered = payload.clone();
        tampered[tampered.length - 1] ^= 1;
        assertFalse(verify(fixture, environment, tampered, signature));
        environment.setActiveProfiles("prod", "test");
        assertThrows(SecurityException.class, () -> fixture.sign(environment, payload));
        assertThrows(SecurityException.class, () -> fixture.publicKey(environment));
    }

    @Test
    void prod混在defaultProdやtestなしではcapabilityと鍵生成を拒否する() {
        MockEnvironment prod = new MockEnvironment(); prod.setActiveProfiles("prod");
        MockEnvironment mixed = new MockEnvironment(); mixed.setActiveProfiles("prod", "test");
        MockEnvironment dev = new MockEnvironment(); dev.setActiveProfiles("dev");
        MockEnvironment prodDefault = new MockEnvironment();
        prodDefault.setActiveProfiles("test"); prodDefault.setDefaultProfiles("prod");
        for (MockEnvironment environment : java.util.List.of(prod, mixed, dev, prodDefault)) {
            assertThrows(SecurityException.class, () -> W2bTestEnvironment.requireTest(environment));
            assertThrows(SecurityException.class, () -> SyntheticAttestorKeyFixture.generate(environment));
            assertThrows(SecurityException.class, () -> W2bTestConnectionCapability.fromOwnedContainer(null,
                    environment, W2bTestConnectionCapability.Store.WITNESS));
        }
    }

    private static WatermarkCheckpoint checkpoint(String subject, Instant instant) {
        return new WatermarkCheckpoint("journal-1", "tenant-1", subject, 1, 12, HASH, "b".repeat(64), instant, "key-1");
    }

    private static boolean verify(SyntheticAttestorKeyFixture fixture, MockEnvironment environment,
                                  byte[] payload, byte[] signatureBytes) {
        try {
            var verifier = java.security.Signature.getInstance("Ed25519");
            verifier.initVerify(fixture.publicKey(environment));
            verifier.update(payload);
            return verifier.verify(signatureBytes);
        } catch (java.security.GeneralSecurityException e) { throw new AssertionError(e); }
    }

    private static boolean containsField(byte[] bytes, String field) {
        return countField(bytes, field) == 1;
    }

    private static int countField(byte[] bytes, String field) {
        ByteBuffer input = ByteBuffer.wrap(bytes);
        int count = 0;
        while (input.remaining() >= Integer.BYTES) {
            int length = input.getInt();
            if (length < 0 || length > input.remaining()) return count;
            byte[] value = new byte[length];
            input.get(value);
            if (new String(value, StandardCharsets.UTF_8).equals(field)) count++;
        }
        return count;
    }
}
