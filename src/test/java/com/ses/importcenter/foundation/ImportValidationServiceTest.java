package com.ses.importcenter.foundation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ImportValidationServiceTest {
    private final ImportValidationService validator = new ImportValidationService();

    @Test
    void createsFiveCanonicalTypesAndAcceptsPositiveSyntheticRows() throws Exception {
        for (ImportEntity entity : ImportEntity.values()) {
            List<SourceRow> parsed = parse(entity, SesImportV1FixtureFactory.allPositive(entity));
            CanonicalImportRow row = CanonicalRowFactory.create(entity, parsed.get(0));
            assertEquals(entity, row.entity());
            Class<?> expectedType = switch (entity) {
                case CUSTOMER -> CustomerImportRow.class;
                case ENGINEER -> EngineerImportRow.class;
                case PROJECT -> ProjectImportRow.class;
                case PROPOSAL -> ProposalImportRow.class;
                case CONTRACT -> ContractImportRow.class;
            };
            assertTrue(expectedType.isInstance(row), entity.name());
            assertTrue(validator.validate(row, context(null), new ImportValidationSession()).accepted(), entity.name());
        }
    }

    @Test
    void distinguishesMissingExplicitNullAndEmptyString() throws Exception {
        List<SourceRow> missingHeader = parse(ImportEntity.CUSTOMER, "externalKey,companyName\nC-SYN-1,架空\n");
        List<SourceRow> explicitNull = parse(ImportEntity.CUSTOMER, "externalKey,companyName,remarks\nC-SYN-2,架空,\\N\n");
        List<SourceRow> empty = parse(ImportEntity.CUSTOMER, "externalKey,companyName,remarks\nC-SYN-3,架空,\n");
        assertEquals(FieldValue.Presence.NOT_PROVIDED, CanonicalRowFactory.create(ImportEntity.CUSTOMER, missingHeader.get(0)).fields().get("remarks").presence());
        assertEquals(FieldValue.Presence.EXPLICIT_NULL, CanonicalRowFactory.create(ImportEntity.CUSTOMER, explicitNull.get(0)).fields().get("remarks").presence());
        assertEquals(FieldValue.of(""), CanonicalRowFactory.create(ImportEntity.CUSTOMER, empty.get(0)).fields().get("remarks"));
    }

    @Test
    void preservesCaseNormalizesNfcAndRejectsTrimInsteadOfMergingKeys() {
        assertEquals("Café", ExternalKeyNormalizer.normalize("Cafe\u0301"));
        assertTrue(Normalizer.isNormalized(ExternalKeyNormalizer.normalize("Cafe\u0301"), Normalizer.Form.NFC));
        assertNotEquals(ExternalKeyNormalizer.normalize("Key-A"), ExternalKeyNormalizer.normalize("key-a"));
        assertThrows(IllegalArgumentException.class, () -> ExternalKeyNormalizer.normalize(" key-a"));
        assertThrows(IllegalArgumentException.class, () -> ExternalKeyNormalizer.normalize("key-a "));
        assertThrows(IllegalArgumentException.class, () -> ExternalKeyNormalizer.normalize("key\n-a"));
    }

    @Test
    void sourceDuplicateDetectionUsesNormalizedNfcButPreservesCase() throws Exception {
        List<SourceRow> rows = parse(ImportEntity.CUSTOMER,
                "externalKey,companyName\nCafe\u0301,架空1\nCafé,架空2\nCAFÉ,架空3\n");
        ImportValidationSession session = new ImportValidationSession();
        var first = validator.validate(CanonicalRowFactory.create(ImportEntity.CUSTOMER, rows.get(0)), context(null), session);
        var sameNfc = validator.validate(CanonicalRowFactory.create(ImportEntity.CUSTOMER, rows.get(1)), context(null), session);
        var differentCase = validator.validate(CanonicalRowFactory.create(ImportEntity.CUSTOMER, rows.get(2)), context(null), session);
        assertTrue(first.accepted());
        assertTrue(sameNfc.findings().stream().anyMatch(f -> f.code().equals("DUPLICATE_SOURCE_KEY")));
        assertTrue(differentCase.accepted());
    }

    @Test
    void rejectsInvalidInitialStatesMissingReferencesRelationshipMismatchAndDuplicateKeys() throws Exception {
        ImportValidationSession session = new ImportValidationSession();
        CanonicalImportRow engineer = CanonicalRowFactory.create(ImportEntity.ENGINEER,
                parse(ImportEntity.ENGINEER, "externalKey,fullName,employmentType,status\nE-SYN-1,架空太郎,正社員,稼動中\n").get(0));
        assertTrue(validator.validate(engineer, context(null), session).findings().stream().anyMatch(f -> f.code().equals("STATE_OR_ENUM_NOT_ALLOWED")));
        CanonicalImportRow duplicate = CanonicalRowFactory.create(ImportEntity.ENGINEER,
                parse(ImportEntity.ENGINEER, "externalKey,fullName,employmentType\nE-SYN-1,別人,正社員\n").get(0));
        assertTrue(validator.validate(duplicate, context(null), session).findings().stream().anyMatch(f -> f.code().equals("DUPLICATE_SOURCE_KEY")));

        CanonicalImportRow contract = CanonicalRowFactory.create(ImportEntity.CONTRACT,
                parse(ImportEntity.CONTRACT, "externalKey,customerExternalKey,engineerExternalKey,projectExternalKey,startDate,sellingPrice,costPrice\nCT-SYN-1,C-SYN-2,E-SYN-1,P-SYN-1,2026-01-01,100,80\n").get(0));
        assertTrue(validator.validate(contract, context("C-SYN-1"), new ImportValidationSession()).findings().stream()
                .anyMatch(f -> f.code().equals("RELATIONSHIP_MISMATCH")));
        assertTrue(validator.validate(contract, context(null, LookupStatus.NOT_FOUND), new ImportValidationSession()).findings().stream()
                .anyMatch(f -> f.code().equals("REFERENCE_NOT_FOUND")));

        CanonicalImportRow proposal = CanonicalRowFactory.create(ImportEntity.PROPOSAL,
                parse(ImportEntity.PROPOSAL, "externalKey,engineerExternalKey,projectExternalKey,status\nPR-SYN-1,E-SYN-1,P-SYN-1,成約\n").get(0));
        assertTrue(validator.validate(proposal, context(null), new ImportValidationSession()).findings().stream()
                .anyMatch(f -> f.code().equals("STATE_OR_ENUM_NOT_ALLOWED")));
    }

    @Test
    void contractStartDateIsRequiredForBlankAndExplicitNullButAcceptsIsoDate() throws Exception {
        for (String cell : List.of("", "   ", "\\N")) {
            CanonicalImportRow contract = contractWithStartDate(cell);
            assertTrue(validator.validate(contract, context(null), new ImportValidationSession()).findings().stream()
                    .anyMatch(f -> f.code().equals("FIELD_REQUIRED")), "cell=" + cell);
        }
        CanonicalImportRow valid = contractWithStartDate("2026-01-01");
        assertFalse(validator.validate(valid, context(null), new ImportValidationSession()).findings().stream()
                .anyMatch(f -> f.code().equals("FIELD_REQUIRED") || f.code().equals("DATE_INVALID")));
    }

    @Test
    void proposalPositionMustBelongToResolvedProjectAndUnknownRelationFailsClosed() throws Exception {
        CanonicalImportRow proposal = CanonicalRowFactory.create(ImportEntity.PROPOSAL,
                parse(ImportEntity.PROPOSAL, "externalKey,engineerExternalKey,projectExternalKey,positionExternalKey\nPR-SYN-2,E-SYN-1,P-SYN-1,POS-SYN-1\n").get(0));
        assertFalse(validator.validate(proposal, contextWithPosition("P-SYN-1"), new ImportValidationSession()).findings().stream()
                .anyMatch(f -> f.code().startsWith("RELATIONSHIP_")));
        assertTrue(validator.validate(proposal, contextWithPosition("P-SYN-OTHER"), new ImportValidationSession()).findings().stream()
                .anyMatch(f -> f.code().equals("RELATIONSHIP_MISMATCH")));
        assertTrue(validator.validate(proposal, contextWithPosition(null), new ImportValidationSession()).findings().stream()
                .anyMatch(f -> f.code().equals("RELATIONSHIP_UNVERIFIABLE")));
    }

    @Test
    void jpyIsExactIntegerNoSilentRoundingOrEmptyToZero() {
        assertEquals(new BigDecimal("12"), ImportAmountParser.parse(FieldValue.of("12")).value());
        assertEquals(new BigDecimal("12"), ImportAmountParser.parse(FieldValue.of("00012.000")).value());
        assertEquals("AMOUNT_INVALID_OR_FRACTIONAL_YEN", ImportAmountParser.parse(FieldValue.of("12.5")).excludedReason());
        assertEquals("AMOUNT_INVALID_OR_FRACTIONAL_YEN", ImportAmountParser.parse(FieldValue.of("1E100000000")).excludedReason());
        assertEquals("AMOUNT_INVALID_OR_FRACTIONAL_YEN", ImportAmountParser.parse(FieldValue.of("-1E-100000000")).excludedReason());
        assertEquals("AMOUNT_NEGATIVE_NOT_ALLOWED", ImportAmountParser.parse(FieldValue.of("-12")).excludedReason());
        assertEquals("AMOUNT_UNKNOWN_OR_EMPTY", ImportAmountParser.parse(FieldValue.of("")).excludedReason());
        assertEquals("AMOUNT_UNKNOWN_OR_EMPTY", ImportAmountParser.parse(FieldValue.explicitNull()).excludedReason());
    }

    @Test
    void validationCountersUseFixedAcceptedBaseAndSeparateEmptyRows() throws Exception {
        ValidationCounterAccumulator counters = new ValidationCounterAccumulator();
        CanonicalImportRow row = CanonicalRowFactory.create(ImportEntity.CUSTOMER,
                parse(ImportEntity.CUSTOMER, "externalKey,companyName\nC-SYN-1,架空会社\n").get(0));
        counters.record(validator.validate(row, context(null), new ImportValidationSession()));
        ImportRowMetadata bad = new ImportRowMetadata(2, "hash-b", null);
        counters.record(new ValidatedRow(new CustomerImportRow(bad, Map.of()), List.of(
                new ImportFinding(2, "hash-b", "FIELD_REQUIRED", "required")), Map.of(), Map.of()));
        var snapshot = counters.snapshot(3);
        assertEquals(5, snapshot.physicalRows());
        assertEquals(2, snapshot.sourceRows());
        assertEquals(1, snapshot.validatedAcceptedTotal());
        assertEquals(1, snapshot.rejected());
        assertEquals(3, snapshot.emptySkipped());
        assertEquals(1, snapshot.applyPending());
    }

    @Test
    void errorCsvEscapesCellsAndNeutralizesFormulaText() throws Exception {
        StringWriter writer = new StringWriter();
        ImportErrorCsvWriter.writeHeader(writer);
        ImportErrorCsvWriter.write(writer, new ImportFinding(1, "abc", "=CMD", "=HYPERLINK(\"x\")"));
        assertTrue(writer.toString().contains("'=CMD"));
        assertTrue(writer.toString().contains("'=HYPERLINK("));
        assertTrue(writer.toString().contains("\"'=HYPERLINK(\"\"x\"\")\""));
        ImportErrorCsvWriter.write(writer, new ImportFinding(2, "def", "FIELD", "  =1+1"));
        assertTrue(writer.toString().contains("'  =1+1"));
        ImportErrorCsvWriter.write(writer, new ImportFinding(3, "ghi", "FIELD", "-12"));
        assertTrue(writer.toString().contains(",-12\r\n"));
    }

    @Test
    void canonicalDtoAndSchemaMappingAreImmutableAndRetainTypedValues() throws Exception {
        CanonicalImportRow contract = CanonicalRowFactory.create(ImportEntity.CONTRACT,
                parse(ImportEntity.CONTRACT, SesImportV1FixtureFactory.allPositive(ImportEntity.CONTRACT)).get(0));
        assertEquals(new BigDecimal("100"), contract.typedValues().get("sellingPrice"));
        assertEquals(java.time.LocalDate.parse("2026-01-01"), contract.typedValues().get("startDate"));
        assertThrows(UnsupportedOperationException.class, () -> contract.fields().put("remarks", FieldValue.of("mutate")));
        assertThrows(UnsupportedOperationException.class, () -> SesImportV1Schema.definition(ImportEntity.CONTRACT).allowedHeaders().add("unknown"));
    }

    private static List<SourceRow> parse(ImportEntity entity, String text) throws Exception {
        List<SourceRow> rows = new ArrayList<>();
        new SesCsvParser().parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), "fixture.csv",
                entity, ImportEncoding.UTF8, rows::add);
        return rows;
    }

    private static CanonicalImportRow contractWithStartDate(String startDate) throws Exception {
        return CanonicalRowFactory.create(ImportEntity.CONTRACT,
                parse(ImportEntity.CONTRACT, "externalKey,customerExternalKey,engineerExternalKey,projectExternalKey,startDate,sellingPrice,costPrice\nCT-SYN-DATE,C-SYN-1,E-SYN-1,P-SYN-1," + startDate + ",100,80\n").get(0));
    }

    private static ValidationContext context(String projectCustomer) { return context(projectCustomer, LookupStatus.UNIQUE_ACTIVE); }
    private static ValidationContext context(String projectCustomer, LookupStatus override) {
        return new ValidationContext("tenant-synthetic", "legal-synthetic", (type, tenant, legal, key) -> {
            if (override == LookupStatus.NOT_FOUND && "C-SYN-2".equals(key)) return ReferenceLookup.notFound();
            Map<String, String> relation = type == ReferenceType.PROJECT
                    ? Map.of("customerExternalKey", projectCustomer == null ? "C-SYN-1" : projectCustomer) : Map.of();
            ReferenceTarget target = new ReferenceTarget("id-" + key, tenant, legal, key, "version-1", true, relation);
            return new ReferenceLookup(override, List.of(target));
        });
    }

    private static ValidationContext contextWithPosition(String positionProject) {
        return new ValidationContext("tenant-synthetic", "legal-synthetic", (type, tenant, legal, key) -> {
            Map<String, String> relation = switch (type) {
                case PROJECT -> Map.of("customerExternalKey", "C-SYN-1");
                case POSITION -> positionProject == null ? Map.of() : Map.of("projectExternalKey", positionProject);
                default -> Map.of();
            };
            return new ReferenceLookup(LookupStatus.UNIQUE_ACTIVE,
                    List.of(new ReferenceTarget("id-" + key, tenant, legal, key, "version-1", true, relation)));
        });
    }
}
