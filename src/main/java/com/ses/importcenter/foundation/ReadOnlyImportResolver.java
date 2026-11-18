package com.ses.importcenter.foundation;

/** validate専用interface。実装はscope内SELECTのみを許可し、write methodを持たない。 */
@FunctionalInterface
public interface ReadOnlyImportResolver {
    ReferenceLookup resolve(ReferenceType entity, String tenantId, String legalEntityId, String normalizedExternalKey);
}
