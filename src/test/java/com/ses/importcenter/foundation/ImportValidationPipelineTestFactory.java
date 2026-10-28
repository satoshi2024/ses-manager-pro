package com.ses.importcenter.foundation;

import java.util.function.BiFunction;

/** metadata integration testからだけ使うpipeline converter seam。 */
public final class ImportValidationPipelineTestFactory {
    private ImportValidationPipelineTestFactory() { }

    public static ImportValidationPipeline withConverter(
            SesCsvParser parser, ImportValidationService validator,
            BiFunction<ImportEntity, SourceRow, CanonicalImportRow> converter) {
        return new ImportValidationPipeline(parser, validator, converter);
    }
}
