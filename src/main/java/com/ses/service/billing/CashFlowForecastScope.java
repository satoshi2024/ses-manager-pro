package com.ses.service.billing;

import java.time.LocalDate;
import java.util.List;

/**
 * 資金繰り予測へ渡す保存済み母集団。nullは全社、空集合は該当なしを表す。
 * report専用の集計条件ではなく、既存CashFlowForecastServiceの認可入力である。
 */
public record CashFlowForecastScope(
        boolean companyWide,
        List<Long> invoiceIds,
        List<Long> contractIds,
        List<Long> engineerIds,
        List<Long> organizationIds,
        List<Long> directUserIds,
        LocalDate asOf) {

    public CashFlowForecastScope {
        invoiceIds = invoiceIds == null ? List.of() : List.copyOf(invoiceIds);
        contractIds = contractIds == null ? List.of() : List.copyOf(contractIds);
        engineerIds = engineerIds == null ? List.of() : List.copyOf(engineerIds);
        organizationIds = organizationIds == null ? List.of() : List.copyOf(organizationIds);
        directUserIds = directUserIds == null ? List.of() : List.copyOf(directUserIds);
    }
}
