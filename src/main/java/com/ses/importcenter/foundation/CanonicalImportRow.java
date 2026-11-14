package com.ses.importcenter.foundation;
import java.util.Map;
public sealed interface CanonicalImportRow permits CustomerImportRow, EngineerImportRow, ProjectImportRow, ProposalImportRow, ContractImportRow {
    ImportRowMetadata metadata();
    Map<String, FieldValue<String>> fields();
    Map<String, Object> typedValues();
    ImportEntity entity();
}
