package com.ses.importcenter.foundation;
import java.util.Map;
public record EngineerImportRow(ImportRowMetadata metadata, Map<String, FieldValue<String>> fields,
                                Map<String, Object> typedValues) implements CanonicalImportRow {
    public EngineerImportRow { fields = Map.copyOf(fields); typedValues = Map.copyOf(typedValues); }
    public EngineerImportRow(ImportRowMetadata metadata, Map<String, FieldValue<String>> fields) { this(metadata, fields, Map.of()); }
    @Override public ImportEntity entity() { return ImportEntity.ENGINEER; }
}
