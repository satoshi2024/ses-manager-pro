package com.ses.importcenter.foundation;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.function.Consumer;

/** parserからread-only検証までを連結する。DB/HTTP/domain service dependencyを持たない。 */
public final class ImportValidationPipeline {
    private final SesCsvParser parser;
    private final ImportValidationService validator;

    public ImportValidationPipeline(SesCsvParser parser, ImportValidationService validator) {
        this.parser = Objects.requireNonNull(parser);
        this.validator = Objects.requireNonNull(validator);
    }

    public RunSummary validate(InputStream input, String fileName, ImportEntity entity,
                               ImportEncoding encoding, ValidationContext context,
                               Consumer<ValidatedRow> rowConsumer) throws IOException {
        return validateInternal(input, fileName, entity, encoding, context, null, rowConsumer);
    }

    /** immutable mappingを明示適用し、同一のcanonical conversion/finding規則を使う。 */
    public RunSummary validateMapped(InputStream input, String fileName, ImportEntity entity,
                                     ImportEncoding encoding, java.util.Map<String, String> sourceToCanonical,
                                     ValidationContext context, Consumer<ValidatedRow> rowConsumer) throws IOException {
        if (sourceToCanonical == null || sourceToCanonical.isEmpty()) {
            throw new IllegalArgumentException("明示header mappingが必要です");
        }
        return validateInternal(input, fileName, entity, encoding, context,
                java.util.Map.copyOf(sourceToCanonical), rowConsumer);
    }

    private RunSummary validateInternal(InputStream input, String fileName, ImportEntity entity,
                                        ImportEncoding encoding, ValidationContext context,
                                        java.util.Map<String, String> sourceToCanonical,
                                        Consumer<ValidatedRow> rowConsumer) throws IOException {
        ValidationCounterAccumulator counters = new ValidationCounterAccumulator();
        ImportValidationSession session = new ImportValidationSession();
        java.util.function.Consumer<SourceRow> validateRow = sourceRow -> {
            CanonicalImportRow canonical;
            try { canonical = CanonicalRowFactory.create(entity, sourceRow); }
            catch (RuntimeException failure) {
                ImportRowMetadata metadata = new ImportRowMetadata(sourceRow.sourceRowNumber(), sourceRow.sourceRowHash(), null);
                canonical = emptyRow(entity, metadata);
                ValidatedRow rejected = new ValidatedRow(canonical, java.util.List.of(new ImportFinding(
                        metadata.sourceRowNumber(), metadata.sourceRowHash(), "CANONICAL_CONVERSION_FAILED", "canonical変換に失敗しました")),
                        java.util.Map.of(), java.util.Map.of());
                counters.record(rejected); rowConsumer.accept(rejected); return;
            }
            ValidatedRow result = validator.validate(canonical, context, session);
            counters.record(result);
            rowConsumer.accept(result);
        };
        SesCsvParser.ParseSummary parsed = sourceToCanonical == null
                ? parser.parse(input, fileName, entity, encoding, validateRow)
                : parser.parseMapped(input, fileName, entity, encoding, sourceToCanonical, validateRow);
        return new RunSummary(parsed, counters.snapshot(parsed.emptySkipped()));
    }

    private static CanonicalImportRow emptyRow(ImportEntity entity, ImportRowMetadata metadata) {
        return switch (entity) {
            case CUSTOMER -> new CustomerImportRow(metadata, java.util.Map.of());
            case ENGINEER -> new EngineerImportRow(metadata, java.util.Map.of());
            case PROJECT -> new ProjectImportRow(metadata, java.util.Map.of());
            case PROPOSAL -> new ProposalImportRow(metadata, java.util.Map.of());
            case CONTRACT -> new ContractImportRow(metadata, java.util.Map.of());
        };
    }

    public record RunSummary(SesCsvParser.ParseSummary source, ValidationCounterAccumulator.Snapshot counters) { }
}
