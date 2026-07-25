package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.WorkRecordGridDto;
import com.ses.dto.closing.MonthlyClosingSummaryDto;
import com.ses.dto.invoice.InvoiceBalanceDto;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
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
    @Mock private SystemConfigService systemConfigService;
    @Mock private SystemConfigMapper systemConfigMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private com.ses.service.compliance.LaborComplianceService laborComplianceService;
    @Mock private com.ses.service.MenuCacheService menuCacheService;
    @Mock private org.springframework.beans.factory.ObjectProvider<com.ses.service.MenuCacheService> menuCacheServiceProvider;

    @InjectMocks
    private MonthlyClosingServiceImpl service;

    private void stubEmptyAll() {
        lenient().when(workRecordMapper.selectMonthlyGrid(anyString(), anyString())).thenReturn(Collections.emptyList());
        lenient().when(workRecordMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(invoiceMapper.selectUnbilledWorkRecordsAll(anyString())).thenReturn(Collections.emptyList());
        lenient().when(bpPaymentMapper.selectListWithDetails(anyString(), any())).thenReturn(Collections.emptyList());
        lenient().when(invoiceMapper.selectOutstandingBalances()).thenReturn(Collections.emptyList());
        lenient().when(systemConfigMapper.selectByIdForUpdate(anyString())).thenReturn(new com.ses.entity.SystemConfig());
        lenient().when(systemConfigMapper.selectById(anyString())).thenReturn(new com.ses.entity.SystemConfig());
    }

    @Test
    void summary_detectsEachItem() {
        WorkRecordGridDto entered = new WorkRecordGridDto();
        entered.setWorkRecordId(5L);
        WorkRecordGridDto unentered = new WorkRecordGridDto();
        unentered.setWorkRecordId(null);
        lenient().when(workRecordMapper.selectMonthlyGrid(anyString(), anyString()))
                .thenReturn(List.of(entered, unentered));
        WorkRecord wr = new WorkRecord();
        wr.setBillingAmount(new BigDecimal("1000")); // fix NPE
        lenient().when(workRecordMapper.selectList(any())).thenReturn(List.of(wr));
        UnbilledWorkRecordDto unbilled = new UnbilledWorkRecordDto();
        unbilled.setBillingAmount(new BigDecimal("2000")); // fix NPE
        lenient().when(invoiceMapper.selectUnbilledWorkRecordsAll(anyString()))
                .thenReturn(List.of(unbilled));
        lenient().when(bpPaymentMapper.selectListWithDetails(anyString(), eq("未払")))
                .thenReturn(List.of(new com.ses.dto.invoice.BpPaymentListDto()));
        InvoiceBalanceDto overdue = new InvoiceBalanceDto();
        overdue.setDueDate(LocalDate.now().minusDays(5));
        overdue.setBalance(new BigDecimal("1000"));
        overdue.setStatus("送付済");
        InvoiceBalanceDto notDue = new InvoiceBalanceDto();
        notDue.setDueDate(LocalDate.now().plusDays(5));
        notDue.setStatus("送付済");
        lenient().when(invoiceMapper.selectOutstandingBalances()).thenReturn(List.of(overdue, notDue));
        lenient().when(systemConfigMapper.selectById(anyString())).thenReturn(new com.ses.entity.SystemConfig());

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
        lenient().when(invoiceMapper.selectOutstandingBalances()).thenReturn(List.of(overdue));

        MonthlyClosingSummaryDto s = service.summary("2026-06");
        assertTrue(s.isReadyToClose(), "(e)期限超過は締めを妨げない");
        assertEquals(1, s.getOverdueCount());
    }

    @Test
    void confirm_recordsWhenReady() {
        stubEmptyAll();
        lenient().when(sysUserMapper.selectById(any())).thenReturn(new com.ses.entity.SysUser());

        service.confirmClosing("2026-06", 7L, "管理者");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(systemConfigService).put(eq("closing.confirmed-months"), json.capture(), anyString());
        assertTrue(json.getValue().contains("2026-06"));
        assertTrue(json.getValue().contains("7"));
    }

    @Test
    void confirm_notReadyThrows() {
        stubEmptyAll();
        WorkRecord wr = new WorkRecord();
        wr.setBillingAmount(new BigDecimal("100"));
        lenient().when(workRecordMapper.selectList(any())).thenReturn(List.of(wr)); // (b) 残あり

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.confirmClosing("2026-06", 7L, "管理者"));
        assertTrue(ex.getMessage().contains("error.closing.notReady"));
        verify(systemConfigService, never()).put(any(), any(), any());
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
        com.ses.entity.SystemConfig config = new com.ses.entity.SystemConfig();
        config.setConfigValue("[{\"month\":\"2026-06\",\"userId\":7,\"confirmedAt\":\"2026-07-01T10:00:00\"}]");
        lenient().when(systemConfigMapper.selectById(anyString())).thenReturn(config);
        
        assertTrue(service.isClosed("2026-06"));
        assertFalse(service.isClosed("2026-05"));
    }

    @Test
    void reopen_removesRecord() {
        com.ses.entity.SystemConfig config = new com.ses.entity.SystemConfig();
        config.setConfigValue("[{\"month\":\"2026-06\",\"userId\":7,\"confirmedAt\":\"2026-07-01T10:00:00\"}]");
        lenient().when(systemConfigMapper.selectByIdForUpdate(anyString())).thenReturn(config);
        
        service.reopenClosing("2026-06", 7L, "マネージャー");
        
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(systemConfigService).put(eq("closing.confirmed-months"), json.capture(), anyString());
        assertFalse(json.getValue().contains("2026-06"));
    }

    @Test
    void reopen_notClosedThrows() {
        lenient().when(systemConfigMapper.selectByIdForUpdate(anyString())).thenReturn(new com.ses.entity.SystemConfig());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reopenClosing("2026-06", 7L, "管理者"));
        assertTrue(ex.getMessage().contains("error.closing.notClosed"));
    }

    @org.junit.jupiter.api.AfterEach
    void clearAuth() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
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