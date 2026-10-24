package com.ses.importcenter.foundation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** validate段階のcounterのみを計算する。apply結果を推測しない。 */
public final class ValidationCounterAccumulator {
    private static final int MAX_AMOUNT_VALUE_DIGITS = 18;
    private static final int MAX_AMOUNT_SUM_DIGITS = 38;
    private static final int MAX_SUMMARY_BYTES = 8192;
    private static final String FINDING_SUMMARY_DOMAIN = "SES-IMPORT-FINDING-COUNTS-V1";
    private static final String AMOUNT_REASON_SUMMARY_DOMAIN = "SES-IMPORT-AMOUNT-EXCLUSION-COUNTS-V1";

    private long source;
    private long accepted;
    private long rejected;
    private long amountExcluded;
    private BigDecimal acceptedAmount = BigDecimal.ZERO;
    private BigDecimal rejectedAmount = BigDecimal.ZERO;
    private final Map<String, Long> excludedReasons = new HashMap<>();
    private final Map<String, BigDecimal> acceptedAmountsByField = new HashMap<>();
    private final Map<String, BigDecimal> rejectedAmountsByField = new HashMap<>();
    private final Map<String, Long> findingCodeCounts = new HashMap<>();
    private final Map<String, MutableAmountCounter> amountCounters = new LinkedHashMap<>();

    public ValidationCounterAccumulator() {
        ImportValidationCodebook.allAmountFields().forEach(field -> amountCounters.put(field, new MutableAmountCounter()));
    }

    public void record(ValidatedRow row) {
        if (row == null || row.row() == null) throw new IllegalArgumentException("validated rowが必要です");
        long nextSource = addExact(source, 1);
        long nextAccepted = accepted;
        long nextRejected = rejected;
        if (row.accepted()) nextAccepted = addExact(accepted, 1);
        else nextRejected = addExact(rejected, 1);

        Set<String> rowFindingCodes = new HashSet<>();
        for (ImportFinding finding : row.findings()) {
            if (finding == null || !ImportValidationCodebook.isFindingCode(finding.code())) {
                throw new IllegalArgumentException("unknown finding codeです");
            }
            rowFindingCodes.add(finding.code());
        }
        for (String code : rowFindingCodes) {
            addExact(findingCodeCounts.getOrDefault(code, 0L), 1);
        }

        Set<String> applicableFields = Set.copyOf(ImportValidationCodebook.amountFields(row.row().entity()));
        Map<String, BigDecimal> known = row.knownAmounts();
        Map<String, String> excluded = row.excludedAmountReasons();
        Set<String> providedFields = new HashSet<>(known.keySet());
        for (String field : excluded.keySet()) {
            if (!providedFields.add(field)) throw new IllegalArgumentException("known/excluded amountが重複しています");
        }
        if (!providedFields.equals(applicableFields)) {
            throw new IllegalArgumentException("適用amount fieldはknownまたはexcludedの一方で必要です");
        }

        known.forEach((field, amount) -> validateKnownUpdate(field, amount, row.accepted()));
        excluded.forEach((field, reason) -> validateExcludedUpdate(field, reason));

        source = nextSource;
        accepted = nextAccepted;
        rejected = nextRejected;
        for (String code : rowFindingCodes) findingCodeCounts.merge(code, 1L, ValidationCounterAccumulator::addExact);

        known.forEach((field, amount) -> recordKnown(field, amount, row.accepted()));
        excluded.forEach((field, reason) -> recordExcluded(field, reason));
    }

    private void validateKnownUpdate(String field, BigDecimal amount, boolean isAccepted) {
        if (amount == null || amount.scale() != 0 || amount.signum() < 0
                || amount.precision() > MAX_AMOUNT_VALUE_DIGITS) {
            throw new IllegalArgumentException("JPY amountは18桁以内の非負整数である必要があります");
        }
        MutableAmountCounter counter = amountCounters.get(field);
        if (counter == null) throw new IllegalArgumentException("amount fieldがschema外です");
        counter.validateKnown(amount, isAccepted);
        Map<String, BigDecimal> aggregate = isAccepted ? acceptedAmountsByField : rejectedAmountsByField;
        checkedSum(aggregate.getOrDefault(field, BigDecimal.ZERO), amount);
    }

