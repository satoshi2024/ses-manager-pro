package com.ses.importcenter.foundation;
import java.util.Map;
public record CustomerImportRow(ImportRowMetadata metadata, Map<String, FieldValue<String>> fields,
                                Map<String, Object> typedValues) implements CanonicalImportRow {
    public CustomerImportRow { fields = Map.copyOf(fields); typedValues = Map.copyOf(typedValues); }
    public CustomerImportRow(ImportRowMetadata metadata, Map<String, FieldValue<String>> fields) { this(metadata, fields, Map.of()); }
    @Override public ImportEntity entity() { return ImportEntity.CUSTOMER; }
}
