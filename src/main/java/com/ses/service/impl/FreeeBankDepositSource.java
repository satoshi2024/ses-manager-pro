package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.reconciliation.BankDepositDto;
import com.ses.entity.IntegrationConnection;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.provider.FreeeAccountingProvider;
import com.ses.service.billing.BankDepositSource;
import com.ses.service.integration.IntegrationConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * FR-09: freee会計の口座入金明細（wallet_txns）を、会計連携の接続（m_integration_connection）で取得する。
 *
 * <p>以前は給与（人事労務）の接続 {@code t_freee_connection} のtokenで会計APIを呼んでいた。給与と会計は
 * 別々に認可・別事業所になり得るため、会計データは会計接続から読む。売上送信と同じ全社共通接続を使う。</p>
 */
@Component
@RequiredArgsConstructor
public class FreeeBankDepositSource implements BankDepositSource {

    private final IntegrationConnectionService connectionService;
    private final FreeeAccountingProvider freeeAccountingProvider;

    @Override
    public List<BankDepositDto> fetch(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            throw BusinessException.of(400, "error.reconciliation.invalidPeriod");
        }
        IntegrationConnection connection = connectionService.getConnection(AccountingTenantContextHolder.requireTenantContext(), null, "freee", "accounting");
        if (connection == null || !"CONNECTED".equals(connection.getStatus())
                || connection.getExternalCompanyId() == null) {
            throw BusinessException.of("error.reconciliation.notConnected");
        }
        return freeeAccountingProvider.fetchBankDeposits(connection, from, to);
    }
}
