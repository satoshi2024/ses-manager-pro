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

    @InjectMocks
    private MonthlyClosingServiceImpl service;

    private void stubEmptyAll() {
        when(workRecordMapper.selectMonthlyGrid(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(workRecordMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(invoiceMapper.selectUnbilledWorkRecordsAll(anyString())).thenReturn(Collections.emptyList());
        when(bpPaymentMapper.selectListWithDetails(anyString(), any())).thenReturn(Collections.emptyList());
        when(invoiceMapper.selectOutstandingBalances()).thenReturn(Collections.emptyList());
    }

    @Test
    void summary_detectsEachItem() {
        WorkRecordGridDto entered = new WorkRecordGridDto();
        entered.setWorkRecordId(5L);
        WorkRecordGridDto unentered = new WorkRecordGridDto();
        unentered.setWorkRecordId(null);
        when(workRecordMapper.selectMonthlyGrid(anyString(), anyString()))
                .thenReturn(List.of(entered, unentered));
        when(workRecordMapper.selectList(any())).thenReturn(List.of(new WorkRecord()));
        when(invoiceMapper.selectUnbilledWorkRecordsAll(anyString()))
                .thenReturn(List.of(new UnbilledWorkRecordDto()));
        when(bpPaymentMapper.selectListWithDetails(anyString(), eq("未払")))
                .thenReturn(List.of(new com.ses.dto.invoice.BpPaymentListDto()));
        InvoiceBalanceDto overdue = new InvoiceBalanceDto();
        overdue.setDueDate(LocalDate.now().minusDays(5));
        overdue.setBalance(new BigDecimal("1000"));
        InvoiceBalanceDto notDue = new InvoiceBalanceDto();
        notDue.setDueDate(LocalDate.now().plusDays(5));
        when(invoiceMapper.selectOutstandingBalances()).thenReturn(List.of(overdue, notDue));
        when(systemConfigService.getString(eq("closing.confirmed-months"), any())).thenReturn("");

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
        when(workRecordMapper.selectMonthlyGrid(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(workRecordMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(invoiceMapper.selectUnbilledWorkRecordsAll(anyString())).thenReturn(Collections.emptyList());
        when(bpPaymentMapper.selectListWithDetails(anyString(), any())).thenReturn(Collections.emptyList());
        InvoiceBalanceDto overdue = new InvoiceBalanceDto();
        overdue.setDueDate(LocalDate.now().minusDays(1));
        when(invoiceMapper.selectOutstandingBalances()).thenReturn(List.of(overdue));
        when(systemConfigService.getString(eq("closing.confirmed-months"), any())).thenReturn("");

        MonthlyClosingSummaryDto s = service.summary("2026-06");
        assertTrue(s.isReadyToClose(), "(e)期限超過は締めを妨げない");
        assertEquals(1, s.getOverdueCount());
    }

    @Test
    void confirm_recordsWhenReady() {
        stubEmptyAll();
        when(systemConfigService.getString(eq("closing.confirmed-months"), any())).thenReturn("");

        service.confirmClosing("2026-06", 7L, "管理者");

        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(systemConfigService).put(eq("closing.confirmed-months"), json.capture(), anyString());
        assertTrue(json.getValue().contains("2026-06"));
        assertTrue(json.getValue().contains("7"));
    }

    @Test
    void confirm_notReadyThrows() {
        when(workRecordMapper.selectMonthlyGrid(anyString(), anyString())).thenReturn(Collections.emptyList());
        when(workRecordMapper.selectList(any())).thenReturn(List.of(new WorkRecord())); // (b) 残あり
        when(invoiceMapper.selectUnbilledWorkRecordsAll(anyString())).thenReturn(Collections.emptyList());
        when(bpPaymentMapper.selectListWithDetails(anyString(), any())).thenReturn(Collections.emptyList());
        when(invoiceMapper.selectOutstandingBalances()).thenReturn(Collections.emptyList());
        when(systemConfigService.getString(eq("closing.confirmed-months"), any())).thenReturn("");

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
        assertTrue(ex.getMessage().contains("error.closing.invalidMonth"));
    }

    @Test
    void isClosed_reflectsRecord() {
        when(systemConfigService.getString(eq("closing.confirmed-months"), any()))
                .thenReturn("[{\"month\":\"2026-06\",\"userId\":7,\"confirmedAt\":\"2026-07-01T10:00:00\"}]");
        assertTrue(service.isClosed("2026-06"));
        assertFalse(service.isClosed("2026-05"));
    }

    @Test
    void reopen_removesRecord() {
        when(systemConfigService.getString(eq("closing.confirmed-months"), any()))
                .thenReturn("[{\"month\":\"2026-06\",\"userId\":7,\"confirmedAt\":\"2026-07-01T10:00:00\"}]");
        service.reopenClosing("2026-06", 7L, "マネージャー");
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(systemConfigService).put(eq("closing.confirmed-months"), json.capture(), anyString());
        assertFalse(json.getValue().contains("2026-06"));
    }

    @Test
    void reopen_notClosedThrows() {
        when(systemConfigService.getString(eq("closing.confirmed-months"), any())).thenReturn("");
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reopenClosing("2026-06", 7L, "管理者"));
        assertTrue(ex.getMessage().contains("error.closing.notClosed"));
    }
}
