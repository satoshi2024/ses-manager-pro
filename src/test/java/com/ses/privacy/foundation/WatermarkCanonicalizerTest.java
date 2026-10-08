package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
        assertEquals(checkpoint, WatermarkCanonicalizer.decode(first));
        assertThrows(IllegalArgumentException.class,
                () -> WatermarkCanonicalizer.decode(replaceField(first, 0, "OTHER-DOMAIN")));
        assertThrows(IllegalArgumentException.class,
                () -> WatermarkCanonicalizer.decode(replaceField(first, 1, "v2")));
        assertThrows(IllegalArgumentException.class,
                () -> WatermarkCanonicalizer.decode(replaceFieldBytes(first, 4, new byte[]{(byte) 0xC0, (byte) 0xAF})));
    }

    @Test
    void canonicalBytesはrecordの全semanticfield差分を保持しlengthprefixで境界を分ける() {
        WatermarkCheckpoint base = checkpoint("s", Instant.parse("2026-10-02T00:00:00Z"));
        byte[] canonical = WatermarkCanonicalizer.canonicalBytes(base);
        for (WatermarkCheckpoint changed : java.util.List.of(
                new WatermarkCheckpoint("journal-2", base.tenantId(), base.subjectRef(), base.epoch(), base.eventRevision(), base.eventHash(), base.previousCheckpointHash(), base.issuedAt(), base.keyId()),
                new WatermarkCheckpoint(base.journalId(), "tenant-2", base.subjectRef(), base.epoch(), base.eventRevision(), base.eventHash(), base.previousCheckpointHash(), base.issuedAt(), base.keyId()),
                new WatermarkCheckpoint(base.journalId(), base.tenantId(), "subject-2", base.epoch(), base.eventRevision(), base.eventHash(), base.previousCheckpointHash(), base.issuedAt(), base.keyId()),
                new WatermarkCheckpoint(base.journalId(), base.tenantId(), base.subjectRef(), 2, base.eventRevision(), base.eventHash(), base.previousCheckpointHash(), base.issuedAt(), base.keyId()),
                new WatermarkCheckpoint(base.journalId(), base.tenantId(), base.subjectRef(), base.epoch(), 13, base.eventHash(), base.previousCheckpointHash(), base.issuedAt(), base.keyId()),
                new WatermarkCheckpoint(base.journalId(), base.tenantId(), base.subjectRef(), base.epoch(), base.eventRevision(), "c".repeat(64), base.previousCheckpointHash(), base.issuedAt(), base.keyId()),
                new WatermarkCheckpoint(base.journalId(), base.tenantId(), base.subjectRef(), base.epoch(), base.eventRevision(), base.eventHash(), "d".repeat(64), base.issuedAt(), base.keyId()),
                new WatermarkCheckpoint(base.journalId(), base.tenantId(), base.subjectRef(), base.epoch(), base.eventRevision(), base.eventHash(), base.previousCheckpointHash(), base.issuedAt().plusNanos(1_000), base.keyId()),
                new WatermarkCheckpoint(base.journalId(), base.tenantId(), base.subjectRef(), base.epoch(), base.eventRevision(), base.eventHash(), base.previousCheckpointHash(), base.issuedAt(), "key-2"))) {
            assertFalse(java.util.Arrays.equals(canonical, WatermarkCanonicalizer.canonicalBytes(changed)));
        }
        WatermarkCheckpoint left = new WatermarkCheckpoint("ab", "c", "s", 1, 1, HASH, HASH, Instant.EPOCH, "k");
        WatermarkCheckpoint right = new WatermarkCheckpoint("a", "bc", "s", 1, 1, HASH, HASH, Instant.EPOCH, "k");
        assertFalse(java.util.Arrays.equals(WatermarkCanonicalizer.canonicalBytes(left), WatermarkCanonicalizer.canonicalBytes(right)));
        assertThrows(IllegalArgumentException.class, () -> checkpoint(String.valueOf((char) 0xD800), Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> checkpoint("x".repeat(129), Instant.EPOCH));
        assertThrows(IllegalArgumentException.class, () -> checkpoint("雪".repeat(43), Instant.EPOCH));
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
    @Test
    void 取得済みDataSourceもprod切替後の実接続を拒否する() throws Exception {
        try (AnnotationConfigApplicationContext context = context(new String[]{"test"}, new String[0])) {
            java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
            javax.sql.DataSource delegate = (javax.sql.DataSource) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class[]{javax.sql.DataSource.class},
                    (proxy, method, args) -> {
                        if (method.getName().startsWith("getConnection")) {
                            attempts.incrementAndGet();
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            javax.sql.DataSource alreadyIssued = new ProfileGuardedDataSource(delegate, context);
            assertNull(alreadyIssued.getConnection());
            assertSame(alreadyIssued, alreadyIssued.unwrap(javax.sql.DataSource.class));
            context.getEnvironment().setActiveProfiles("prod", "test");
            assertThrows(SecurityException.class, alreadyIssued::getConnection);
            assertThrows(SecurityException.class, () -> alreadyIssued.getConnection("other", "secret"));
            assertEquals(1, attempts.get());
        }
    }

    @Test
    void prod混在defaultProdやtestなしではcapabilityと鍵生成を拒否する() {
        for (AnnotationConfigApplicationContext badContext : java.util.List.of(
                newContext(new String[]{"prod"}, new String[0]),
                newContext(new String[]{"prod", "test"}, new String[0]),
                newContext(new String[]{"dev"}, new String[0]),
                newContext(new String[]{"test"}, new String[]{"prod"}))) {
            try (badContext) {
                assertThrows(SecurityException.class, () -> W2bTestEnvironment.requireTest(badContext));
                assertThrows(SecurityException.class, () -> W2bTestConnectionCapability.fromOwnedContainer(null,
                        badContext, W2bTestConnectionCapability.Store.WITNESS));
            }
        }
    }

    private static WatermarkCheckpoint checkpoint(String subject, Instant instant) {
        return new WatermarkCheckpoint("journal-1", "tenant-1", subject, 1, 12, HASH, "b".repeat(64), instant, "key-1");
    }

    private static boolean verify(SyntheticAttestorKeyFixture fixture, byte[] payload, byte[] signatureBytes) {
        try {
            var verifier = java.security.Signature.getInstance("Ed25519");
            verifier.initVerify(fixture.publicKey());
            verifier.update(payload);
            return verifier.verify(signatureBytes);
        } catch (java.security.GeneralSecurityException e) { throw new AssertionError(e); }
    }

    private static AnnotationConfigApplicationContext context(String[] active, String[] defaults) {
        return newContext(active, defaults);
    }

    private static AnnotationConfigApplicationContext newContext(String[] active, String[] defaults) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setDefaultProfiles(defaults);
        context.getEnvironment().setActiveProfiles(active);
        context.refresh();
        return context;
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

    private static byte[] replaceField(byte[] bytes, int targetIndex, String replacement) {
        return replaceFieldBytes(bytes, targetIndex, replacement.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] replaceFieldBytes(byte[] bytes, int targetIndex, byte[] replacement) {
        java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
        ByteBuffer input = ByteBuffer.wrap(bytes);
        int index = 0;
        while (input.hasRemaining()) {
            int length = input.getInt();
            byte[] value = new byte[length];
            input.get(value);
            byte[] next = index++ == targetIndex ? replacement : value;
            result.writeBytes(ByteBuffer.allocate(Integer.BYTES).putInt(next.length).array());
            result.writeBytes(next);
        }
        return result.toByteArray();
    }
}
