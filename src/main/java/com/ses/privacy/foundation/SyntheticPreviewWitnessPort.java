package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.Map;

/** current独立source read。実装はtest fixture内のみ。 */
interface SyntheticPreviewWitnessPort {
    java.util.Set<String> SOURCE_NAMES = java.util.Set.of("identity", "scope", "providerRegistry", "policy", "hold",
            "audit", "blocker", "journal", "witness", "case");
    java.util.Set<String> ALLOWED_WITNESS_KEYS = java.util.Set.of("identityRevision", "identityHash", "keyId", "audience",
            "identityExpiresAt", "identityRevocation", "scopeRevision", "scopeHash", "providerId", "providerAudience",
            "policyVersion", "policyHash", "policyEffective", "policyExpiresAt", "policyRevocation", "policyState",
            "holdRevision", "holdHash", "holdState", "auditRevision", "auditHash", "auditState", "blockerRevision",
            "blockerHash", "journalRevision", "journalHash", "journalState", "witnessEpoch", "witnessRevision",
            "checkpointHash", "witnessKeyState", "caseRevision", "tenantId", "subjectRef", "requesterId", "requestId",
            "caseRef", "scopeId", "scopeState");
    SourceSnapshot readCurrent(String tenantId, String requesterId, String subjectRef, String requestId,
                               String caseRef, String scopeId);
    record SourceSnapshot(Map<String, String> values, java.util.List<String> allowedTargetIds,
                          Map<String, Instant> sourceObservedAt, Instant observedAt) {
        public SourceSnapshot {
            values = Map.copyOf(values);
            allowedTargetIds = java.util.List.copyOf(allowedTargetIds);
            sourceObservedAt = sourceObservedAt.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> SyntheticCanonicalizer.toMicros(entry.getValue())));
            observedAt = SyntheticCanonicalizer.toMicros(observedAt);
            if (observedAt == null) throw new IllegalArgumentException("observedAtが必要です");
            if (!sourceObservedAt.keySet().equals(SOURCE_NAMES) || sourceObservedAt.values().stream().anyMatch(java.util.Objects::isNull))
                throw new IllegalArgumentException("sourceごとのobservedAtが必要です");
            if (allowedTargetIds.isEmpty() || allowedTargetIds.stream().anyMatch(id -> id == null
                    || !id.matches("target-synthetic-[a-zA-Z0-9-]+"))
                    || allowedTargetIds.size() != allowedTargetIds.stream().distinct().count())
                throw new IllegalArgumentException("synthetic scope target一覧が不正です");
            if (!ALLOWED_WITNESS_KEYS.containsAll(values.keySet())) throw new IllegalArgumentException("opaque witness以外のfieldは禁止です");
            for (String key : java.util.List.of("identityRevision", "identityHash", "keyId", "audience",
                    "identityExpiresAt", "identityRevocation", "scopeRevision", "scopeHash", "providerId",
                    "providerAudience", "policyVersion", "policyHash", "policyEffective", "policyExpiresAt",
                    "policyRevocation", "holdRevision", "holdHash", "holdState", "auditRevision", "auditHash",
                    "blockerRevision", "blockerHash", "journalRevision", "journalHash", "witnessEpoch",
                    "witnessRevision", "checkpointHash", "witnessKeyState", "caseRevision", "tenantId",
                    "subjectRef", "requesterId", "requestId", "caseRef", "scopeId", "policyState", "journalState", "auditState", "scopeState")) {
                if (!values.containsKey(key) || values.get(key) == null) throw new IllegalArgumentException("source witness不足: " + key);
            }
        }
        String canonical() {
            StringBuilder out = new StringBuilder("observedAt=").append(observedAt).append('\n');
            values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> out.append(e.getKey())
                    .append('=').append(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                            e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8))).append('\n'));
            allowedTargetIds.stream().sorted().forEach(id -> out.append("allowedTarget=")
                    .append(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                            id.getBytes(java.nio.charset.StandardCharsets.UTF_8))).append('\n'));
            sourceObservedAt.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> out.append("sourceObservedAt=").append(e.getKey()).append(':').append(e.getValue()).append('\n'));
            return out.toString();
        }
        String hash() { return ApprovalEnvelopeCanonicalizer.digest(canonical().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        String bindingHash() {
            String canonical = values.entrySet().stream().sorted(Map.Entry.comparingByKey()).map(e -> e.getKey() + "="
                    + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .collect(java.util.stream.Collectors.joining("\n")) + "\nallowedTargets="
                    + allowedTargetIds.stream().sorted().collect(java.util.stream.Collectors.joining("\n"));
            return ApprovalEnvelopeCanonicalizer.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
