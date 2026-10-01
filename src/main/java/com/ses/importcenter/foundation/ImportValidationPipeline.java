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
        ValidationCounterAccumulator counters = new ValidationCounterAccumulator();
        ImportValidationSession session = new ImportValidationSession();
        SesCsvParser.ParseSummary parsed = parser.parse(input, fileName, entity, encoding, sourceRow -> {
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
        });
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
