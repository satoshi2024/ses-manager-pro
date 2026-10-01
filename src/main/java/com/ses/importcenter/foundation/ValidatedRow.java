package com.ses.importcenter.foundation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record ValidatedRow(CanonicalImportRow row, List<ImportFinding> findings,
                           Map<String, BigDecimal> knownAmounts,
                           Map<String, String> excludedAmountReasons) {
    public ValidatedRow {
        findings = List.copyOf(findings);
        knownAmounts = Map.copyOf(knownAmounts);
        excludedAmountReasons = Map.copyOf(excludedAmountReasons);
    }
    public ValidatedRow(CanonicalImportRow row, List<ImportFinding> findings, BigDecimal knownAmount, String excludedReason) {
        this(row, findings, knownAmount == null ? Map.of() : Map.of("TOTAL", knownAmount),
                excludedReason == null ? Map.of() : Map.of("TOTAL", excludedReason));
    }
    public boolean accepted() { return findings.isEmpty(); }
    public BigDecimal knownAmount() { return knownAmounts.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add); }
    public String amountExcludedReason() { return excludedAmountReasons.values().stream().findFirst().orElse(null); }
}
