package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.WorkRecordGridDto;
import com.ses.dto.closing.MonthlyClosingSummaryDto;
import com.ses.dto.invoice.InvoiceBalanceDto;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import com.ses.entity.MonthlyClosing;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.MonthlyClosingMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MonthlyClosingServiceImplTest {

    @Mock private WorkRecordMapper workRecordMapper;
    @Mock private InvoiceMapper invoiceMapper;
    @Mock private BpPaymentMapper bpPaymentMapper;
    @Mock private MonthlyClosingMapper monthlyClosingMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private com.ses.service.compliance.LaborComplianceService laborComplianceService;
    @Mock private com.ses.service.MenuCacheService menuCacheService;
    @Mock private org.springframework.beans.factory.ObjectProvider<com.ses.service.MenuCacheService> menuCacheServiceProvider;
    @Mock private com.ses.service.MonthlyAccountingSnapshotService monthlyAccountingSnapshotService;

    @InjectMocks
    private MonthlyClosingServiceImpl service;

    @BeforeEach
    void wireSnapshotService() {
        AccountingTenantContextHolder.setTenantId("default");
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "monthlyAccountingSnapshotService", monthlyAccountingSnapshotService);
    }

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private MonthlyClosing openRow(String month) {
        MonthlyClosing row = new MonthlyClosing();
        row.setTenantId("default");
        row.setWorkMonth(month);
        row.setVersion(0);
        return row;
    }

    private MonthlyClosing closedRow(String month, Long by) {
        MonthlyClosing row = openRow(month);
        row.setConfirmedBy(by);
        row.setConfirmedAt(LocalDateTime.of(2026, 7, 1, 10, 0));
        return row;
    }

    private void stubEmptyAll() {
        lenient().when(workRecordMapper.selectMonthlyGrid(anyString(), anyString(), eq("default")))
                .thenReturn(Collections.emptyList());
        lenient().when(workRecordMapper.selectUnconfirmedByWorkMonthForTenant(anyString(), eq("default")))
                .thenReturn(Collections.emptyList());
        lenient().when(invoiceMapper.selectUnbilledWorkRecordsAll(anyString(), eq("default")))
                .thenReturn(Collections.emptyList());
        lenient().when(bpPaymentMapper.selectListWithDetailsForTenant(anyString(), any(), eq("default")))
                .thenReturn(Collections.emptyList());
        lenient().when(invoiceMapper.selectOutstandingBalancesForTenant(eq("default")))
                .thenReturn(Collections.emptyList());
        lenient().when(monthlyClosingMapper.ensureRow(eq("default"), anyString())).thenReturn(1);
        lenient().when(monthlyClosingMapper.selectForUpdate(eq("default"), anyString()))
                .thenAnswer(inv -> openRow(inv.getArgument(1)));
        lenient().when(monthlyClosingMapper.selectByTenantAndMonth(eq("default"), anyString()))
                .thenReturn(null);
        lenient().when(monthlyClosingMapper.confirmCas(eq("default"), anyString(), any(), any(), any()))
                .thenReturn(1);
        lenient().when(monthlyClosingMapper.reopenCas(eq("default"), anyString(), any())).thenReturn(1);
    }

    @Test
    void summary_detectsEachItem() {
        WorkRecordGridDto entered = new WorkRecordGridDto();
        entered.setWorkRecordId(5L);
        WorkRecordGridDto unentered = new WorkRecordGridDto();
        unentered.setWorkRecordId(null);
        lenient().when(workRecordMapper.selectMonthlyGrid(anyString(), anyString(), eq("default")))
                .thenReturn(List.of(entered, unentered));
        WorkRecord wr = new WorkRecord();
        wr.setBillingAmount(new BigDecimal("1000"));
        lenient().when(workRecordMapper.selectUnconfirmedByWorkMonthForTenant(anyString(), eq("default")))
                .thenReturn(List.of(wr));
        UnbilledWorkRecordDto unbilled = new UnbilledWorkRecordDto();
        unbilled.setBillingAmount(new BigDecimal("2000"));
        lenient().when(invoiceMapper.selectUnbilledWorkRecordsAll(anyString(), eq("default")))
                .thenReturn(List.of(unbilled));
        lenient().when(bpPaymentMapper.selectListWithDetailsForTenant(anyString(), eq("未払"), eq("default")))
                .thenReturn(List.of(new com.ses.dto.invoice.BpPaymentListDto()));
        InvoiceBalanceDto overdue = new InvoiceBalanceDto();
        overdue.setDueDate(LocalDate.now().minusDays(5));
        overdue.setBalance(new BigDecimal("1000"));
        overdue.setStatus("送付済");
        InvoiceBalanceDto notDue = new InvoiceBalanceDto();
        notDue.setDueDate(LocalDate.now().plusDays(5));
        notDue.setStatus("送付済");
        lenient().when(invoiceMapper.selectOutstandingBalancesForTenant(eq("default")))
                .thenReturn(List.of(overdue, notDue));
        lenient().when(monthlyClosingMapper.selectByTenantAndMonth(eq("default"), anyString()))
                .thenReturn(null);

        MonthlyClosingSummaryDto s = service.summary("2026-06");

        assertEquals(1, s.getUnenteredCount());
        assertEquals(1, s.getUnconfirmedCount());
        assertEquals(1, s.getUnbilledCount());
        assertEquals(1, s.getUnpaidBpCount());
        assertEquals(1, s.getOverdueCount(), "期限内は除外され超過のみ計上");
        assertFalse(s.isReadyToClose());
        assertFalse(s.isClosed());
    }

    @Test
    void summary_readyWhenAllZero_eveIfOverdueRemains() {
        stubEmptyAll();
        InvoiceBalanceDto overdue = new InvoiceBalanceDto();
        overdue.setDueDate(LocalDate.now().minusDays(1));
        overdue.setStatus("送付済");
        lenient().when(invoiceMapper.selectOutstandingBalancesForTenant(eq("default")))
                .thenReturn(List.of(overdue));

        MonthlyClosingSummaryDto s = service.summary("2026-06");
        assertTrue(s.isReadyToClose(), "(e)期限超過は締めを妨げない");
        assertEquals(1, s.getOverdueCount());
    }

    @Test
    void confirm_recordsWhenReady() {
        stubEmptyAll();
        lenient().when(sysUserMapper.selectById(any())).thenReturn(new com.ses.entity.SysUser());

        service.confirmClosing("2026-06", 7L, "管理者");

        verify(monthlyClosingMapper).ensureRow("default", "2026-06");
        verify(monthlyClosingMapper).selectForUpdate("default", "2026-06");
        verify(monthlyAccountingSnapshotService).snapshotMonth("2026-06");
        verify(monthlyClosingMapper).confirmCas(eq("default"), eq("2026-06"), eq(7L), any(), eq(0));
    }

    @Test
    void confirm_notReadyThrows() {
        stubEmptyAll();
        WorkRecord wr = new WorkRecord();
        wr.setBillingAmount(new BigDecimal("100"));
        lenient().when(workRecordMapper.selectUnconfirmedByWorkMonthForTenant(anyString(), eq("default")))
                .thenReturn(List.of(wr));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmClosing("2026-06", 7L, "管理者"));
        assertTrue(ex.getMessage().contains("error.closing.notReady"));
        verify(monthlyClosingMapper, never()).confirmCas(any(), any(), any(), any(), any());
    }

    @Test
    void confirm_hrRoleDenied() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmClosing("2026-06", 7L, "HR"));
        assertTrue(ex.getMessage().contains("error.closing.roleDenied"));
    }

    @Test
    void confirm_invalidMonth() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmClosing("2026/6", 7L, "管理者"));
        assertTrue(ex.getMessage().contains("error.date.invalidYearMonth"));
    }

    @Test
    void isClosed_reflectsRecord() {
        when(monthlyClosingMapper.selectByTenantAndMonth("default", "2026-06"))
                .thenReturn(closedRow("2026-06", 7L));
        when(monthlyClosingMapper.selectByTenantAndMonth("default", "2026-05"))
                .thenReturn(openRow("2026-05"));

        assertTrue(service.isClosed("2026-06"));
        assertFalse(service.isClosed("2026-05"));
    }

    @Test
    void reopen_removesRecord() {
        when(monthlyClosingMapper.ensureRow("default", "2026-06")).thenReturn(1);
        when(monthlyClosingMapper.selectForUpdate("default", "2026-06"))
                .thenReturn(closedRow("2026-06", 7L));
        when(monthlyClosingMapper.reopenCas("default", "2026-06", 0)).thenReturn(1);

        service.reopenClosing("2026-06", 7L, "マネージャー");

        verify(monthlyClosingMapper).reopenCas("default", "2026-06", 0);
    }

    @Test
    void reopen_notClosedThrows() {
        when(monthlyClosingMapper.ensureRow("default", "2026-06")).thenReturn(1);
        when(monthlyClosingMapper.selectForUpdate("default", "2026-06"))
                .thenReturn(openRow("2026-06"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reopenClosing("2026-06", 7L, "管理者"));
        assertTrue(ex.getMessage().contains("error.closing.notClosed"));
    }

    @Test
    void missingTenant_failClosed() {
        AccountingTenantContextHolder.clear();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.isClosed("2026-06"));
        assertTrue(ex.getMessage().contains("TENANT_CONTEXT_REQUIRED"));
    }

    /** 指定ロールでログイン中の状態にする。 */
    private void loginAs(String role) {
        lenient().when(menuCacheServiceProvider.getIfAvailable()).thenReturn(menuCacheService);
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "tester", "n/a",
                        List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_" + role))));
    }

    private com.ses.dto.compliance.ContractComplianceDto sampleRisk() {
        com.ses.dto.compliance.ContractComplianceDto risk = new com.ses.dto.compliance.ContractComplianceDto();
        risk.setContractId(1L);
        risk.setFindings(List.of(new com.ses.dto.compliance.ComplianceFinding(
                "TIER_EXCEEDED", "warning", "段数超過", 1L)));
        return risk;
    }

    @Test
    void summary_コンプライアンスfindingsを提示し締めを妨げない() {
        stubEmptyAll();
        loginAs("管理者");
        lenient().when(laborComplianceService.findCurrentRisks()).thenReturn(List.of(sampleRisk()));

        MonthlyClosingSummaryDto s = service.summary("2026-06");

        assertEquals(1, s.getComplianceCount());
        assertTrue(s.isReadyToClose(), "コンプライアンスリスクは締めを妨げない");
    }

    /**
     * 月次締めメニューはHRにも開放されているが compliance メニューは管理者/マネージャー限定。
     * 締め画面経由でHRがリスク一覧を閲覧できてしまわないことを保証する。
     */
    @Test
    void summary_compliance権限が無いロールにはfindingsを返さない() {
        stubEmptyAll();
        loginAs("HR");
        when(menuCacheService.getMenuKeysByRole("HR")).thenReturn(List.of("monthly-closing"));
        lenient().when(laborComplianceService.findCurrentRisks()).thenReturn(List.of(sampleRisk()));

        MonthlyClosingSummaryDto s = service.summary("2026-06");

        assertEquals(0, s.getComplianceCount());
        assertTrue(s.getComplianceFindings().isEmpty());
        verify(laborComplianceService, never()).findCurrentRisks();
    }

    @Test
    void summary_compliance権限を持つマネージャーにはfindingsを返す() {
        stubEmptyAll();
        loginAs("マネージャー");
        when(menuCacheService.getMenuKeysByRole("マネージャー")).thenReturn(List.of("monthly-closing", "compliance"));
        lenient().when(laborComplianceService.findCurrentRisks()).thenReturn(List.of(sampleRisk()));

        MonthlyClosingSummaryDto s = service.summary("2026-06");

        assertEquals(1, s.getComplianceCount());
    }
}
