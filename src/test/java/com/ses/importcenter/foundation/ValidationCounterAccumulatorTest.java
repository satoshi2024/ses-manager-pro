package com.ses.importcenter.foundation;

import org.junit.jupiter.api.Test;
import com.ses.importcenter.metadata.ImportValidationAppendService;
import com.ses.importcenter.metadata.ImportValidationLifecycleService;

import java.math.BigDecimal;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationCounterAccumulatorTest {
    @Test
    void accumulatesOnlyApplicableFieldsAndKeepsRejectedKnownAmounts() {
        ValidationCounterAccumulator counters = new ValidationCounterAccumulator();
        counters.record(row(ImportEntity.CUSTOMER, List.of(), Map.of(), Map.of()));
        counters.record(row(ImportEntity.ENGINEER, List.of(), Map.of("expectedUnitPrice", BigDecimal.ZERO), Map.of()));
        counters.record(row(ImportEntity.PROJECT, List.of(finding("FIELD_REQUIRED")),
                Map.of("unitPriceMin", new BigDecimal("120")), Map.of("unitPriceMax", "AMOUNT_UNKNOWN_OR_EMPTY")));
        counters.record(row(ImportEntity.PROJECT, List.of(finding("FIELD_REQUIRED")),
                Map.of("unitPriceMin", new BigDecimal("30")), Map.of("unitPriceMax", "AMOUNT_PRECISION_EXCEEDED")));
        counters.record(row(ImportEntity.CONTRACT, List.of(finding("FIELD_REQUIRED")),
                Map.of("sellingPrice", new BigDecimal("300"), "costPrice", new BigDecimal("100")), Map.of()));
        counters.record(row(ImportEntity.PROJECT, List.of(),
                Map.of("unitPriceMin", new BigDecimal("10"), "unitPriceMax", new BigDecimal("20")), Map.of()));

        ValidationCounterAccumulator.Snapshot snapshot = counters.snapshot(2);
        assertEquals(8, snapshot.physicalRows());
        assertEquals(6, snapshot.sourceRows());
        assertEquals(3, snapshot.validatedAcceptedTotal());
        assertEquals(3, snapshot.rejected());

        var expectedUnitPrice = snapshot.amountCountersByField().get("expectedUnitPrice");
        assertEquals(1, expectedUnitPrice.acceptedKnownCount());
        assertEquals(BigDecimal.ZERO, expectedUnitPrice.acceptedSumYen());
        assertEquals(0, expectedUnitPrice.rejectedKnownCount());
        assertNull(expectedUnitPrice.rejectedSumYen());

        var minimum = snapshot.amountCountersByField().get("unitPriceMin");
        assertEquals(1, minimum.acceptedKnownCount());
        assertEquals(new BigDecimal("10"), minimum.acceptedSumYen());
        assertEquals(2, minimum.rejectedKnownCount());
        assertEquals(new BigDecimal("150"), minimum.rejectedSumYen());

        var maximum = snapshot.amountCountersByField().get("unitPriceMax");
        assertEquals(1, maximum.acceptedKnownCount());
        assertEquals(new BigDecimal("20"), maximum.acceptedSumYen());
        assertEquals(2, maximum.excludedCount());
        assertEquals(Map.of("AMOUNT_UNKNOWN_OR_EMPTY", 1L, "AMOUNT_PRECISION_EXCEEDED", 1L),
                maximum.excludedReasonCounts());

        var selling = snapshot.amountCountersByField().get("sellingPrice");
        assertEquals(1, selling.rejectedKnownCount());
        assertEquals(new BigDecimal("300"), selling.rejectedSumYen());
        var cost = snapshot.amountCountersByField().get("costPrice");
        assertEquals(1, cost.rejectedKnownCount());
        assertEquals(new BigDecimal("100"), cost.rejectedSumYen());

        assertEquals(2, snapshot.amountExcluded());
        assertEquals(Map.of("unitPriceMax:AMOUNT_UNKNOWN_OR_EMPTY", 1L,
                "unitPriceMax:AMOUNT_PRECISION_EXCEEDED", 1L), snapshot.amountExcludedReasons());
        assertEquals(new BigDecimal("550"), snapshot.rejectedAmount());
        assertEquals(new BigDecimal("30"), snapshot.validatedAcceptedAmount());
    }

    @Test
    void countsEachFindingCodeOncePerRowAndAgainForAnotherRow() {
        ValidationCounterAccumulator counters = new ValidationCounterAccumulator();
        counters.record(row(ImportEntity.CUSTOMER,
                List.of(finding("FIELD_REQUIRED"), finding("FIELD_REQUIRED", "別message")), Map.of(), Map.of()));
        counters.record(row(ImportEntity.CUSTOMER, List.of(finding("FIELD_REQUIRED")), Map.of(), Map.of()));

        ValidationCounterAccumulator.Snapshot snapshot = counters.snapshot(0);
        assertEquals(Map.of("FIELD_REQUIRED", 2L), snapshot.findingCodeCounts());
        assertTrue(snapshot.findingSummaryBytes().length > 0);
        assertTrue(snapshot.findingSummaryBytes().length <= 8192);
        assertTrue(snapshot.amountExcludedReasonCountsBytes().length > 0);
        assertTrue(snapshot.amountExcludedReasonCountsBytes().length <= 8192);
        assertTrue(snapshot.sameValues(counters.snapshot(0)));
    }

    @Test
    void snapshotsUseContentEqualityAndHashingForSummaryArrays() {
        ValidationCounterAccumulator first = new ValidationCounterAccumulator();
        ValidationCounterAccumulator second = new ValidationCounterAccumulator();
        first.record(row(ImportEntity.CUSTOMER, List.of(finding("FIELD_REQUIRED")), Map.of(), Map.of()));
        second.record(row(ImportEntity.CUSTOMER, List.of(finding("FIELD_REQUIRED")), Map.of(), Map.of()));
        var original = first.snapshot(0);
        var equal = second.snapshot(0);
        assertEquals(original, equal);
        assertEquals(original.hashCode(), equal.hashCode());

        second.record(row(ImportEntity.CUSTOMER, List.of(finding("EMAIL_INVALID")), Map.of(), Map.of()));
        assertFalse(original.equals(second.snapshot(0)));

        byte[] findingBytes = original.findingSummaryBytes();
        byte[] findingHash = original.findingSummarySha256();
        byte[] reasonBytes = original.amountExcludedReasonCountsBytes();
        byte[] reasonHash = original.amountExcludedReasonSha256();
        var copied = new ValidationCounterAccumulator.Snapshot(original.physicalRows(), original.sourceRows(),
                original.validatedAcceptedTotal(), original.rejected(), original.emptySkipped(),
                original.validatedAcceptedAmount(), original.rejectedAmount(), original.amountExcluded(),
                original.amountExcludedReasons(), original.validatedAcceptedAmountsByField(),
                original.rejectedAmountsByField(), original.findingCodeCounts(), original.amountCountersByField(),
                findingBytes, findingHash, reasonBytes, reasonHash);
        findingBytes[0] ^= 1;
        findingHash[0] ^= 1;
        reasonBytes[0] ^= 1;
        reasonHash[0] ^= 1;
        assertEquals(original, copied, "constructor入力arrayはsnapshot内部へ漏れません");
        assertEquals(original.hashCode(), copied.hashCode());
    }

    @Test
    void emitsZeroForInapplicableFieldsAndNullSumWhenNoKnownAmountExists() {
        ValidationCounterAccumulator counters = new ValidationCounterAccumulator();
        counters.record(row(ImportEntity.PROPOSAL, List.of(), Map.of(), Map.of()));
        ValidationCounterAccumulator.Snapshot snapshot = counters.snapshot(0);

        assertEquals(5, snapshot.amountCountersByField().size());
        for (var amount : snapshot.amountCountersByField().values()) {
            assertEquals(0, amount.acceptedKnownCount());
            assertNull(amount.acceptedSumYen());
            assertEquals(0, amount.rejectedKnownCount());
            assertNull(amount.rejectedSumYen());
            assertEquals(0, amount.excludedCount());
        }
        assertEquals(BigDecimal.ZERO, snapshot.validatedAcceptedAmount());
    }

    @Test
    void pipelineConversionFailureCountsApplicableAmountAsUnknownAndRejected() throws Exception {
        ImportValidationPipeline pipeline = new ImportValidationPipeline(new SesCsvParser(), new ImportValidationService(),
                (entity, sourceRow) -> { throw new IllegalArgumentException("synthetic conversion failure"); });
        List<ValidatedRow> rows = new java.util.ArrayList<>();
        var result = pipeline.validate(new ByteArrayInputStream(
                        "externalKey,expectedUnitPrice\nengineer-1,120\n".getBytes(StandardCharsets.UTF_8)),
                "engineer.csv", ImportEntity.ENGINEER, ImportEncoding.UTF8, null, rows::add);

        assertEquals(1, rows.size());
        assertEquals("CANONICAL_CONVERSION_FAILED", rows.getFirst().findings().getFirst().code());
        assertFalse(rows.getFirst().accepted());
        assertEquals(Map.of(), rows.getFirst().knownAmounts());
        assertEquals(Map.of("expectedUnitPrice", "AMOUNT_UNKNOWN_OR_EMPTY"), rows.getFirst().excludedAmountReasons());
        var summary = result.counters();
        assertEquals(0, summary.validatedAcceptedTotal());
        assertEquals(1, summary.rejected());
        var amount = summary.amountCountersByField().get("expectedUnitPrice");
        assertEquals(0, amount.acceptedKnownCount());
        assertNull(amount.acceptedSumYen());
        assertEquals(1, amount.excludedCount());
        assertEquals(Map.of("AMOUNT_UNKNOWN_OR_EMPTY", 1L), amount.excludedReasonCounts());
    }

    @Test
    void rejectsUnknownOrMissingApplicableAmountAndKnownExcludedOverlap() {
        ValidationCounterAccumulator counters = new ValidationCounterAccumulator();
        assertThrows(IllegalArgumentException.class, () -> counters.record(row(ImportEntity.ENGINEER,
                List.of(), Map.of(), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> counters.record(row(ImportEntity.ENGINEER,
                List.of(), Map.of("expectedUnitPrice", BigDecimal.ONE), Map.of("expectedUnitPrice", "AMOUNT_UNKNOWN_OR_EMPTY"))));
        assertThrows(IllegalArgumentException.class, () -> counters.record(row(ImportEntity.ENGINEER,
                List.of(), Map.of("expectedUnitPrice", new BigDecimal("1000000000000000000")), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> counters.record(row(ImportEntity.ENGINEER,
                List.of(), Map.of("expectedUnitPrice", new BigDecimal("-1")), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> counters.record(row(ImportEntity.ENGINEER,
                List.of(), Map.of("expectedUnitPrice", new BigDecimal("1.5")), Map.of())));
        assertThrows(IllegalArgumentException.class, () -> counters.record(row(ImportEntity.ENGINEER,
                List.of(), Map.of(), Map.of("expectedUnitPrice", "NOT_A_CLOSED_REASON"))));
        assertThrows(IllegalArgumentException.class, () -> counters.record(row(ImportEntity.CUSTOMER,
                List.of(finding("NOT_A_CLOSED_FINDING")), Map.of(), Map.of())));
    }

    @Test
    void amountCounterEnforcesDecimal38AndSummaryByteArraysAreDefensiveCopies() {
        String largest38DigitSum = "9".repeat(38);
        var valid = new ValidationCounterAccumulator.AmountCounter(1, new BigDecimal(largest38DigitSum),
                0, null, 0, Map.of());
        assertEquals(1, valid.acceptedKnownCount());
        assertThrows(IllegalArgumentException.class, () -> new ValidationCounterAccumulator.AmountCounter(
                1, new BigDecimal("1" + "0".repeat(38)), 0, null, 0, Map.of()));

        ValidationCounterAccumulator accumulator = new ValidationCounterAccumulator();
        accumulator.record(row(ImportEntity.CUSTOMER, List.of(), Map.of(), Map.of()));
        var snapshot = accumulator.snapshot(0);
        byte[] findingBytes = snapshot.findingSummaryBytes();
        byte[] amountBytes = snapshot.amountExcludedReasonCountsBytes();
        findingBytes[0] ^= 0x7f;
        amountBytes[0] ^= 0x7f;
        assertFalse(java.util.Arrays.equals(findingBytes, snapshot.findingSummaryBytes()));
        assertFalse(java.util.Arrays.equals(amountBytes, snapshot.amountExcludedReasonCountsBytes()));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.amountCountersByField().put("other", valid));
        assertThrows(UnsupportedOperationException.class,
                () -> snapshot.amountCountersByField().get("expectedUnitPrice")
                        .excludedReasonCounts().put("AMOUNT_UNKNOWN_OR_EMPTY", 1L));
        Map<String, Long> constructorInput = new HashMap<>();
        constructorInput.put("AMOUNT_UNKNOWN_OR_EMPTY", 1L);
        var copied = new ValidationCounterAccumulator.AmountCounter(0, null, 0, null, 1, constructorInput);
        constructorInput.clear();
        assertEquals(Map.of("AMOUNT_UNKNOWN_OR_EMPTY", 1L), copied.excludedReasonCounts());
    }

    @Test
    void checkedCounterAdditionRejectsLongOverflow() {
        assertThrows(ArithmeticException.class,
                () -> ValidationCounterAccumulator.checkedCounterAdd(Long.MAX_VALUE, 1));
    }

    @Test
    void onlyAppendServiceCanConstructSealProofAndLegacyMethodStillReturnsLease() throws Exception {
        Class<?> capability = Class.forName(
                "com.ses.importcenter.metadata.ImportValidationAppendService$SealCapability");
        assertTrue(java.lang.reflect.Modifier.isPrivate(capability.getDeclaredConstructors()[0].getModifiers()));
        assertEquals(ImportValidationLifecycleService.RunLease.class,
                ImportValidationAppendService.class.getMethod("appendRemaining",
                        ImportValidationLifecycleService.RunLease.class).getReturnType());
    }

    private static ValidatedRow row(ImportEntity entity, List<ImportFinding> findings,
                                    Map<String, BigDecimal> known, Map<String, String> excluded) {
        ImportRowMetadata metadata = new ImportRowMetadata(1, "0".repeat(64), "synthetic");
        CanonicalImportRow canonical = switch (entity) {
            case CUSTOMER -> new CustomerImportRow(metadata, Map.of());
            case ENGINEER -> new EngineerImportRow(metadata, Map.of());
            case PROJECT -> new ProjectImportRow(metadata, Map.of());
            case PROPOSAL -> new ProposalImportRow(metadata, Map.of());
            case CONTRACT -> new ContractImportRow(metadata, Map.of());
        };
        return new ValidatedRow(canonical, findings, known, excluded);
    }

    private static ImportFinding finding(String code) { return finding(code, "synthetic message"); }

    private static ImportFinding finding(String code, String message) {
        return new ImportFinding(1, "0".repeat(64), code, message);
    }
}
