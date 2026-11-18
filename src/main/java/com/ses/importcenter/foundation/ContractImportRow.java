package com.ses.importcenter.foundation;
import java.util.Map;
public record ContractImportRow(ImportRowMetadata metadata, Map<String, FieldValue<String>> fields,
                               Map<String, Object> typedValues) implements CanonicalImportRow {
    public ContractImportRow { fields = Map.copyOf(fields); typedValues = Map.copyOf(typedValues); }
    public ContractImportRow(ImportRowMetadata metadata, Map<String, FieldValue<String>> fields) { this(metadata, fields, Map.of()); }
    @Override public ImportEntity entity() { return ImportEntity.CONTRACT; }
}
