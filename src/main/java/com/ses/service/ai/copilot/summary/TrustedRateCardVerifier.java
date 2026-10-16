package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.CopilotProviderUsage;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Clock;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 署名済みsynthetic card検証の内部境界。本番用registryは固定deny-all。 */
final class TrustedRateCardVerifier {
    private static final String REQUIRED_CURRENCY = "JPY";
    private static final String REQUIRED_PRICE_UNIT = "JPY_PER_1000_TOKENS";
    private static final Set<SignedRateCard.Dimension> REQUIRED_DIMENSIONS =
            EnumSet.allOf(SignedRateCard.Dimension.class);

    private final Map<String, PublicKey> verificationKeys;
    private final Set<String> approvedCardDigests;
    private final Clock clock;
    private final boolean syntheticTestVerifier;
    private final Map<String, CachedEvaluation> completedAttempts = new ConcurrentHashMap<>();

    /** Spring beanではなく、productionから署名鍵を登録する入口も持たない。 */
    TrustedRateCardVerifier() {
        this.verificationKeys = Map.of();
        this.approvedCardDigests = Set.of();
        this.clock = Clock.systemUTC();
        this.syntheticTestVerifier = false;
    }

    /** test package専用constructor。production call siteからは利用せず、test keyだけを渡す。 */
    TrustedRateCardVerifier(Map<String, PublicKey> testVerificationKeys,
                            Set<String> testApprovedCardDigests, Clock clock) {
        this.verificationKeys = Map.copyOf(testVerificationKeys);
        this.approvedCardDigests = Set.copyOf(testApprovedCardDigests);
        this.clock = Objects.requireNonNull(clock);
        this.syntheticTestVerifier = true;
    }

    synchronized VerifiedCostEvaluation evaluate(SignedRateCard card,
                                                  SignedUsageReceipt usageReceipt,
                                                  CurrentAttemptBinding currentAttempt,
                                                  TokenUsageBounds bounds,
                                                  PreflightCostEvidence preflight,
                                                  boolean actualCost) {
        if (!syntheticTestVerifier) throw rejected("PRODUCTION_REGISTRY_DENY_ALL");
        verifyUsageReceiptSignature(usageReceipt);
        RateCardExecutionInput execution = usageReceipt.input();
        if (execution == null) throw rejected("EXECUTION_BINDING_MISMATCH");
        VerifiedRateCard verifiedCard = validateCardSignature(card);
        CopilotProviderUsage validatedUsage = isValidUsage(execution.usage()) ? execution.usage() : null;
        if (actualCost && validatedUsage == null) throw rejected("USAGE_CONTRACT_INVALID");
        if (validatedUsage != null && execution.dispatchOutcome().state() == DispatchOutcome.State.NOT_STARTED) {
            throw rejected("DISPATCH_USAGE_CONFLICT");
        }
        VerifiedUsageBinding verifiedUsage = validateExecutionBinding(execution, currentAttempt, preflight, bounds,
                verifiedCard.digest, validatedUsage);
        validateCardDomain(card, execution);
        validatePreflightIdentity(preflight, execution);
        boolean shouldCalculateActual = validatedUsage != null;
        if (actualCost != shouldCalculateActual) throw rejected("COST_MODE_MISMATCH");
        if (!actualCost) validatePreflight(preflight, execution, bounds, card);
        if (!actualCost && validatedUsage != null && (bounds == null
                || validatedUsage.promptTokenCount() > bounds.promptMax()
                || validatedUsage.candidateTokenCount() > bounds.candidateMax())) {
            throw rejected("USAGE_EXCEEDS_PREFLIGHT_BOUND");
        }
        return evaluateVerified(verifiedCard, verifiedUsage, preflight, actualCost);
    }

