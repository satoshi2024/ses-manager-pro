package com.ses.service.accounting;

import com.ses.common.exception.BusinessException;
import com.ses.entity.IntegrationConnection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AccountingProviderFactory {

    private final Map<String, AccountingProvider> providerMap;

    public AccountingProvider getProvider(IntegrationConnection connection) {
        if (connection == null || connection.getProvider() == null) {
            throw new BusinessException(400, "接続マスタまたはプロバイダが設定されていません");
        }
        String providerKey = connection.getProvider().toLowerCase() + "AccountingProvider";
        AccountingProvider provider = providerMap.get(providerKey);
        if (provider == null) {
            // "csv" -> "csvAccountingExportProvider"
            provider = providerMap.get(connection.getProvider().toLowerCase() + "AccountingExportProvider");
        }
        if (provider == null) {
            throw new BusinessException(400, "未対応の会計プロバイダです: " + connection.getProvider());
        }
        return provider;
    }
}
