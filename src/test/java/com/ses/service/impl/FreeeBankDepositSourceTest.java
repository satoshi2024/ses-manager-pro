package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.reconciliation.BankDepositDto;
import com.ses.entity.IntegrationConnection;
import com.ses.service.accounting.provider.FreeeAccountingProvider;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FR-09: 入金明細は会計接続（m_integration_connection）から取得する。
 * 給与（人事労務）の接続とは別に認可されるため、会計が未接続なら給与が接続済みでも取得しない。
 */
class FreeeBankDepositSourceTest {

    private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
    private static final LocalDate TO = LocalDate.of(2026, 11, 30);

    private final IntegrationConnectionService connectionService = mock(IntegrationConnectionService.class);
    private final FreeeAccountingProvider provider = mock(FreeeAccountingProvider.class);
    private final FreeeBankDepositSource source = new FreeeBankDepositSource(connectionService, provider);

    @BeforeEach
    void setTenant() {
        // 接続は要求のtenantで引く（全社 "default" 固定ではない）
        AccountingTenantContextHolder.setTenantId("default");
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    private IntegrationConnection connection(String status, Long companyId) {
        IntegrationConnection c = new IntegrationConnection();
        c.setId(1L);
        c.setStatus(status);
        c.setExternalCompanyId(companyId);
        when(connectionService.getConnection("default", null, "freee", "accounting")).thenReturn(c);
        return c;
    }

    @Test
    void 会計接続済みなら会計接続で入金明細を取得する() {
        IntegrationConnection c = connection("CONNECTED", 123L);
        List<BankDepositDto> expected = List.of(new BankDepositDto());
        when(provider.fetchBankDeposits(c, FROM, TO)).thenReturn(expected);

        assertSame(expected, source.fetch(FROM, TO));
    }

    @Test
    void 会計が未接続なら取得せず未接続エラー() {
        connection("DISCONNECTED", null);

        BusinessException ex = assertThrows(BusinessException.class, () -> source.fetch(FROM, TO));
        assertEquals("error.reconciliation.notConnected", ex.getMessage());
        verify(provider, never()).fetchBankDeposits(any(), any(), any());
    }

    @Test
    void 会計接続が無ければ未接続エラー() {
        when(connectionService.getConnection("default", null, "freee", "accounting")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> source.fetch(FROM, TO));
        assertEquals("error.reconciliation.notConnected", ex.getMessage());
    }

    @Test
    void 期間が逆転していれば400() {
        BusinessException ex = assertThrows(BusinessException.class, () -> source.fetch(TO, FROM));
        assertEquals(400, ex.getCode());
        assertEquals("error.reconciliation.invalidPeriod", ex.getMessage());
    }
}
