package com.ses.service.ai.copilot.scope;

import com.ses.service.ai.copilot.digest.CopilotDigest;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * scope 許可集合の canonical 入力から scopeHash を生成する。
 *
 * <p>canonical 形式（UTF-8、各行 {@code key=value}、キーは辞書順固定）:
 * <pre>
 * asOf=yyyy-MM-dd
 * contractIds=&lt;sorted unique ids, comma-separated&gt;
 * customerIds=...
 * directUserIds=...
 * engineerIds=...
 * legalEntity=&lt;tenant legal entity id or empty&gt;
 * organizationIds=...
 * policyVersion=...
 * scopeType=...
 * tenant=...
 * </pre>
 * 空集合は値を空文字。同一 ID が異なる次元に存在しても次元名で区別される。
 */
public final class CopilotScopeHash {

    private CopilotScopeHash() {
    }

    public static String hash(
            String tenantId,
            String legalEntityId,
            String scopeType,
            String policyVersion,
            LocalDate asOf,
            Set<Long> customerIds,
            Set<Long> contractIds,
            Set<Long> engineerIds,
            Set<Long> organizationIds,
            Set<Long> directUserIds) {
        return CopilotDigest.sha256(canonicalInput(
                tenantId,
                legalEntityId,
                scopeType,
                policyVersion,
                asOf,
                customerIds,
                contractIds,
                engineerIds,
                organizationIds,
                directUserIds));
    }

    static String canonicalInput(
            String tenantId,
            String legalEntityId,
            String scopeType,
            String policyVersion,
            LocalDate asOf,
            Set<Long> customerIds,
            Set<Long> contractIds,
            Set<Long> engineerIds,
            Set<Long> organizationIds,
            Set<Long> directUserIds) {
        StringBuilder sb = new StringBuilder(256);
        appendLine(sb, "asOf", asOf.toString());
        appendLine(sb, "contractIds", formatIds(contractIds));
        appendLine(sb, "customerIds", formatIds(customerIds));
        appendLine(sb, "directUserIds", formatIds(directUserIds));
        appendLine(sb, "engineerIds", formatIds(engineerIds));
        appendLine(sb, "legalEntity", legalEntityId == null ? "" : legalEntityId);
        appendLine(sb, "organizationIds", formatIds(organizationIds));
        appendLine(sb, "policyVersion", policyVersion);
        appendLine(sb, "scopeType", scopeType);
        appendLine(sb, "tenant", tenantId);
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String key, String value) {
        sb.append(key).append('=').append(value).append('\n');
    }

    private static String formatIds(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }
}
