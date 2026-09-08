package com.ses.service.accounting;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/** 定期処理が処理対象にできるテナントの明示的なinventory設定。 */
@ConfigurationProperties(prefix = "app.accounting.tenants")
public class AccountingTenantInventoryProperties {

    private Set<String> ids = new LinkedHashSet<>();

    public Set<String> getIds() {
        return ids;
    }

    public void setIds(Set<String> ids) {
        this.ids = ids == null ? new LinkedHashSet<>() : new LinkedHashSet<>(ids);
    }

    public Set<String> normalizedIds() {
        return ids.stream().filter(value -> value != null && !value.isBlank())
                .map(String::trim).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    /** 定期処理の対象tenantが未設定の場合は、空実行を許可せず起動・実行を停止する。 */
    public Set<String> requireNormalizedIds() {
        Set<String> normalized = normalizedIds();
        if (normalized.isEmpty()) {
            throw new IllegalStateException(
                    "ACCOUNTING_TENANT_IDSには1件以上のtenant IDをカンマ区切りで設定してください");
        }
        return normalized;
    }
}
