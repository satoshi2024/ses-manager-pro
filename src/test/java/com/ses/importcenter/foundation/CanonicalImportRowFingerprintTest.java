package com.ses.importcenter.foundation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CanonicalImportRowFingerprintTest {
    @Test
    void fieldOrderAndSourcePositionDoNotChangeCanonicalFingerprint() {
        Map<String, FieldValue<String>> firstFields = new LinkedHashMap<>();
        firstFields.put("companyName", FieldValue.of("Example"));
        firstFields.put("remarks", FieldValue.missing());
        Map<String, FieldValue<String>> reorderedFields = new LinkedHashMap<>();
        reorderedFields.put("remarks", FieldValue.missing());
        reorderedFields.put("companyName", FieldValue.of("Example"));

        byte[] first = CanonicalImportRowFingerprint.sha256(new CustomerImportRow(
                new ImportRowMetadata(1, "11".repeat(32), "customer-1"), firstFields));
        byte[] reordered = CanonicalImportRowFingerprint.sha256(new CustomerImportRow(
                new ImportRowMetadata(19, "22".repeat(32), "customer-1"), reorderedFields));

        assertArrayEquals(first, reordered);
    }

    @Test
    void fieldValueAndPresenceChangesChangeCanonicalFingerprint() {
        byte[] value = CanonicalImportRowFingerprint.sha256(new CustomerImportRow(
                new ImportRowMetadata(1, "11".repeat(32), "customer-1"),
                Map.of("companyName", FieldValue.of("Example"))));
        byte[] changedValue = CanonicalImportRowFingerprint.sha256(new CustomerImportRow(
                new ImportRowMetadata(1, "11".repeat(32), "customer-1"),
                Map.of("companyName", FieldValue.of("Different"))));
        byte[] explicitNull = CanonicalImportRowFingerprint.sha256(new CustomerImportRow(
                new ImportRowMetadata(1, "11".repeat(32), "customer-1"),
                Map.of("companyName", FieldValue.explicitNull())));
        byte[] missing = CanonicalImportRowFingerprint.sha256(new CustomerImportRow(
                new ImportRowMetadata(1, "11".repeat(32), "customer-1"),
                Map.of("companyName", FieldValue.missing())));

        assertFalse(java.util.Arrays.equals(value, changedValue));
        assertFalse(java.util.Arrays.equals(explicitNull, missing));
    }
}
