package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.CopilotProviderUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrustedRateCardVerifierTest {
    private static final Instant NOW = Instant.parse("2026-10-16T03:00:00Z");
    private KeyPair signingKey;
    private TrustedRateCardVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        signingKey = generator.generateKeyPair();
        verifier = new TrustedRateCardVerifier(Map.of("synthetic-test-key-v1", signingKey.getPublic()),
                java.util.Set.of(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void synthetic署名rateCardから独立期待値9円を計算する() {
        Fixture fixture = fixture("attempt-1", "idem-1", usage(1_000, 500, 1_500, 100, 200),
                new TokenUsageBounds(10_000L, 2_000L), 70, responseReceived(), validWindowCard());

        VerifiedCostEvaluation result = evaluate(fixture.card, fixture.execution, fixture.current,
                fixture.bounds, fixture.preflight, true);

        assertEquals(CopilotCostState.ACTUAL, result.evidence().costState());
        assertEquals(9, result.evidence().actualCostYen());
        assertEquals(null, result.evidence().upperBoundYen());
        assertEquals(usage(1_000, 500, 1_500, 100, 200), result.evidence().usage());
        assertEquals("synthetic-rate-card-v1", result.cardId());
        assertEquals("v1", result.cardVersion());
        assertEquals(64, result.cardDigest().length());
        assertEquals(64, result.executionBindingDigest().length());
    }

    @Test
    void 有効usageのACTUALは予算や事前上限を超えても保持し上限なしでも計算する() {
        SignedRateCard card = validWindowCard();
        String proof = "synthetic-rate-card:" + TrustedRateCardVerifier.canonicalCardDigest(card);
        Fixture overEstimate = fixture("attempt-over-estimate", "idem-over-estimate",
                usage(1_000, 500, 1_500, 100, 200), new TokenUsageBounds(10_000L, 2_000L),
                preflight("config-hash", 8, 100, proof), responseReceived(), card);
        VerifiedCostEvaluation estimated = evaluate(card, overEstimate.execution, overEstimate.current,
                overEstimate.bounds, overEstimate.preflight, true);
        assertEquals(9, estimated.evidence().actualCostYen());
        assertEquals("SYNTHETIC_RATE_CARD_ACTUAL_EXCEEDS_ESTIMATE", estimated.evidence().reasonCode());

        Fixture overBudget = fixture("attempt-over-budget", "idem-over-budget",
                usage(1_000, 500, 1_500, 100, 200), new TokenUsageBounds(10_000L, 2_000L),
                preflight("config-hash", 5, 5, proof), responseReceived(), card);
        VerifiedCostEvaluation budget = evaluate(card, overBudget.execution, overBudget.current,
                overBudget.bounds, overBudget.preflight, true);
        assertEquals(9, budget.evidence().actualCostYen());
        assertEquals("SYNTHETIC_RATE_CARD_ACTUAL_OVER_BUDGET", budget.evidence().reasonCode());

        PreflightCostEvidence denied = PreflightCostEvidence.denied("synthetic-test-policy-v1",
                "config-hash", proof, null, 0, "PREFLIGHT_DENIED");
        Fixture noUpper = fixture("attempt-no-upper", "idem-no-upper",
                usage(1_000, 500, 1_500, 100, 200), null, denied,
                responseReceived(), card);
        VerifiedCostEvaluation actual = evaluate(card, noUpper.execution, noUpper.current,
                noUpper.bounds, noUpper.preflight, true);
        assertEquals(9, actual.evidence().actualCostYen());
        assertEquals("SYNTHETIC_RATE_CARD_ACTUAL_NO_VALID_UPPER_BOUND", actual.evidence().reasonCode());
    }

    @Test
    void 母集合maxRateによる上限は部分集合がゼロでも過少評価しない() {
        SignedRateCard card = signedCard("prompt-3-cached-1", validWindow(),
                rates("3", "1", "0", "0"));
        Fixture fixture = fixture("attempt-bound", "idem-bound", usage(10_000, 0, 10_000, 0, 0),
                new TokenUsageBounds(10_000L, 0L, 2_000L, 0L), 30, possibleDispatch(), card);

        VerifiedCostEvaluation result = evaluate(card, fixture.execution, fixture.current,
                fixture.bounds, fixture.preflight, false);

        assertEquals(CopilotCostState.UPPER_BOUND, result.evidence().costState());
        assertEquals(30, result.evidence().upperBoundYen());
        assertEquals(30, fixture.preflight.upperBoundYen());
        assertReason("COST_MODE_MISMATCH", () -> evaluate(card, fixture.execution, fixture.current,
                fixture.bounds, fixture.preflight, true));

        Fixture missingUsage = fixture("attempt-bound-no-usage", "idem-bound-no-usage", null,
                new TokenUsageBounds(10_000L, 0L, 2_000L, 0L), 30, possibleDispatch(), card);
        VerifiedCostEvaluation safeUpper = evaluate(card, missingUsage.execution, missingUsage.current,
                missingUsage.bounds, missingUsage.preflight, false);
        assertEquals(30, safeUpper.evidence().upperBoundYen());
        assertEquals(null, safeUpper.evidence().usage());
    }

    @Test
    void cached子単価が高い上限は母集合全体へ高いrateを適用する() {
        SignedRateCard card = signedCard("prompt-3-cached-5", validWindow(),
                rates("3", "5", "0", "0"));
        Fixture fixture = fixture("attempt-cache-high", "idem-cache-high",
                usage(10_000, 0, 10_000, 0, 0), new TokenUsageBounds(10_000L, 0L), 50,
                possibleDispatch(), card);

        VerifiedCostEvaluation upper = evaluate(card, fixture.execution, fixture.current,
                fixture.bounds, fixture.preflight, false);
        assertEquals(50, upper.evidence().upperBoundYen());

        Fixture cachedActual = fixture("attempt-cache-actual", "idem-cache-actual",
                usage(10_000, 0, 10_000, 0, 2_000), new TokenUsageBounds(10_000L, 0L), 50,
                responseReceived(), card);
        VerifiedCostEvaluation actual = evaluate(card, cachedActual.execution, cachedActual.current,
                cachedActual.bounds, cachedActual.preflight, true);
        assertEquals(34, actual.evidence().actualCostYen());
        assertEquals(50, cachedActual.preflight.upperBoundYen());

        Fixture equalBoundary = fixture("attempt-cache-equal", "idem-cache-equal",
                usage(10_000, 0, 10_000, 0, 0), new TokenUsageBounds(10_000L, 0L, 2_000L, 0L), 30,
                responseReceived(), signedCard("prompt-3-cached-1-equal", validWindow(),
                        rates("3", "1", "0", "0")));
        VerifiedCostEvaluation equal = evaluate(equalBoundary.card, equalBoundary.execution,
                equalBoundary.current, equalBoundary.bounds, equalBoundary.preflight, true);
        assertEquals(30, equal.evidence().actualCostYen());
        Fixture equalUpper = fixture("attempt-cache-equal-upper", "idem-cache-equal-upper",
                usage(10_000, 0, 10_000, 0, 0), new TokenUsageBounds(10_000L, 0L, 2_000L, 0L), 30,
                possibleDispatch(), equalBoundary.card);
        assertEquals(30, evaluate(equalUpper.card, equalUpper.execution, equalUpper.current,
                equalUpper.bounds, equalUpper.preflight, false).evidence().upperBoundYen());
    }

    @Test
    void 合計後に一度だけHALF_UP丸めし上限は最後にCEILINGする() {
        SignedRateCard half = signedCard("half-yen", validWindow(), rates("1", "0", "1", "0"));
        Fixture halfFixture = fixture("attempt-half", "idem-half", usage(250, 250, 500, 0, 0),
                new TokenUsageBounds(250L, 250L), 1, responseReceived(), half);
        assertEquals(1, evaluate(half, halfFixture.execution, halfFixture.current,
                halfFixture.bounds, halfFixture.preflight, true).evidence().actualCostYen());

        SignedRateCard ceiling = signedCard("ceiling-bound", validWindow(), rates("1", "0", "0", "0"));
        Fixture upperFixture = fixture("attempt-ceiling", "idem-ceiling", usage(1, 0, 1, 0, 0),
                new TokenUsageBounds(1L, 0L), 1, possibleDispatch(), ceiling);
        assertEquals(1, evaluate(ceiling, upperFixture.execution, upperFixture.current,
                upperFixture.bounds, upperFixture.preflight, false).evidence().upperBoundYen());
    }

    @Test
    void 同一idempotency入力は同一結果を返し改変や別attemptへのreplayは拒否する() {
        Fixture fixture = fixture("attempt-replay", "idem-replay", usage(1_000, 500, 1_500, 100, 200),
                new TokenUsageBounds(10_000L, 2_000L), 70, responseReceived(), validWindowCard());
        VerifiedCostEvaluation first = evaluate(fixture.card, fixture.execution, fixture.current,
                fixture.bounds, fixture.preflight, true);
        VerifiedCostEvaluation duplicate = evaluate(fixture.card, fixture.execution, fixture.current,
                fixture.bounds, fixture.preflight, true);
        assertSame(first, duplicate);

        RateCardExecutionInput changedUsage = execution(fixture.execution, "idem-replay",
                usage(999, 500, 1_499, 100, 199), fixture.execution.preflightSnapshotHash());
        assertReason("ATTEMPT_REPLAY_OR_IDEMPOTENCY_CONFLICT", () -> evaluate(fixture.card,
                changedUsage, fixture.current, fixture.bounds, fixture.preflight, true));

        CurrentAttemptBinding otherAttempt = current(fixture.current, "attempt-other", null, null, null);
        assertReason("EXECUTION_BINDING_MISMATCH", () -> evaluate(fixture.card, fixture.execution,
                otherAttempt, fixture.bounds, fixture.preflight, true));
        RateCardExecutionInput otherKey = execution(fixture.execution, "different-key", fixture.execution.usage(),
                fixture.execution.preflightSnapshotHash());
        assertReason("ATTEMPT_REPLAY_OR_IDEMPOTENCY_CONFLICT", () -> evaluate(fixture.card,
                otherKey, fixture.current, fixture.bounds, fixture.preflight, true));
    }

    @Test
    void DTO由改ざんできずusage_preflight_config_domain差し替えを拒否する() {
        Fixture fixture = fixture("attempt-binding", "idem-binding", usage(1_000, 500, 1_500, 100, 200),
                new TokenUsageBounds(10_000L, 2_000L), 70, responseReceived(), validWindowCard());
        byte[] changedSignature = fixture.card.signature();
        changedSignature[0] ^= 1;
        SignedRateCard tampered = copyCard(fixture.card, changedSignature);
        assertReason("CARD_SIGNATURE_INVALID", () -> evaluate(tampered, fixture.execution,
                fixture.current, fixture.bounds, fixture.preflight, true));
        byte[] changedUsageSignature = signedUsage(fixture.execution).signature();
        changedUsageSignature[0] ^= 1;
        SignedUsageReceipt forgedUsage = new SignedUsageReceipt(fixture.execution,
                "synthetic-test-key-v1", changedUsageSignature);
        assertReason("USAGE_RECEIPT_SIGNATURE_INVALID", () -> verifier.evaluate(fixture.card, forgedUsage,
                fixture.current, fixture.bounds, fixture.preflight, true));
        SignedRateCard sameIdentityChangedRate = signedCard(fixture.card.cardId(), validWindow(),
                rates("4", "1", "12", "20"));
        assertEquals(fixture.card.version(), sameIdentityChangedRate.version());
        assertReason("CARD_NOT_REGISTERED", () -> evaluate(sameIdentityChangedRate,
                fixture.execution, fixture.current, fixture.bounds, fixture.preflight, true));

        SignedRateCard sameDomainRateA = signedCard("same-domain-a", validWindow(),
                rates("4", "1", "12", "20"));
        SignedRateCard sameDomainRateB = signedCard("same-domain-b", validWindow(),
                rates("5", "2", "13", "21"));
        trust(fixture.card, sameDomainRateA, sameDomainRateB);
        SignedUsageReceipt originalCardReceipt = signedUsage(fixture.execution);
        assertReason("USAGE_CARD_BINDING_MISMATCH", () -> verifier.evaluate(sameDomainRateA,
                originalCardReceipt, fixture.current, fixture.bounds, fixture.preflight, true));
        assertReason("USAGE_CARD_BINDING_MISMATCH", () -> verifier.evaluate(sameDomainRateB,
                originalCardReceipt, fixture.current, fixture.bounds, fixture.preflight, true));

        CurrentAttemptBinding wrongTenant = current(fixture.current, null, "tenant-b", null, null);
        assertReason("EXECUTION_BINDING_MISMATCH", () -> evaluate(fixture.card, fixture.execution,
                wrongTenant, fixture.bounds, fixture.preflight, true));
        CurrentAttemptBinding wrongTrace = current(fixture.current, null, null, "trace-other", null);
        assertReason("EXECUTION_BINDING_MISMATCH", () -> evaluate(fixture.card, fixture.execution,
                wrongTrace, fixture.bounds, fixture.preflight, true));
        CurrentAttemptBinding wrongVersion = current(fixture.current, null, null, null, 1);
        assertReason("EXECUTION_BINDING_MISMATCH", () -> evaluate(fixture.card, fixture.execution,
                wrongVersion, fixture.bounds, fixture.preflight, true));
        CurrentAttemptBinding wrongRegion = new CurrentAttemptBinding(fixture.current.attemptId(),
                fixture.current.tenantId(), fixture.current.traceId(), fixture.current.useCase(),
                fixture.current.attemptStatus(), fixture.current.statusVersion(), fixture.current.dispatchNonce(),
                fixture.current.provider(), fixture.current.modelRevision(), "region-other", fixture.current.tier(),
                fixture.current.configHash(), fixture.current.preflightSnapshotHash());
        assertReason("EXECUTION_BINDING_MISMATCH", () -> evaluate(fixture.card, fixture.execution,
                wrongRegion, fixture.bounds, fixture.preflight, true));
        CurrentAttemptBinding wrongNonce = new CurrentAttemptBinding(fixture.current.attemptId(),
                fixture.current.tenantId(), fixture.current.traceId(), fixture.current.useCase(),
                fixture.current.attemptStatus(), fixture.current.statusVersion(), "nonce-replayed",
                fixture.current.provider(), fixture.current.modelRevision(), fixture.current.region(),
                fixture.current.tier(), fixture.current.configHash(), fixture.current.preflightSnapshotHash());
        assertReason("EXECUTION_BINDING_MISMATCH", () -> evaluate(fixture.card, fixture.execution,
                wrongNonce, fixture.bounds, fixture.preflight, true));
        CurrentAttemptBinding wrongUseCase = new CurrentAttemptBinding(fixture.current.attemptId(),
                fixture.current.tenantId(), fixture.current.traceId(), "OTHER_USE_CASE",
                fixture.current.attemptStatus(), fixture.current.statusVersion(), fixture.current.dispatchNonce(),
                fixture.current.provider(), fixture.current.modelRevision(), fixture.current.region(),
                fixture.current.tier(), fixture.current.configHash(), fixture.current.preflightSnapshotHash());
        assertReason("EXECUTION_BINDING_MISMATCH", () -> evaluate(fixture.card, fixture.execution,
                wrongUseCase, fixture.bounds, fixture.preflight, true));

        RateCardExecutionInput wrongConfig = new RateCardExecutionInput(fixture.execution.attemptId(),
                fixture.execution.idempotencyKey(), fixture.execution.rateCardDigest(),
                fixture.execution.tenantId(), fixture.execution.traceId(),
                fixture.execution.useCase(), fixture.execution.attemptStatus(), fixture.execution.statusVersion(),
                fixture.execution.dispatchNonce(), fixture.execution.provider(), fixture.execution.modelRevision(),
                fixture.execution.region(), fixture.execution.tier(),
                "other-config", fixture.execution.preflightSnapshotHash(), fixture.execution.evaluatedAt(),
                fixture.execution.usage(), fixture.execution.dispatchOutcome());
        assertReason("EXECUTION_BINDING_MISMATCH", () -> evaluate(fixture.card, wrongConfig,
                fixture.current, fixture.bounds, fixture.preflight, true));

        PreflightCostEvidence changedPreflight = preflight("different-config", 70, 100,
                "synthetic-rate-card:" + TrustedRateCardVerifier.canonicalCardDigest(fixture.card));
        assertReason("EXECUTION_BINDING_MISMATCH", () -> evaluate(fixture.card, fixture.execution,
                fixture.current, fixture.bounds, changedPreflight, true));
        SignedRateCard otherModelCard = signedCard("other-model", validWindow(),
                rates("3", "1", "12", "20"), "gemini", "model-other");
        trust(otherModelCard);
        assertReason("CARD_DOMAIN_OR_WINDOW_MISMATCH", () -> evaluate(otherModelCard,
                fixture.execution, fixture.current, fixture.bounds, fixture.preflight, true));
        SignedRateCard otherProviderCard = signedCard("other-provider", validWindow(),
                rates("3", "1", "12", "20"), "other-provider", "model-rev-1");
        trust(otherProviderCard);
        assertReason("CARD_DOMAIN_OR_WINDOW_MISMATCH", () -> evaluate(otherProviderCard,
                fixture.execution, fixture.current, fixture.bounds, fixture.preflight, true));
    }

    @Test
    void 署名欠落未知鍵期限外不正usage負rateとoverflowを拒否する() {
        Fixture fixture = fixture("attempt-invalid", "idem-invalid", usage(1_000, 500, 1_500, 100, 200),
                new TokenUsageBounds(10_000L, 2_000L), 70, responseReceived(), validWindowCard());
        assertReason("PRODUCTION_REGISTRY_DENY_ALL", () -> new TrustedRateCardVerifier().evaluate(
                fixture.card, signedUsage(fixture.execution), fixture.current, fixture.bounds, fixture.preflight, true));

        SignedRateCard unknownKey = copyCard(fixture.card, fixture.card.signature(), "unknown-key");
        assertReason("CARD_ISSUER_UNKNOWN", () -> evaluate(unknownKey, fixture.execution,
                fixture.current, fixture.bounds, fixture.preflight, true));
        SignedRateCard unsigned = copyCard(fixture.card, null);
        assertReason("CARD_SIGNATURE_MISSING", () -> evaluate(unsigned, fixture.execution,
                fixture.current, fixture.bounds, fixture.preflight, true));
        SignedRateCard expired = signedCard("expired", new Window(NOW.minusSeconds(60), NOW),
                rates("3", "1", "12", "20"));
        trust(expired);
        assertReason("CARD_DOMAIN_OR_WINDOW_MISMATCH", () -> evaluate(expired,
                fixture.execution, fixture.current, fixture.bounds, fixture.preflight, true));

        assertInvalidUsage(fixture, usage(1_000, 500, 1_501, 100, 200));
        assertInvalidUsage(fixture, usage(-1, 500, 499, 100, 0));
        assertInvalidUsage(fixture, usage(1_000, 500, 1_500, 501, 0));
        assertInvalidUsage(fixture, usage(1_000, 500, 1_500, 0, 1_001));
        assertInvalidUsage(fixture, new CopilotProviderUsage(1, 1, 2, null, 0));
        assertInvalidUsage(fixture, new CopilotProviderUsage(1, 1, 2, 0, null));

        SignedRateCard negativeRate = signedCard("negative", validWindow(), rates("-1", "0", "0", "0"));
        trust(negativeRate);
        assertReason("CARD_CONTRACT_UNSUPPORTED", () -> evaluate(negativeRate, fixture.execution,
                fixture.current, fixture.bounds, fixture.preflight, true));
        EnumMap<SignedRateCard.Dimension, BigDecimal> incompleteRates = new EnumMap<>(rates("3", "1", "12", "20"));
        incompleteRates.remove(SignedRateCard.Dimension.THINKING);
        SignedRateCard incompleteCard = signedCard("incomplete", validWindow(), incompleteRates);
        trust(incompleteCard);
        assertReason("CARD_CONTRACT_UNSUPPORTED", () -> evaluate(incompleteCard, fixture.execution,
                fixture.current, fixture.bounds, fixture.preflight, true));
        SignedRateCard hugeRate = signedCard("huge", validWindow(), rates("999999999999999999", "0", "0", "0"));
        Fixture overflow = fixture("attempt-overflow", "idem-overflow",
                usage(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0, 0),
                new TokenUsageBounds((long) Integer.MAX_VALUE, 0L), Integer.MAX_VALUE,
                responseReceived(), hugeRate);
        assertReason("COST_OVERFLOW", () -> evaluate(hugeRate, overflow.execution,
                overflow.current, overflow.bounds, overflow.preflight, true));
    }

    @Test
    void 上限情報が欠けても検証済みusageのactualを保持する() {
        Fixture fixture = fixture("attempt-no-bound", "idem-no-bound", usage(1_000, 500, 1_500, 100, 200),
                new TokenUsageBounds(10_000L, 2_000L), 70, responseReceived(), validWindowCard());
        VerifiedCostEvaluation missingBound = evaluate(fixture.card, fixture.execution,
                fixture.current, new TokenUsageBounds(null, 2_000L), fixture.preflight, true);
        assertEquals(9, missingBound.evidence().actualCostYen());
        assertEquals("SYNTHETIC_RATE_CARD_ACTUAL_NO_VALID_UPPER_BOUND", missingBound.evidence().reasonCode());

        Fixture exceeded = fixture("attempt-exceeded", "idem-exceeded", usage(10_001, 0, 10_001, 0, 0),
                new TokenUsageBounds(10_000L, 2_000L), 70, responseReceived(), validWindowCard());
        VerifiedCostEvaluation actual = evaluate(exceeded.card, exceeded.execution,
                exceeded.current, exceeded.bounds, exceeded.preflight, true);
        assertEquals(30, actual.evidence().actualCostYen());
        assertEquals("SYNTHETIC_RATE_CARD_ACTUAL", actual.evidence().reasonCode());
    }

    private void assertInvalidUsage(Fixture fixture, CopilotProviderUsage usage) {
        trust(fixture.card);
        RateCardExecutionInput input = execution(fixture.execution, fixture.execution.idempotencyKey(), usage,
                fixture.execution.preflightSnapshotHash());
        assertReason("USAGE_CONTRACT_INVALID", () -> evaluate(fixture.card, input,
                fixture.current, fixture.bounds, fixture.preflight, true));
    }

    private VerifiedCostEvaluation evaluate(SignedRateCard card, RateCardExecutionInput input,
                                            CurrentAttemptBinding current, TokenUsageBounds bounds,
                                            PreflightCostEvidence preflight, boolean actualCost) {
        return verifier.evaluate(card, signedUsage(input), current, bounds, preflight, actualCost);
    }

    private SignedUsageReceipt signedUsage(RateCardExecutionInput input) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(signingKey.getPrivate());
            signer.update(TrustedRateCardVerifier.canonicalExecutionInput(input));
            return new SignedUsageReceipt(input, "synthetic-test-key-v1", signer.sign());
        } catch (Exception ex) {
            throw new AssertionError("synthetic usage receipt signing failed", ex);
        }
    }

    private Fixture fixture(String attemptId, String idempotencyKey, CopilotProviderUsage usage,
                            TokenUsageBounds bounds, int upperBound, DispatchOutcome dispatch,
                            SignedRateCard card) {
        int budget = Math.max(100, upperBound);
        PreflightCostEvidence preflight = preflight("config-hash", upperBound, budget,
                "synthetic-rate-card:" + TrustedRateCardVerifier.canonicalCardDigest(card));
        return fixture(attemptId, idempotencyKey, usage, bounds, preflight, dispatch, card);
    }

    private Fixture fixture(String attemptId, String idempotencyKey, CopilotProviderUsage usage,
                            TokenUsageBounds bounds, PreflightCostEvidence preflight, DispatchOutcome dispatch,
                            SignedRateCard card) {
        trust(card);
        String digest = TrustedRateCardVerifier.preflightSnapshotDigest(preflight);
        RateCardExecutionInput input = new RateCardExecutionInput(attemptId, idempotencyKey,
                TrustedRateCardVerifier.canonicalCardDigest(card), "tenant-a",
                "trace-" + attemptId, "MANAGEMENT_COPILOT", "PENDING", 0, "nonce-" + attemptId,
                "gemini", "model-rev-1", "synthetic-region", "synthetic-tier", "config-hash",
                digest, NOW, usage, dispatch);
        CurrentAttemptBinding current = new CurrentAttemptBinding(attemptId, "tenant-a", "trace-" + attemptId,
                "MANAGEMENT_COPILOT", "PENDING", 0, "nonce-" + attemptId, "gemini", "model-rev-1",
                "synthetic-region", "synthetic-tier", "config-hash", digest);
        return new Fixture(card, input, current, bounds, preflight);
    }

    private PreflightCostEvidence preflight(String configHash, int upperBound, int budget) {
        return preflight(configHash, upperBound, budget, "synthetic-proof-v1");
    }

    private PreflightCostEvidence preflight(String configHash, int upperBound, int budget, String proofIdentity) {
        return new PreflightCostEvidence("synthetic-test-policy-v1", configHash, proofIdentity,
                upperBound, budget, true, CopilotCostState.UPPER_BOUND,
                DispatchOutcome.notStarted("PREFLIGHT_ALLOWED"), "PREFLIGHT_ALLOWED");
    }

    private SignedRateCard validWindowCard() {
        return signedCard("synthetic-rate-card-v1", validWindow(), rates("3", "1", "12", "20"));
    }

    private Window validWindow() { return new Window(NOW.minusSeconds(300), NOW.plusSeconds(300)); }

    private SignedRateCard signedCard(String cardId, Window window, Map<SignedRateCard.Dimension, BigDecimal> rates) {
        return signedCard(cardId, window, rates, "gemini", "model-rev-1");
    }

    private SignedRateCard signedCard(String cardId, Window window,
                                      Map<SignedRateCard.Dimension, BigDecimal> rates,
                                      String provider, String model) {
        SignedRateCard unsigned = new SignedRateCard(cardId, "v1", provider, model, "synthetic-region",
                "synthetic-tier", "JPY", window.from, window.until, "JPY_PER_1000_TOKENS",
                RoundingMode.HALF_UP, "synthetic-test-key-v1", rates, null);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(signingKey.getPrivate());
            signer.update(TrustedRateCardVerifier.canonicalCard(unsigned));
            return copyCard(unsigned, signer.sign());
        } catch (Exception ex) {
            throw new AssertionError("synthetic test card signing failed", ex);
        }
    }

    private SignedRateCard copyCard(SignedRateCard source, byte[] signature) {
        return copyCard(source, signature, source.keyId());
    }

    private SignedRateCard copyCard(SignedRateCard source, byte[] signature, String keyId) {
        return new SignedRateCard(source.cardId(), source.version(), source.provider(), source.modelRevision(),
                source.region(), source.tier(), source.currency(), source.effectiveFrom(), source.effectiveUntil(),
                source.priceUnit(), source.roundingMode(), keyId, source.rates(), signature);
    }

    private void trust(SignedRateCard... cards) {
        verifier = new TrustedRateCardVerifier(Map.of("synthetic-test-key-v1", signingKey.getPublic()),
                java.util.Arrays.stream(cards).map(TrustedRateCardVerifier::canonicalCardDigest)
                        .collect(java.util.stream.Collectors.toSet()),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static Map<SignedRateCard.Dimension, BigDecimal> rates(String prompt, String cached,
                                                                    String candidate, String thinking) {
        EnumMap<SignedRateCard.Dimension, BigDecimal> map = new EnumMap<>(SignedRateCard.Dimension.class);
        map.put(SignedRateCard.Dimension.PROMPT, new BigDecimal(prompt));
        map.put(SignedRateCard.Dimension.CACHED, new BigDecimal(cached));
        map.put(SignedRateCard.Dimension.CANDIDATE, new BigDecimal(candidate));
        map.put(SignedRateCard.Dimension.THINKING, new BigDecimal(thinking));
        return map;
    }

    private static CopilotProviderUsage usage(int prompt, int candidate, int total, Integer thinking, Integer cached) {
        return new CopilotProviderUsage(prompt, candidate, total, thinking, cached);
    }

    private static DispatchOutcome responseReceived() {
        return new DispatchOutcome(DispatchOutcome.State.RESPONSE_RECEIVED, "TEST_RESPONSE_RECEIVED");
    }

    private static DispatchOutcome possibleDispatch() {
        return new DispatchOutcome(DispatchOutcome.State.MAY_HAVE_BEEN_SENT, "TEST_MAY_HAVE_BEEN_SENT");
    }

    private static RateCardExecutionInput execution(RateCardExecutionInput source, String idempotencyKey,
                                                     CopilotProviderUsage usage, String preflightHash) {
        return new RateCardExecutionInput(source.attemptId(), idempotencyKey, source.rateCardDigest(),
                source.tenantId(), source.traceId(),
                source.useCase(), source.attemptStatus(), source.statusVersion(), source.dispatchNonce(),
                source.provider(), source.modelRevision(), source.region(), source.tier(),
                source.configHash(), preflightHash,
                source.evaluatedAt(), usage, source.dispatchOutcome());
    }

    private static CurrentAttemptBinding current(CurrentAttemptBinding source, String attemptId, String tenantId,
                                                 String traceId, Integer version) {
        return new CurrentAttemptBinding(attemptId == null ? source.attemptId() : attemptId,
                tenantId == null ? source.tenantId() : tenantId,
                traceId == null ? source.traceId() : traceId, source.useCase(), source.attemptStatus(),
                version == null ? source.statusVersion() : version, source.dispatchNonce(), source.provider(),
                source.modelRevision(), source.region(), source.tier(), source.configHash(),
                source.preflightSnapshotHash());
    }

    private static void assertReason(String expected, org.junit.jupiter.api.function.Executable action) {
        RateCardVerificationException error = assertThrows(RateCardVerificationException.class, action);
        assertEquals(expected, error.reasonCode());
    }

    private record Window(Instant from, Instant until) { }
    private record Fixture(SignedRateCard card, RateCardExecutionInput execution,
                           CurrentAttemptBinding current, TokenUsageBounds bounds,
                           PreflightCostEvidence preflight) { }
}