    private VerifiedCostEvaluation evaluateVerified(VerifiedRateCard verifiedCard,
                                                     VerifiedUsageBinding verifiedUsage,
                                                     PreflightCostEvidence preflight,
                                                     boolean actualCost) {
        SignedRateCard card = verifiedCard.card;
        RateCardExecutionInput execution = verifiedUsage.input;
        int calculatedYen;
        int upperBoundYen = 0;
        String reasonCode;
        if (actualCost) {
            calculatedYen = calculateActual(verifiedCard, verifiedUsage);
            reasonCode = actualPreflightReason(verifiedCard, verifiedUsage, preflight, calculatedYen);
        } else {
            upperBoundYen = calculateUpperBound(verifiedCard, verifiedUsage);
            if (!Objects.equals(preflight.upperBoundYen(), upperBoundYen)
                    || upperBoundYen > preflight.budgetYen()) throw rejected("PREFLIGHT_BOUND_MISMATCH");
            calculatedYen = upperBoundYen;
            reasonCode = "SYNTHETIC_RATE_CARD_UPPER_BOUND";
        }
        if (!actualCost && execution.dispatchOutcome().state() == DispatchOutcome.State.NOT_STARTED) {
            throw rejected("UPPER_BOUND_REQUIRES_POSSIBLE_DISPATCH");
        }

        String cardDigest = verifiedCard.digest;
        String bindingDigest = verifiedUsage.digest;
        CachedEvaluation cached = completedAttempts.get(execution.attemptId());
        if (cached != null) {
            if (cached.idempotencyKey.equals(execution.idempotencyKey())
                    && cached.bindingDigest.equals(bindingDigest)) return cached.result;
            throw rejected("ATTEMPT_REPLAY_OR_IDEMPOTENCY_CONFLICT");
        }

        SummaryCostEvidence evidence = actualCost
                ? new SummaryCostEvidence(CopilotCostState.ACTUAL, calculatedYen, null,
                execution.dispatchOutcome(), preflight, verifiedUsage.usage, reasonCode)
                : new SummaryCostEvidence(CopilotCostState.UPPER_BOUND, null, upperBoundYen,
                execution.dispatchOutcome(), preflight, verifiedUsage.usage, reasonCode);
        VerifiedCostEvaluation result = new VerifiedCostEvaluation(evidence, card.cardId(), card.version(),
                cardDigest, bindingDigest);
        completedAttempts.put(execution.attemptId(),
                new CachedEvaluation(execution.idempotencyKey(), bindingDigest, result));
        return result;
    }