    private void validateExcludedUpdate(String field, String reason) {
        MutableAmountCounter counter = amountCounters.get(field);
        if (counter == null || !ImportValidationCodebook.isAmountExcludedReason(reason)) {
            throw new IllegalArgumentException("unknown amount exclusion field/reasonです");
        }
        counter.validateExcluded(reason);
        addExact(amountExcluded, 1);
        addExact(excludedReasons.getOrDefault(field + ":" + reason, 0L), 1);
    }

    private void recordKnown(String field, BigDecimal amount, boolean isAccepted) {
        if (amount == null || amount.scale() != 0 || amount.signum() < 0
                || amount.precision() > MAX_AMOUNT_VALUE_DIGITS) {
            throw new IllegalArgumentException("JPY amountは18桁以内の非負整数である必要があります");
        }
        MutableAmountCounter counter = amountCounters.get(field);
        if (counter == null) throw new IllegalArgumentException("amount fieldがschema外です");
        counter.addKnown(amount, isAccepted);
        if (isAccepted) {
            acceptedAmount = acceptedAmount.add(amount);
            acceptedAmountsByField.merge(field, amount, ValidationCounterAccumulator::checkedSum);
        } else {
            rejectedAmount = rejectedAmount.add(amount);
            rejectedAmountsByField.merge(field, amount, ValidationCounterAccumulator::checkedSum);
        }
    }

    private void recordExcluded(String field, String reason) {
        MutableAmountCounter counter = amountCounters.get(field);
        if (counter == null || !ImportValidationCodebook.isAmountExcludedReason(reason)) {
            throw new IllegalArgumentException("unknown amount exclusion field/reasonです");
        }
        counter.addExcluded(reason);
        amountExcluded = addExact(amountExcluded, 1);
        excludedReasons.merge(field + ":" + reason, 1L, ValidationCounterAccumulator::addExact);
    }

    public Snapshot snapshot(long emptySkipped) {
        if (emptySkipped < 0) throw new IllegalArgumentException("empty row countは負数にできません");
        long physical = addExact(source, emptySkipped);
        Map<String, AmountCounter> immutableAmountCounters = new LinkedHashMap<>();
        amountCounters.forEach((field, counter) -> immutableAmountCounters.put(field, counter.snapshot()));
        byte[] findingBytes = encodeFindingCounts(findingCodeCounts);
        byte[] amountReasonBytes = encodeAmountExcludedReasonCounts(immutableAmountCounters);
        return new Snapshot(physical, source, accepted, rejected, emptySkipped,
                acceptedAmount, rejectedAmount, amountExcluded, excludedReasons,
                acceptedAmountsByField, rejectedAmountsByField, findingCodeCounts, immutableAmountCounters,
                findingBytes, sha256(findingBytes), amountReasonBytes, sha256(amountReasonBytes));
    }

    private static byte[] encodeFindingCounts(Map<String, Long> counts) {
        return encode(out -> {
            writeBytes(out, FINDING_SUMMARY_DOMAIN.getBytes(StandardCharsets.US_ASCII));
            out.writeInt(1);
            TreeMap<String, Long> sorted = new TreeMap<>(counts);
            out.writeInt(sorted.size());
            for (var entry : sorted.entrySet()) {
                if (!ImportValidationCodebook.isFindingCode(entry.getKey()) || entry.getValue() <= 0) {
                    throw new IllegalArgumentException("finding summary entryが不正です");
                }
                writeBytes(out, entry.getKey().getBytes(StandardCharsets.US_ASCII));
                out.writeLong(entry.getValue());
            }
        });
    }

