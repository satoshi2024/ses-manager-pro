package com.ses.service.ai.copilot.dispatch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF08 B2c-1 §1.4: ClaimPermit・DispatchTicketを通常のsource・DTO・serializationから作れないことを固定する。 */
class ClaimPermitTypeContractTest {

    private static final Instant NOW = Instant.parse("2026-11-15T01:00:00.123456789Z");

    @ParameterizedTest
    @ValueSource(classes = {ClaimPermit.class, DispatchTicket.class})
    void permitとticketはfinalでpublic_constructorもSerializableもCloneableも持たない(Class<?> type) {
        assertTrue(Modifier.isFinal(type.getModifiers()), "継承で別実装を作れない");
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            assertFalse(Modifier.isPublic(constructor.getModifiers()) || Modifier.isProtected(constructor.getModifiers()),
                    "constructorはpackage内専用: " + constructor);
            assertFalse(constructor.getParameterCount() == 0, "no-arg constructorを持たない");
        }
        assertFalse(Serializable.class.isAssignableFrom(type));
        assertFalse(Cloneable.class.isAssignableFrom(type));
        assertFalse(type.isRecord(), "recordのcanonical constructorはpublicになる");
    }

    @ParameterizedTest
    @ValueSource(classes = {ClaimPermit.class, DispatchTicket.class})
    void permitとticketはsetterもpublic_factoryもJackson注釈も持たない(Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            assertFalse(method.getName().startsWith("set"), "setterを持たない: " + method.getName());
            if (Modifier.isStatic(method.getModifiers()) && Modifier.isPublic(method.getModifiers())) {
                assertFalse(type.isAssignableFrom(method.getReturnType()), "public factoryを持たない: " + method);
            }
            assertNoJacksonAnnotation(method.getDeclaredAnnotations(), method.toString());
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            assertNoJacksonAnnotation(constructor.getDeclaredAnnotations(), constructor.toString());
        }
        assertNoJacksonAnnotation(type.getDeclaredAnnotations(), type.getName());
    }

    @Test
    void main_sourceでpermitを作れるのはclaim_serviceだけである() throws Exception {
        List<String> creators = new java.util.ArrayList<>();
        try (var files = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java"))) {
            for (java.nio.file.Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
                if (source.contains("new ClaimPermit(")) {
                    creators.add(file.getFileName().toString());
                }
            }
        }
        assertEquals(List.of("CopilotDispatchClaimService.java"), creators);
    }

    @Test
    void permitは一度だけconsumeでき並行の再利用も一件だけ成功する() throws Exception {
        ClaimPermit permit = new ClaimPermit(ticket(), NOW);
        assertFalse(permit.isConsumed());
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Boolean>> results = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    return permit.tryConsume();
                }));
            }
            start.countDown();
            long winners = 0;
            for (Future<Boolean> result : results) {
                if (result.get(5, TimeUnit.SECONDS)) winners++;
            }
            assertEquals(1, winners);
        } finally {
            pool.shutdownNow();
        }
        assertTrue(permit.isConsumed());
        assertFalse(permit.tryConsume(), "未使用へ戻す手段は無い");
    }

    @Test
    void permitはticketのclaim_IDを予約IDとして持ち配列を複製して返しtoStringに秘密を出さない() {
        DispatchTicket ticket = ticket();
        ClaimPermit permit = new ClaimPermit(ticket, NOW);
        assertEquals(ticket.claimId(), permit.claimId());
        assertEquals(ticket.claimId(), permit.reservationId(), "計画 C-3: reservation ID = claim ID");
        assertEquals(ticket.runId(), permit.runId());
        assertEquals(ticket.tenantId(), permit.tenantId());
        assertArrayEquals(ticket.bindingHash(), permit.bindingHash());
        byte[] exposed = permit.bindingHash();
        exposed[0] ^= 1;
        assertFalse(Arrays.equals(exposed, permit.bindingHash()), "内部配列を変更できない");
        assertEquals("ClaimPermit[claimId=" + ticket.claimId() + "]", permit.toString());
        assertEquals("DispatchTicket[claimId=" + ticket.claimId() + "]", ticket.toString());
    }

    @Test
    void ticketはclaim_IDとtrace_IDの正本表記だけを受け付け時刻をマイクロ秒へ切り捨てる() {
        DispatchTicket ticket = ticket();
        assertEquals(Instant.parse("2026-11-15T01:00:00.123456Z"), ticket.issuedAt());
        assertEquals(ticket.issuedAt().plus(ticket.request().attemptTtl()), ticket.expiresAt());
        String upper = UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
        String noHyphen = UUID.randomUUID().toString().replace("-", "");
        for (String invalid : List.of(upper, noHyphen, "{" + UUID.randomUUID() + "}", "not-a-uuid")) {
            assertThrows(IllegalArgumentException.class, () -> new DispatchTicket(1L, "tenant-a",
                    UUID.randomUUID().toString(), invalid, ticket.request(), new byte[32], new byte[32], NOW, 0L));
            assertThrows(IllegalArgumentException.class, () -> new DispatchTicket(1L, "tenant-a",
                    invalid, UUID.randomUUID().toString(), ticket.request(), new byte[32], new byte[32], NOW, 0L));
        }
        assertThrows(IllegalArgumentException.class, () -> new DispatchTicket(1L, "tenant-a",
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), ticket.request(), new byte[15],
                new byte[32], NOW, 0L), "nonceは128bit以上");
    }

    @Test
    void binding_hashは全fieldに依存し同じ値からは同じhashになる() {
        DispatchTicket ticket = ticket();
        assertArrayEquals(ticket.bindingHash(), DispatchClaimFixtures.copyOf(ticket).bindingHash());
        DispatchIssueRequest base = ticket.request();
        DispatchIssueRequest otherRegion = new DispatchIssueRequest(base.provider(), base.modelRevision(), "us-central1",
                base.tier(), base.configHash(), base.artifactVersionId(), base.scopeHash(), base.inputHash(),
                base.preflightSnapshotHash(), base.requestSha256(), base.approvalDigest(), base.decisionId(),
                base.cardId(), base.cardVersion(), base.cardDigest(), base.cardEffectiveFrom(), base.cardEffectiveUntil(),
                base.policyId(), base.policyVersion(), base.policyDigest(), base.destinationHost(), base.queryId(),
                base.legalEntityId(), base.promptBytes(), base.costScope(), base.upperBoundYen(), base.dailyLimitYen(),
                base.monthlyLimitYen(), base.attemptTtl(), base.deadlineNanos());
        DispatchTicket drifted = new DispatchTicket(ticket.runId(), ticket.tenantId(), ticket.traceId(),
                ticket.claimId(), otherRegion, ticket.nonce(), ticket.idempotencyKey(), ticket.issuedAt(),
                ticket.issuedNanos());
        assertFalse(Arrays.equals(ticket.bindingHash(), drifted.bindingHash()));
        assertFalse(DispatchClaimFixtures.readyRow(ticket).equals(DispatchClaimFixtures.readyRow(drifted)));
        assertEquals(List.of(), ticket.mismatches(DispatchClaimFixtures.readyRow(ticket)));
        assertTrue(ticket.mismatches(DispatchClaimFixtures.readyRow(drifted)).containsAll(
                List.of("dispatch_binding_hash", "dispatch_region")));
    }

    @Test
    void 入力は識別子と16進表記と上限値の形式を検査する() {
        DispatchIssueRequest base = ticket().request();
        assertThrows(IllegalArgumentException.class, () -> new DispatchIssueRequest(base.provider(),
                base.modelRevision(), base.region(), base.tier(), base.configHash().toUpperCase(java.util.Locale.ROOT),
                base.artifactVersionId(), base.scopeHash(), base.inputHash(), base.preflightSnapshotHash(),
                base.requestSha256(), base.approvalDigest(), base.decisionId(), base.cardId(), base.cardVersion(),
                base.cardDigest(), base.cardEffectiveFrom(), base.cardEffectiveUntil(), base.policyId(),
                base.policyVersion(), base.policyDigest(), base.destinationHost(), base.queryId(), base.legalEntityId(),
                base.promptBytes(), base.costScope(), base.upperBoundYen(), base.dailyLimitYen(), base.monthlyLimitYen(),
                base.attemptTtl(), base.deadlineNanos()), "hashは小文字64桁");
        assertThrows(IllegalArgumentException.class, () -> new DispatchIssueRequest(base.provider(),
                base.modelRevision(), base.region(), base.tier(), base.configHash(), base.artifactVersionId(),
                base.scopeHash(), base.inputHash(), base.preflightSnapshotHash(), base.requestSha256(),
                base.approvalDigest(), base.decisionId(), base.cardId(), base.cardVersion(), base.cardDigest(),
                base.cardEffectiveFrom(), base.cardEffectiveUntil(), base.policyId(), base.policyVersion(),
                base.policyDigest(), base.destinationHost(), base.queryId(), base.legalEntityId(), base.promptBytes(),
                base.costScope(), 0, base.dailyLimitYen(), base.monthlyLimitYen(), base.attemptTtl(),
                base.deadlineNanos()), "費用予約見積りは1円以上");
        assertThrows(IllegalArgumentException.class, () -> new DispatchIssueRequest(base.provider(),
                base.modelRevision(), base.region(), base.tier(), base.configHash(), base.artifactVersionId(),
                base.scopeHash(), base.inputHash(), base.preflightSnapshotHash(), base.requestSha256(),
                base.approvalDigest(), base.decisionId(), base.cardId(), base.cardVersion(), base.cardDigest(),
                base.cardEffectiveUntil(), base.cardEffectiveFrom(), base.policyId(), base.policyVersion(),
                base.policyDigest(), base.destinationHost(), base.queryId(), base.legalEntityId(), base.promptBytes(),
                base.costScope(), base.upperBoundYen(), base.dailyLimitYen(), base.monthlyLimitYen(),
                base.attemptTtl(), base.deadlineNanos()), "card期間の前後関係");
        assertThrows(IllegalArgumentException.class, () -> new DispatchIssueRequest(base.provider(),
                base.modelRevision(), base.region(), base.tier(), base.configHash(), base.artifactVersionId(),
                base.scopeHash(), base.inputHash(), base.preflightSnapshotHash(), base.requestSha256(),
                base.approvalDigest(), base.decisionId(), base.cardId(), base.cardVersion(), base.cardDigest(),
                base.cardEffectiveFrom(), base.cardEffectiveUntil(), base.policyId(), base.policyVersion(),
                base.policyDigest(), base.destinationHost(), base.queryId(), base.legalEntityId(), base.promptBytes(),
                base.costScope(), base.upperBoundYen(), base.dailyLimitYen(), base.monthlyLimitYen(),
                java.time.Duration.ofMinutes(11), base.deadlineNanos()), "attemptの有効時間は10分以下");
        assertThrows(IllegalArgumentException.class, () -> new DispatchIssueRequest("gemini\nX",
                base.modelRevision(), base.region(), base.tier(), base.configHash(), base.artifactVersionId(),
                base.scopeHash(), base.inputHash(), base.preflightSnapshotHash(), base.requestSha256(),
                base.approvalDigest(), base.decisionId(), base.cardId(), base.cardVersion(), base.cardDigest(),
                base.cardEffectiveFrom(), base.cardEffectiveUntil(), base.policyId(), base.policyVersion(),
                base.policyDigest(), base.destinationHost(), base.queryId(), base.legalEntityId(), base.promptBytes(),
                base.costScope(), base.upperBoundYen(), base.dailyLimitYen(), base.monthlyLimitYen(),
                base.attemptTtl(), base.deadlineNanos()), "識別子に改行を含めない");
    }

    private static DispatchTicket ticket() {
        return new DispatchTicket(41L, "tenant-a", UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                DispatchClaimFixtures.request(7L, NOW, 0L), new byte[32], new byte[32], NOW, 0L);
    }

    private static void assertNoJacksonAnnotation(Annotation[] annotations, String where) {
        for (Annotation annotation : annotations) {
            assertFalse(annotation.annotationType().getName().startsWith("com.fasterxml.jackson"),
                    "Jackson注釈を持たない: " + where);
        }
    }
}
