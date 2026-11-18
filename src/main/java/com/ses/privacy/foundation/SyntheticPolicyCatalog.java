package com.ses.privacy.foundation;

import java.util.Map;
import java.util.Optional;

/**
 * 照会パラメータを決定論的な policyId と expectedScope へマッピングする固定カタログ。
 */
interface SyntheticPolicyCatalog {
    Optional<CatalogEntry> resolve(String moduleCode, String dataElementId, String action, String providerId);

    record CatalogEntry(String policyId, String expectedScope) {}

    static SyntheticPolicyCatalog createDefault() {
        Map<String, CatalogEntry> entries = Map.of(
            "NF03_CERTIFICATION:CERTIFICATION_PII:DISPOSITION_PURGE:local-db",
                new CatalogEntry("policy-nf03-cert-v1", "CERTIFICATION"),
            "NF08_AI_SUMMARY:AI_PROMPT_LOG:DISPOSITION_PURGE:local-db",
                new CatalogEntry("policy-nf08-log-purge-v1", "AI_FEEDBACK"),
            "NF08_AI_SUMMARY:AI_PROMPT_LOG:EXTERNAL_DISPATCH:gemini-adapter",
                new CatalogEntry("policy-nf08-log-dispatch-v1", "AI_FEEDBACK")
        );
        return (moduleCode, dataElementId, action, providerId) -> {
            String key = moduleCode + ":" + dataElementId + ":" + action + ":" + providerId;
            return Optional.ofNullable(entries.get(key));
        };
    }
}