    static byte[] canonicalCard(SignedRateCard card) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, card.cardId());
            write(out, card.version());
            write(out, card.provider());
            write(out, card.modelRevision());
            write(out, card.region());
            write(out, card.tier());
            write(out, card.currency());
            write(out, card.effectiveFrom().toString());
            write(out, card.effectiveUntil().toString());
            write(out, card.priceUnit());
            write(out, card.roundingMode().name());
            write(out, card.keyId());
            for (SignedRateCard.Dimension dimension : SignedRateCard.Dimension.values()) {
                BigDecimal rate = card.rates().get(dimension);
                write(out, dimension.name());
                write(out, rate == null ? "<missing>" : rate.toPlainString());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (Exception ex) {
            throw rejected("CARD_CANONICALIZATION_FAILED");
        }
    }

    static byte[] canonicalExecutionInput(RateCardExecutionInput input) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (String value : new String[]{input.attemptId(), input.idempotencyKey(), input.tenantId(),
                    input.traceId(), input.useCase(), input.attemptStatus(), input.statusVersion() + "",
                    input.dispatchNonce(), input.provider(), input.modelRevision(), input.region(), input.tier(),
                    input.configHash(),
                    input.preflightSnapshotHash(), input.evaluatedAt() == null ? "<missing>" : input.evaluatedAt().toString(),
                    input.dispatchOutcome() == null ? "<missing>" : input.dispatchOutcome().state().name(),
                    input.dispatchOutcome() == null ? "<missing>" : input.dispatchOutcome().reasonCode(),
                    usageValue(input.usage(), "prompt"), usageValue(input.usage(), "candidate"),
                    usageValue(input.usage(), "total"), usageValue(input.usage(), "thinking"),
                    usageValue(input.usage(), "cached")}) write(out, value == null ? "<null>" : value);
            out.flush();
            return bytes.toByteArray();
        } catch (Exception ex) {
            throw rejected("USAGE_RECEIPT_CANONICALIZATION_FAILED");
        }
    }

    private void verifyUsageReceiptSignature(SignedUsageReceipt receipt) {
        if (receipt == null || receipt.input() == null || !validText(receipt.keyId(), 64)
                || receipt.signature() == null || receipt.signature().length != 64) {
            throw rejected("USAGE_RECEIPT_SIGNATURE_MISSING");
        }
        PublicKey key = verificationKeys.get(receipt.keyId());
        if (key == null) throw rejected("USAGE_RECEIPT_ISSUER_UNKNOWN");
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonicalExecutionInput(receipt.input()));
            if (!verifier.verify(receipt.signature())) throw rejected("USAGE_RECEIPT_SIGNATURE_INVALID");
        } catch (RateCardVerificationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw rejected("USAGE_RECEIPT_SIGNATURE_INVALID");
        }
    }

    private VerifiedRateCard validateCardSignature(SignedRateCard card) {
        if (card == null || !validText(card.keyId(), 64) || card.signature() == null
                || card.signature().length != 64) throw rejected("CARD_SIGNATURE_MISSING");
        if (!validText(card.cardId(), 128) || !validText(card.version(), 64)
                || !validText(card.provider(), 64) || !validText(card.modelRevision(), 128)
                || !validText(card.region(), 64) || !validText(card.tier(), 64)
                || !REQUIRED_CURRENCY.equals(card.currency()) || !REQUIRED_PRICE_UNIT.equals(card.priceUnit())
                || card.roundingMode() != RoundingMode.HALF_UP
                || !card.effectiveFrom().isBefore(card.effectiveUntil())
                || !card.rates().keySet().equals(REQUIRED_DIMENSIONS)
                || card.rates().values().stream().anyMatch(rate -> rate == null || rate.signum() < 0
                || rate.scale() < -12 || rate.scale() > 12 || rate.precision() > 18)) {
            throw rejected("CARD_CONTRACT_UNSUPPORTED");
        }
        PublicKey key = verificationKeys.get(card.keyId());
        if (key == null) throw rejected("CARD_ISSUER_UNKNOWN");
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(canonicalCard(card));
            if (!verifier.verify(card.signature())) throw rejected("CARD_SIGNATURE_INVALID");
        } catch (RateCardVerificationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw rejected("CARD_SIGNATURE_INVALID");
        }
        String digest = canonicalCardDigest(card);
        if (!approvedCardDigests.contains(digest)) throw rejected("CARD_NOT_REGISTERED");
        return new VerifiedRateCard(card, digest);
    }

    private void validateCardDomain(SignedRateCard card, RateCardExecutionInput execution) {
        if (!card.provider().equals(execution.provider()) || !card.modelRevision().equals(execution.modelRevision())
                || !card.region().equals(execution.region()) || !card.tier().equals(execution.tier())
                || execution.evaluatedAt() == null || execution.evaluatedAt().isBefore(card.effectiveFrom())
                || !execution.evaluatedAt().isBefore(card.effectiveUntil())
                || execution.evaluatedAt().isAfter(clock.instant())) {
            throw rejected("CARD_DOMAIN_OR_WINDOW_MISMATCH");
        }
    }

    private VerifiedUsageBinding validateExecutionBinding(RateCardExecutionInput input,
                                                          CurrentAttemptBinding current,
                                                          PreflightCostEvidence preflight,
                                                          TokenUsageBounds bounds,
                                                          String cardDigest,
                                                          CopilotProviderUsage validatedUsage) {
        if (input == null || current == null || preflight == null
                || !validText(input.attemptId(), 128) || !validText(input.idempotencyKey(), 128)
                || !validText(input.tenantId(), 128) || !validText(input.traceId(), 128)
                || !validText(input.useCase(), 64)
                || !"PENDING".equals(input.attemptStatus())
                || !validText(input.dispatchNonce(), 128)
                || !validText(input.provider(), 64) || !validText(input.modelRevision(), 128)
                || !validText(input.region(), 64) || !validText(input.tier(), 64)
                || !validText(input.configHash(), 128) || !validText(input.preflightSnapshotHash(), 64)
                || input.evaluatedAt() == null
                || input.dispatchOutcome() == null || input.statusVersion() < 0
                || !validReason(input.dispatchOutcome().reasonCode())
                || !Objects.equals(input.attemptId(), current.attemptId())
                || !Objects.equals(input.tenantId(), current.tenantId())
                || !Objects.equals(input.traceId(), current.traceId())
                || !Objects.equals(input.useCase(), current.useCase())
                || !Objects.equals(input.attemptStatus(), current.attemptStatus())
                || input.statusVersion() != current.statusVersion()
                || !Objects.equals(input.dispatchNonce(), current.dispatchNonce())
                || !Objects.equals(input.provider(), current.provider())
                || !Objects.equals(input.modelRevision(), current.modelRevision())
                || !Objects.equals(input.region(), current.region()) || !Objects.equals(input.tier(), current.tier())
                || !Objects.equals(input.configHash(), current.configHash())
                || !Objects.equals(input.preflightSnapshotHash(), current.preflightSnapshotHash())
                || !Objects.equals(input.preflightSnapshotHash(), preflightDigest(preflight))) {
            throw rejected("EXECUTION_BINDING_MISMATCH");
        }
        String digest = sha256(canonicalBinding(cardDigest, input, current, bounds, preflight));
        return new VerifiedUsageBinding(input, current, bounds, preflight, validatedUsage, digest);
    }

    private void validatePreflight(PreflightCostEvidence preflight, RateCardExecutionInput input,
                                   TokenUsageBounds bounds, SignedRateCard card) {
        if (!preflight.dispatchAllowed() || preflight.costState() != CopilotCostState.UPPER_BOUND
                || preflight.dispatchOutcome().state() != DispatchOutcome.State.NOT_STARTED
                || !Objects.equals(preflight.configIdentity(), input.configHash())
                || preflight.upperBoundYen() == null || preflight.budgetYen() == null
                || preflight.upperBoundYen() > preflight.budgetYen()
                || bounds == null || bounds.promptMax() == null || bounds.candidateMax() == null
                || bounds.promptMax() < 0 || bounds.candidateMax() < 0
                || (bounds.cachedMax() != null && (bounds.cachedMax() < 0
                || bounds.cachedMax() > bounds.promptMax()))
                || (bounds.thinkingMax() != null && (bounds.thinkingMax() < 0
                || bounds.thinkingMax() > bounds.candidateMax()))
                || !validText(preflight.policyId(), 128) || !validText(preflight.configIdentity(), 128)
                || !validText(preflight.proofIdentity(), 128) || !validReason(preflight.reasonCode())
                || !validReason(preflight.dispatchOutcome().reasonCode())
                || !Objects.equals(preflight.proofIdentity(), "synthetic-rate-card:"
                + canonicalCardDigest(card))
                || !REQUIRED_CURRENCY.equals(card.currency())) {
            throw rejected("PREFLIGHT_OR_USAGE_BOUND_INVALID");
        }
    }

    private void validatePreflightIdentity(PreflightCostEvidence preflight, RateCardExecutionInput input) {
        if (preflight == null || !Objects.equals(preflight.configIdentity(), input.configHash())
                || !Objects.equals(input.preflightSnapshotHash(), preflightDigest(preflight))) {
            throw rejected("PREFLIGHT_SNAPSHOT_MISMATCH");
        }
    }

    private String actualPreflightReason(VerifiedRateCard card, VerifiedUsageBinding usage,
                                         PreflightCostEvidence preflight, int actualYen) {
        if (preflight == null || !preflight.dispatchAllowed()
                || preflight.costState() != CopilotCostState.UPPER_BOUND
                || preflight.dispatchOutcome().state() != DispatchOutcome.State.NOT_STARTED
                || preflight.upperBoundYen() == null || preflight.budgetYen() == null
                || usage.bounds == null || !validBounds(usage.bounds)) {
            return "SYNTHETIC_RATE_CARD_ACTUAL_NO_VALID_UPPER_BOUND";
        }
        if (actualYen > preflight.budgetYen()) return "SYNTHETIC_RATE_CARD_ACTUAL_OVER_BUDGET";
        if (actualYen > preflight.upperBoundYen()) return "SYNTHETIC_RATE_CARD_ACTUAL_EXCEEDS_ESTIMATE";
        try {
            int calculatedUpper = calculateUpperBound(card, usage);
            if (calculatedUpper != preflight.upperBoundYen()) {
                return "SYNTHETIC_RATE_CARD_ACTUAL_PREFLIGHT_MISMATCH";
            }
            return "SYNTHETIC_RATE_CARD_ACTUAL";
        } catch (RateCardVerificationException ex) {
            return "SYNTHETIC_RATE_CARD_ACTUAL_NO_VALID_UPPER_BOUND";
        }
    }

    private boolean validBounds(TokenUsageBounds bounds) {
        return bounds.promptMax() != null && bounds.candidateMax() != null
                && bounds.promptMax() >= 0 && bounds.candidateMax() >= 0
                && (bounds.cachedMax() == null || (bounds.cachedMax() >= 0
                && bounds.cachedMax() <= bounds.promptMax()))
                && (bounds.thinkingMax() == null || (bounds.thinkingMax() >= 0
                && bounds.thinkingMax() <= bounds.candidateMax()));
    }

    private boolean isValidUsage(CopilotProviderUsage usage) {
        return usage != null && usage.promptTokenCount() >= 0 && usage.candidateTokenCount() >= 0
                && usage.totalTokenCount() >= 0 && usage.thoughtsTokenCount() != null
                && usage.cachedContentTokenCount() != null && usage.thoughtsTokenCount() >= 0
                && usage.cachedContentTokenCount() >= 0
                && (long) usage.promptTokenCount() + usage.candidateTokenCount() == usage.totalTokenCount()
                && usage.cachedContentTokenCount() <= usage.promptTokenCount()
                && usage.thoughtsTokenCount() <= usage.candidateTokenCount();
    }

    private int calculateActual(VerifiedRateCard verifiedCard, VerifiedUsageBinding verifiedUsage) {
        SignedRateCard card = verifiedCard.card;
        CopilotProviderUsage usage = verifiedUsage.usage;
        long promptExclusive = (long) usage.promptTokenCount() - usage.cachedContentTokenCount();
        long candidateExclusive = (long) usage.candidateTokenCount() - usage.thoughtsTokenCount();
        BigDecimal raw = charge(promptExclusive, card.rates().get(SignedRateCard.Dimension.PROMPT))
                .add(charge(usage.cachedContentTokenCount(), card.rates().get(SignedRateCard.Dimension.CACHED)))
                .add(charge(candidateExclusive, card.rates().get(SignedRateCard.Dimension.CANDIDATE)))
                .add(charge(usage.thoughtsTokenCount(), card.rates().get(SignedRateCard.Dimension.THINKING)));
        try {
            return raw.setScale(0, card.roundingMode()).intValueExact();
        } catch (ArithmeticException ex) {
            throw rejected("COST_OVERFLOW");
        }
    }

    private int calculateUpperBound(VerifiedRateCard verifiedCard, VerifiedUsageBinding verifiedUsage) {
        SignedRateCard card = verifiedCard.card;
        TokenUsageBounds bounds = verifiedUsage.bounds;
        BigDecimal promptRate = card.rates().get(SignedRateCard.Dimension.PROMPT)
                .max(card.rates().get(SignedRateCard.Dimension.CACHED));
        BigDecimal candidateRate = card.rates().get(SignedRateCard.Dimension.CANDIDATE)
                .max(card.rates().get(SignedRateCard.Dimension.THINKING));
        BigDecimal raw = charge(bounds.promptMax(), promptRate).add(charge(bounds.candidateMax(), candidateRate));
        try {
            return raw.setScale(0, RoundingMode.CEILING).intValueExact();
        } catch (ArithmeticException ex) {
            throw rejected("COST_OVERFLOW");
        }
    }

    private static BigDecimal charge(long tokens, BigDecimal rate) {
        return BigDecimal.valueOf(tokens).multiply(rate).divide(BigDecimal.valueOf(1_000));
    }

    private byte[] canonicalBinding(String cardDigest, RateCardExecutionInput input,
                                    CurrentAttemptBinding current, TokenUsageBounds bounds,
                                    PreflightCostEvidence preflight) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (String value : new String[]{cardDigest, input.attemptId(), input.idempotencyKey(),
                    input.tenantId(), input.traceId(), input.useCase(), input.attemptStatus(), input.dispatchNonce(),
                    input.provider(), input.modelRevision(), input.region(), input.tier(),
                    input.configHash(), input.preflightSnapshotHash(),
                    current.statusVersion() + "", boundValue(bounds, "prompt"), boundValue(bounds, "candidate"),
                    boundValue(bounds, "cached"), boundValue(bounds, "thinking"),
                    input.evaluatedAt().toString(), preflightDigest(preflight),
                    input.dispatchOutcome() == null ? "<missing>" : input.dispatchOutcome().state().name(),
                    input.dispatchOutcome() == null ? "<missing>" : input.dispatchOutcome().reasonCode(),
                    usageValue(input.usage(), "prompt"), usageValue(input.usage(), "candidate"),
                    usageValue(input.usage(), "total"), usageValue(input.usage(), "thinking"),
                    usageValue(input.usage(), "cached")}) write(out, value);
            out.flush();
            return bytes.toByteArray();
        } catch (Exception ex) {
            throw rejected("BINDING_CANONICALIZATION_FAILED");
        }
    }

    private static String boundValue(TokenUsageBounds bounds, String dimension) {
        if (bounds == null) return "<missing>";
        return switch (dimension) {
            case "prompt" -> String.valueOf(bounds.promptMax());
            case "candidate" -> String.valueOf(bounds.candidateMax());
            case "cached" -> String.valueOf(bounds.cachedMax());
            case "thinking" -> String.valueOf(bounds.thinkingMax());
            default -> throw new IllegalArgumentException("unknown bound dimension");
        };
    }

    private static String preflightDigest(PreflightCostEvidence evidence) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            for (String value : new String[]{evidence.policyId(), evidence.configIdentity(), evidence.proofIdentity(),
                    String.valueOf(evidence.upperBoundYen()), String.valueOf(evidence.budgetYen()),
                    String.valueOf(evidence.dispatchAllowed()), evidence.costState().name(),
                    evidence.dispatchOutcome().state().name(), evidence.dispatchOutcome().reasonCode(),
                    evidence.reasonCode()}) write(out, value);
            out.flush();
            return sha256(bytes.toByteArray());
        } catch (Exception ex) {
            throw rejected("PREFLIGHT_CANONICALIZATION_FAILED");
        }
    }

    static String preflightSnapshotDigest(PreflightCostEvidence evidence) {
        return preflightDigest(evidence);
    }

    static String canonicalCardDigest(SignedRateCard card) {
        return sha256(canonicalCard(card));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception ex) {
            throw rejected("DIGEST_UNAVAILABLE");
        }
    }

    private static void write(DataOutputStream out, String value) throws Exception {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static boolean validText(String value, int maxLength) {
        return !blank(value) && value.length() <= maxLength;
    }

    private static boolean validReason(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,64}");
    }

    private static String usageValue(CopilotProviderUsage usage, String dimension) {
        if (usage == null) return "<no-usage>";
        return switch (dimension) {
            case "prompt" -> String.valueOf(usage.promptTokenCount());
            case "candidate" -> String.valueOf(usage.candidateTokenCount());
            case "total" -> String.valueOf(usage.totalTokenCount());
            case "thinking" -> String.valueOf(usage.thoughtsTokenCount());
            case "cached" -> String.valueOf(usage.cachedContentTokenCount());
            default -> throw new IllegalArgumentException("unknown token dimension");
        };
    }

    private static RateCardVerificationException rejected(String reasonCode) {
        return new RateCardVerificationException(reasonCode);
    }

    private record CachedEvaluation(String idempotencyKey, String bindingDigest, VerifiedCostEvaluation result) { }

    private record VerifiedRateCard(SignedRateCard card, String digest) { }

    private record VerifiedUsageBinding(RateCardExecutionInput input, CurrentAttemptBinding current,
                                        TokenUsageBounds bounds, PreflightCostEvidence preflight,
                                        CopilotProviderUsage usage,
                                        String digest) { }
}
