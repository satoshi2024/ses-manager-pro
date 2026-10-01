package com.ses.importcenter.foundation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 合成契約ses-import-v1の固定header manifest。 */
public final class SesImportV1Schema {
    public static final String VERSION = "ses-import-v1";
    private static final Map<ImportEntity, Definition> DEFINITIONS = definitions();

    private SesImportV1Schema() { }

    public static Definition definition(ImportEntity entity) { return DEFINITIONS.get(entity); }

    private static Map<ImportEntity, Definition> definitions() {
        Map<ImportEntity, Definition> result = new LinkedHashMap<>();
        result.put(ImportEntity.CUSTOMER, new Definition(
                List.of("externalKey", "companyName", "companyNameKana", "contactPerson", "contactEmail", "contactPhone", "address", "commercialFlow", "trustLevel", "deliveryPreference", "remarks"),
                Set.of("externalKey", "companyName"), Set.of("companyName", "companyNameKana", "contactPerson", "contactEmail", "contactPhone", "address", "commercialFlow", "trustLevel", "deliveryPreference", "remarks")));
        result.put(ImportEntity.ENGINEER, new Definition(
                List.of("externalKey", "fullName", "fullNameKana", "initialName", "gender", "employmentType", "status", "expectedUnitPrice", "birthDate", "experienceYears", "nearestStation", "japaneseLevel", "remarks", "organizationExternalKey", "costCenterExternalKey"),
                Set.of("externalKey", "fullName"), Set.of("gender", "employmentType", "status", "expectedUnitPrice", "birthDate", "experienceYears")));
        result.put(ImportEntity.PROJECT, new Definition(
                List.of("externalKey", "customerExternalKey", "projectName", "commercialFlow", "description", "requiredCount", "unitPriceMin", "unitPriceMax", "workLocation", "remoteType", "startDate", "endDate", "status", "priority", "remarks"),
                Set.of("externalKey", "customerExternalKey", "projectName"), Set.of("requiredCount", "unitPriceMin", "unitPriceMax", "startDate", "endDate")));
        result.put(ImportEntity.PROPOSAL, new Definition(
                List.of("externalKey", "engineerExternalKey", "projectExternalKey", "positionExternalKey", "status", "proposedAt", "remarks"),
                Set.of("externalKey", "engineerExternalKey", "projectExternalKey"), Set.of("status", "proposedAt")));
        result.put(ImportEntity.CONTRACT, new Definition(
                List.of("externalKey", "customerExternalKey", "engineerExternalKey", "projectExternalKey", "startDate", "endDate", "sellingPrice", "costPrice", "status", "contractType", "remarks"),
                Set.of("externalKey", "customerExternalKey", "engineerExternalKey", "projectExternalKey", "startDate", "sellingPrice", "costPrice"), Set.of("startDate", "endDate", "sellingPrice", "costPrice")));
        return Map.copyOf(result);
    }

    public record Definition(List<String> allowedHeaders, Set<String> requiredHeaders, Set<String> typedHeaders) {
        public Definition {
            allowedHeaders = List.copyOf(allowedHeaders);
            requiredHeaders = Set.copyOf(requiredHeaders);
            typedHeaders = Set.copyOf(typedHeaders);
        }
    }
}