    private static byte[] encodeAmountExcludedReasonCounts(Map<String, AmountCounter> counters) {
        return encode(out -> {
            writeBytes(out, AMOUNT_REASON_SUMMARY_DOMAIN.getBytes(StandardCharsets.US_ASCII));
            out.writeInt(1);
            List<String> fields = ImportValidationCodebook.allAmountFields().stream().sorted().toList();
            int entries = counters.values().stream().mapToInt(value -> value.excludedReasonCounts().size()).sum();
            out.writeInt(entries);
            for (String field : fields) {
                AmountCounter counter = counters.get(field);
                if (counter == null) throw new IllegalArgumentException("amount summary fieldが不足しています");
                for (var reason : new TreeMap<>(counter.excludedReasonCounts()).entrySet()) {
                    if (!ImportValidationCodebook.isAmountExcludedReason(reason.getKey()) || reason.getValue() <= 0) {
                        throw new IllegalArgumentException("amount reason summary entryが不正です");
                    }
                    writeBytes(out, field.getBytes(StandardCharsets.US_ASCII));
                    writeBytes(out, reason.getKey().getBytes(StandardCharsets.US_ASCII));
                    out.writeLong(reason.getValue());
                }
            }
        });
    }

    private static byte[] encode(IoEncoder encoder) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) { encoder.write(out); }
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_SUMMARY_BYTES) throw new IllegalArgumentException("summaryが8192 bytesを超えています");
            return result;
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeBytes(DataOutputStream out, byte[] value) throws IOException {
        out.writeInt(value.length);
        out.write(value);
    }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static BigDecimal checkedSum(BigDecimal left, BigDecimal right) {
        BigDecimal sum = left.add(right);
        if (sum.scale() != 0 || sum.signum() < 0 || sum.precision() > MAX_AMOUNT_SUM_DIGITS) {
            throw new IllegalArgumentException("amount sumがDECIMAL(38,0)範囲外です");
        }
        return sum;
    }

    private static long addExact(long current, long delta) {
        return checkedCounterAdd(current, delta);
    }

    static long checkedCounterAdd(long current, long delta) { return Math.addExact(current, delta); }

    @FunctionalInterface
    private interface IoEncoder { void write(DataOutputStream out) throws IOException; }

    private static final class MutableAmountCounter {
        private long acceptedKnownCount;
        private long rejectedKnownCount;
        private long excludedCount;
        private BigDecimal acceptedSum = BigDecimal.ZERO;
        private BigDecimal rejectedSum = BigDecimal.ZERO;
        private final Map<String, Long> reasons = new HashMap<>();

        private void addKnown(BigDecimal value, boolean isAccepted) {
            if (isAccepted) {
                acceptedKnownCount = addExact(acceptedKnownCount, 1);
                acceptedSum = checkedSum(acceptedSum, value);
            } else {
                rejectedKnownCount = addExact(rejectedKnownCount, 1);
                rejectedSum = checkedSum(rejectedSum, value);
            }
        }

        private void validateKnown(BigDecimal value, boolean isAccepted) {
            checkedSum(isAccepted ? acceptedSum : rejectedSum, value);
            addExact(isAccepted ? acceptedKnownCount : rejectedKnownCount, 1);
        }

        private void addExcluded(String reason) {
            excludedCount = addExact(excludedCount, 1);
            reasons.merge(reason, 1L, ValidationCounterAccumulator::addExact);
        }

        private void validateExcluded(String reason) { addExact(reasons.getOrDefault(reason, 0L), 1); }

        private AmountCounter snapshot() {
            return new AmountCounter(acceptedKnownCount, acceptedKnownCount == 0 ? null : acceptedSum,
                    rejectedKnownCount, rejectedKnownCount == 0 ? null : rejectedSum,
                    excludedCount, reasons);
        }
    }

    public record AmountCounter(long acceptedKnownCount, BigDecimal acceptedSumYen,
                                long rejectedKnownCount, BigDecimal rejectedSumYen,
                                long excludedCount, Map<String, Long> excludedReasonCounts) {
        public AmountCounter {
            if (acceptedKnownCount < 0 || rejectedKnownCount < 0 || excludedCount < 0
                    || (acceptedKnownCount == 0) != (acceptedSumYen == null)
                    || (rejectedKnownCount == 0) != (rejectedSumYen == null)) {
                throw new IllegalArgumentException("amount count/sumのnull契約に違反しています");
            }
            if (acceptedSumYen != null) validateSum(acceptedSumYen);
            if (rejectedSumYen != null) validateSum(rejectedSumYen);
            excludedReasonCounts = Map.copyOf(excludedReasonCounts);
            long reasonTotal = 0;
            for (var entry : excludedReasonCounts.entrySet()) {
                if (!ImportValidationCodebook.isAmountExcludedReason(entry.getKey()) || entry.getValue() <= 0) {
                    throw new IllegalArgumentException("amount reason countが不正です");
                }
                reasonTotal = addExact(reasonTotal, entry.getValue());
            }
            if (reasonTotal != excludedCount) throw new IllegalArgumentException("excluded countとreason countが一致しません");
        }

        private static void validateSum(BigDecimal value) {
            if (value.scale() != 0 || value.signum() < 0 || value.precision() > MAX_AMOUNT_SUM_DIGITS) {
                throw new IllegalArgumentException("amount sumがDECIMAL(38,0)範囲外です");
            }
        }
    }

    public record Snapshot(long physicalRows, long sourceRows, long validatedAcceptedTotal, long rejected,
                           long emptySkipped, BigDecimal validatedAcceptedAmount, BigDecimal rejectedAmount,
                           long amountExcluded, Map<String, Long> amountExcludedReasons,
                           Map<String, BigDecimal> validatedAcceptedAmountsByField,
                           Map<String, BigDecimal> rejectedAmountsByField,
                           Map<String, Long> findingCodeCounts,
                           Map<String, AmountCounter> amountCountersByField,
                           byte[] findingSummaryBytes, byte[] findingSummarySha256,
                           byte[] amountExcludedReasonCountsBytes, byte[] amountExcludedReasonSha256) {
        public Snapshot {
            amountExcludedReasons = Map.copyOf(amountExcludedReasons);
            validatedAcceptedAmountsByField = Map.copyOf(validatedAcceptedAmountsByField);
            rejectedAmountsByField = Map.copyOf(rejectedAmountsByField);
            findingCodeCounts = Map.copyOf(findingCodeCounts);
            Map<String, AmountCounter> copiedCounters = new LinkedHashMap<>();
            amountCountersByField.forEach((field, counter) -> copiedCounters.put(field,
                    new AmountCounter(counter.acceptedKnownCount(), counter.acceptedSumYen(),
                            counter.rejectedKnownCount(), counter.rejectedSumYen(),
                            counter.excludedCount(), counter.excludedReasonCounts())));
            amountCountersByField = Map.copyOf(copiedCounters);
            findingSummaryBytes = findingSummaryBytes.clone();
            findingSummarySha256 = findingSummarySha256.clone();
            amountExcludedReasonCountsBytes = amountExcludedReasonCountsBytes.clone();
            amountExcludedReasonSha256 = amountExcludedReasonSha256.clone();
            if (sourceRows < 0 || validatedAcceptedTotal < 0 || rejected < 0 || emptySkipped < 0
                    || physicalRows != addExact(sourceRows, emptySkipped)
                    || sourceRows != addExact(validatedAcceptedTotal, rejected)
                    || findingSummaryBytes.length > MAX_SUMMARY_BYTES
                    || amountExcludedReasonCountsBytes.length > MAX_SUMMARY_BYTES
                    || findingSummarySha256.length != 32 || amountExcludedReasonSha256.length != 32
                    || !MessageDigest.isEqual(findingSummarySha256, sha256(findingSummaryBytes))
                    || !MessageDigest.isEqual(amountExcludedReasonSha256, sha256(amountExcludedReasonCountsBytes))
                    || !amountCountersByField.keySet().equals(Set.copyOf(ImportValidationCodebook.allAmountFields()))) {
                throw new IllegalArgumentException("validate counter/proof契約に違反しています");
            }
            Map<String, Long> reconstructedReasons = new HashMap<>();
            Map<String, BigDecimal> reconstructedAccepted = new HashMap<>();
            Map<String, BigDecimal> reconstructedRejected = new HashMap<>();
            long reconstructedExcluded = 0;
            for (var fieldEntry : amountCountersByField.entrySet()) {
                String field = fieldEntry.getKey();
                AmountCounter counter = fieldEntry.getValue();
                reconstructedExcluded = addExact(reconstructedExcluded, counter.excludedCount());
                counter.excludedReasonCounts().forEach((reason, count) ->
                        reconstructedReasons.put(field + ":" + reason, count));
                if (counter.acceptedKnownCount() > 0) reconstructedAccepted.put(field, counter.acceptedSumYen());
                if (counter.rejectedKnownCount() > 0) reconstructedRejected.put(field, counter.rejectedSumYen());
            }
            if (reconstructedExcluded != amountExcluded || !reconstructedReasons.equals(amountExcludedReasons)
                    || !reconstructedAccepted.equals(validatedAcceptedAmountsByField)
                    || !reconstructedRejected.equals(rejectedAmountsByField)
                    || !sum(reconstructedAccepted).equals(validatedAcceptedAmount)
                    || !sum(reconstructedRejected).equals(rejectedAmount)
                    || findingCodeCounts.entrySet().stream().anyMatch(entry ->
                    !ImportValidationCodebook.isFindingCode(entry.getKey()) || entry.getValue() == null || entry.getValue() <= 0)
                    || !MessageDigest.isEqual(findingSummaryBytes, encodeFindingCounts(findingCodeCounts))
                    || !MessageDigest.isEqual(amountExcludedReasonCountsBytes,
                    encodeAmountExcludedReasonCounts(amountCountersByField))) {
                throw new IllegalArgumentException("validate counter集約の整合性に違反しています");
            }
        }

        @Override public byte[] findingSummaryBytes() { return findingSummaryBytes.clone(); }
        @Override public byte[] findingSummarySha256() { return findingSummarySha256.clone(); }
        @Override public byte[] amountExcludedReasonCountsBytes() { return amountExcludedReasonCountsBytes.clone(); }
        @Override public byte[] amountExcludedReasonSha256() { return amountExcludedReasonSha256.clone(); }

        public boolean sameValues(Snapshot other) {
            return other != null && physicalRows == other.physicalRows && sourceRows == other.sourceRows
                    && validatedAcceptedTotal == other.validatedAcceptedTotal && rejected == other.rejected
                    && emptySkipped == other.emptySkipped && validatedAcceptedAmount.equals(other.validatedAcceptedAmount)
                    && rejectedAmount.equals(other.rejectedAmount) && amountExcluded == other.amountExcluded
                    && amountExcludedReasons.equals(other.amountExcludedReasons)
                    && validatedAcceptedAmountsByField.equals(other.validatedAcceptedAmountsByField)
                    && rejectedAmountsByField.equals(other.rejectedAmountsByField)
                    && findingCodeCounts.equals(other.findingCodeCounts)
                    && amountCountersByField.equals(other.amountCountersByField)
                    && MessageDigest.isEqual(findingSummaryBytes, other.findingSummaryBytes)
                    && MessageDigest.isEqual(findingSummarySha256, other.findingSummarySha256)
                    && MessageDigest.isEqual(amountExcludedReasonCountsBytes, other.amountExcludedReasonCountsBytes)
                    && MessageDigest.isEqual(amountExcludedReasonSha256, other.amountExcludedReasonSha256);
        }

        public long applyPending() { return validatedAcceptedTotal; }
        public BigDecimal applyPendingAmount() { return validatedAcceptedAmount; }
        public Map<String, BigDecimal> applyPendingAmountsByField() { return validatedAcceptedAmountsByField; }
    }

    private static BigDecimal sum(Map<String, BigDecimal> values) {
        return values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
