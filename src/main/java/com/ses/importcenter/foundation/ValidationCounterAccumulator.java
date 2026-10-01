package com.ses.importcenter.foundation;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/** validate段階のcounterのみを計算する。apply結果を推測しない。 */
public final class ValidationCounterAccumulator {
    private long source;
    private long accepted;
    private long rejected;
    private long amountExcluded;
    private BigDecimal acceptedAmount = BigDecimal.ZERO;
    private BigDecimal rejectedAmount = BigDecimal.ZERO;
    private final Map<String, Long> excludedReasons = new HashMap<>();
    private final Map<String, BigDecimal> acceptedAmountsByField = new HashMap<>();
    private final Map<String, BigDecimal> rejectedAmountsByField = new HashMap<>();

    public void record(ValidatedRow row) {
        source++;
        if (row.accepted()) {
            accepted++;
            row.knownAmounts().forEach((field, amount) -> {
                acceptedAmount = acceptedAmount.add(amount);
                acceptedAmountsByField.merge(field, amount, BigDecimal::add);
            });
        } else {
            rejected++;
            row.knownAmounts().forEach((field, amount) -> {
                rejectedAmount = rejectedAmount.add(amount);
                rejectedAmountsByField.merge(field, amount, BigDecimal::add);
            });
        }
        row.excludedAmountReasons().forEach((field, reason) -> {
            amountExcluded++;
            excludedReasons.merge(field + ":" + reason, 1L, Long::sum);
        });
    }

    public Snapshot snapshot(long emptySkipped) {
        return new Snapshot(source + emptySkipped, source, accepted, rejected, emptySkipped,
                acceptedAmount, rejectedAmount, amountExcluded, Map.copyOf(excludedReasons),
                Map.copyOf(acceptedAmountsByField), Map.copyOf(rejectedAmountsByField));
    }

    public record Snapshot(long physicalRows, long sourceRows, long validatedAcceptedTotal, long rejected,
                           long emptySkipped, BigDecimal validatedAcceptedAmount, BigDecimal rejectedAmount,
                           long amountExcluded, Map<String, Long> amountExcludedReasons,
                           Map<String, BigDecimal> validatedAcceptedAmountsByField,
                           Map<String, BigDecimal> rejectedAmountsByField) {
        public Snapshot {
            amountExcludedReasons = Map.copyOf(amountExcludedReasons);
            validatedAcceptedAmountsByField = Map.copyOf(validatedAcceptedAmountsByField);
            rejectedAmountsByField = Map.copyOf(rejectedAmountsByField);
            if (sourceRows != validatedAcceptedTotal + rejected || physicalRows != sourceRows + emptySkipped) {
                throw new IllegalArgumentException("validate counter式に違反しています");
            }
        }
        public long applyPending() { return validatedAcceptedTotal; }
        public BigDecimal applyPendingAmount() { return validatedAcceptedAmount; }
        public Map<String, BigDecimal> applyPendingAmountsByField() { return validatedAcceptedAmountsByField; }
    }
}
