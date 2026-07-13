package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import com.ses.entity.Invoice;
import com.ses.entity.InvoiceItem;
import com.ses.entity.BpPayment;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.InvoiceItemMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class InvoiceServiceImplTest {

    @Mock
    private InvoiceMapper invoiceMapper;

    @Mock
    private InvoiceItemMapper invoiceItemMapper;

    @Mock
    private CustomerMapper customerMapper;

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private BpPaymentMapper bpPaymentMapper;

    @InjectMocks
    private InvoiceServiceImpl invoiceService;

    @BeforeEach
    void setUp() {
        // ServiceImpl の baseMapper フィールドを手動で注入
        ReflectionTestUtils.setField(invoiceService, "baseMapper", invoiceMapper);
    }

    @Test
    void testGenerate_Success() {
        Long customerId = 1L;
        String billingMonth = "2026-07";

        UnbilledWorkRecordDto dto = new UnbilledWorkRecordDto();
        dto.setWorkRecordId(10L);
        dto.setBillingAmount(new BigDecimal("100000"));
        dto.setEngineerName("山田太郎");
        dto.setProjectName("開発案件");

        when(invoiceMapper.selectUnbilledWorkRecords(customerId, billingMonth))
                .thenReturn(Collections.singletonList(dto));

        when(invoiceMapper.selectMaxInvoiceNoIncludingDeleted(anyString())).thenReturn(null);
        when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);
        when(invoiceItemMapper.insert(any(InvoiceItem.class))).thenReturn(1);
        when(systemConfigService.getDecimal(any(), any())).thenReturn(new BigDecimal("0.10"));

        Invoice invoice = invoiceService.generate(customerId, billingMonth);

        assertNotNull(invoice);
        assertEquals("INV-202607-0001", invoice.getInvoiceNo());
        assertEquals(new BigDecimal("100000"), invoice.getSubtotal());
        assertEquals(new BigDecimal("10000"), invoice.getTax());
        assertEquals(new BigDecimal("110000"), invoice.getTotal());

        verify(invoiceMapper, times(1)).insert(any(Invoice.class));
        verify(invoiceItemMapper, times(1)).insert(any(InvoiceItem.class));
    }

    @Test
    void testGenerate_NoUnbilledRecords() {
        Long customerId = 1L;
        String billingMonth = "2026-07";

        when(invoiceMapper.selectUnbilledWorkRecords(customerId, billingMonth))
                .thenReturn(Collections.emptyList());

        assertThrows(BusinessException.class, () -> invoiceService.generate(customerId, billingMonth));
    }

    @Test
    void testGenerateInvoiceNo() {
        String billingMonth = "2026-07";

        when(invoiceMapper.selectMaxInvoiceNoIncludingDeleted(anyString())).thenReturn("INV-202607-0002");

        String nextNo = invoiceService.generateInvoiceNo(billingMonth);
        assertEquals("INV-202607-0003", nextNo);
    }

    @Test
    void testGenerateInvoiceNo_Empty() {
        String billingMonth = "2026-07";

        when(invoiceMapper.selectMaxInvoiceNoIncludingDeleted(anyString())).thenReturn(null);

        String nextNo = invoiceService.generateInvoiceNo(billingMonth);
        assertEquals("INV-202607-0001", nextNo);
    }

    @Test
    void testGenerate_RetriesOnDuplicateInvoiceNoAndSucceeds() {
        Long customerId = 1L;
        String billingMonth = "2026-07";

        UnbilledWorkRecordDto dto = new UnbilledWorkRecordDto();
        dto.setWorkRecordId(10L);
        dto.setBillingAmount(new BigDecimal("100000"));
        dto.setEngineerName("山田太郎");
        dto.setProjectName("開発案件");
        when(invoiceMapper.selectUnbilledWorkRecords(customerId, billingMonth))
                .thenReturn(Collections.singletonList(dto));
        when(systemConfigService.getDecimal(any(), any())).thenReturn(new BigDecimal("0.10"));
        when(invoiceMapper.selectMaxInvoiceNoIncludingDeleted(anyString())).thenReturn(null);

        // 1回目のinsertは同時採番の衝突でDuplicateKeyException、2回目で成功する
        when(invoiceMapper.insert(any(Invoice.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate invoice_no"))
                .thenReturn(1);

        Invoice invoice = invoiceService.generate(customerId, billingMonth);

        assertNotNull(invoice);
        verify(invoiceMapper, times(2)).insert(any(Invoice.class));
    }

    @Test
    void testGenerate_FailsAfterMaxRetries() {
        Long customerId = 1L;
        String billingMonth = "2026-07";

        UnbilledWorkRecordDto dto = new UnbilledWorkRecordDto();
        dto.setWorkRecordId(10L);
        dto.setBillingAmount(new BigDecimal("100000"));
        when(invoiceMapper.selectUnbilledWorkRecords(customerId, billingMonth))
                .thenReturn(Collections.singletonList(dto));
        when(systemConfigService.getDecimal(any(), any())).thenReturn(new BigDecimal("0.10"));
        when(invoiceMapper.selectMaxInvoiceNoIncludingDeleted(anyString())).thenReturn(null);
        when(invoiceMapper.insert(any(Invoice.class)))
                .thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate invoice_no"));

        assertThrows(BusinessException.class, () -> invoiceService.generate(customerId, billingMonth));
        verify(invoiceMapper, times(3)).insert(any(Invoice.class));
    }

    @Test
    void testVoidInvoice_Success() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("未送付");

        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);
        when(invoiceItemMapper.delete(any())).thenReturn(1);
        when(invoiceMapper.deleteById(invoiceId)).thenReturn(1);

        invoiceService.voidInvoice(invoiceId);

        verify(invoiceItemMapper, times(1)).delete(any());
        verify(invoiceMapper, times(1)).deleteById(invoiceId);
    }

    @Test
    void testVoidInvoice_PaidThrowsException() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("入金済");

        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.voidInvoice(invoiceId));
        assertTrue(ex.getMessage().contains("入金済の請求書は取消できません"));
    }

    @Test
    void testVoidInvoice_NotFoundThrowsException() {
        Long invoiceId = 1L;
        when(invoiceMapper.selectById(invoiceId)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.voidInvoice(invoiceId));
        assertTrue(ex.getMessage().contains("請求書が見つかりません"));
    }

    @Test
    void testChangeStatus_InvalidTransition() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("未送付");

        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.changeStatus(invoiceId, "入金済", null));
        assertTrue(ex.getMessage().contains("変更できません"));
    }

    @Test
    void testChangeStatus_PaidRequiresDate() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("送付済");

        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.changeStatus(invoiceId, "入金済", null));
        assertTrue(ex.getMessage().contains("入金日を指定してください"));
    }

    @Test
    void testChangeStatus_ClearPaidDate() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("入金済");
        invoice.setPaidDate(java.time.LocalDate.now());

        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);
        when(invoiceMapper.update(any(), any())).thenReturn(1);

        invoiceService.changeStatus(invoiceId, "送付済", null);

        verify(invoiceMapper, times(1)).update(any(), any());
    }

    @Test
    void testChangeBpPaymentStatus_NotFound() {
        when(bpPaymentMapper.selectById(anyLong())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.changeBpPaymentStatus(1L, "支払済", null));
        assertTrue(ex.getMessage().contains("BP支払が見つかりません"));
    }

    @Test
    void testChangeBpPaymentStatus_ClearPaidDate() {
        BpPayment bp = new BpPayment();
        bp.setId(1L);
        bp.setStatus("支払済");
        when(bpPaymentMapper.selectById(1L)).thenReturn(bp);
        when(bpPaymentMapper.update(any(), any())).thenReturn(1);

        invoiceService.changeBpPaymentStatus(1L, "未払", null);

        verify(bpPaymentMapper, times(1)).update(any(), any());
    }

    @Test
    void testChangeBpPaymentStatus_InvalidStatus() {
        BpPayment bp = new BpPayment();
        bp.setId(1L);
        when(bpPaymentMapper.selectById(1L)).thenReturn(bp);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.changeBpPaymentStatus(1L, "済", null));
        assertTrue(ex.getMessage().contains("不正なステータスです"));
    }

    // ===== WS-F: 支払期限・適格請求書対応 =====

    @Test
    void testCalcDueDate_NextMonthEnd() {
        assertEquals(LocalDate.of(2026, 8, 31), InvoiceServiceImpl.calcDueDate("2026-07", "next-month-end"));
    }

    @Test
    void testCalcDueDate_NextNextMonthEnd() {
        assertEquals(LocalDate.of(2026, 9, 30), InvoiceServiceImpl.calcDueDate("2026-07", "next-next-month-end"));
    }

    @Test
    void testCalcDueDate_InvalidRuleDefaultsToNextMonthEnd() {
        assertEquals(LocalDate.of(2026, 8, 31), InvoiceServiceImpl.calcDueDate("2026-07", "bogus"));
    }

    @Test
    void testCalcDueDate_HandlesMonthEndBoundary() {
        // 2026-01 の翌月末は 2026-02-28（月末補正）
        assertEquals(LocalDate.of(2026, 2, 28), InvoiceServiceImpl.calcDueDate("2026-01", "next-month-end"));
    }

    @Test
    void testGenerate_SetsDueDate() {
        Long customerId = 1L;
        String billingMonth = "2026-07";

        UnbilledWorkRecordDto dto = new UnbilledWorkRecordDto();
        dto.setWorkRecordId(10L);
        dto.setBillingAmount(new BigDecimal("100000"));
        dto.setEngineerName("山田太郎");
        dto.setProjectName("開発案件");
        when(invoiceMapper.selectUnbilledWorkRecords(customerId, billingMonth))
                .thenReturn(Collections.singletonList(dto));
        when(invoiceMapper.selectMaxInvoiceNoIncludingDeleted(anyString())).thenReturn(null);
        when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);
        when(invoiceItemMapper.insert(any(InvoiceItem.class))).thenReturn(1);
        when(systemConfigService.getDecimal(any(), any())).thenReturn(new BigDecimal("0.10"));
        when(systemConfigService.getString(any(), any())).thenReturn("next-month-end");

        Invoice invoice = invoiceService.generate(customerId, billingMonth);

        assertEquals(LocalDate.of(2026, 8, 31), invoice.getDueDate());
    }

    @Test
    void testDetail_PopulatesQualifiedInvoiceInfo() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setCustomerId(5L);
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);
        when(customerMapper.selectById(5L)).thenReturn(null);
        when(invoiceItemMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(systemConfigService.getString("company.name", "")).thenReturn("株式会社テスト");
        when(systemConfigService.getString("company.invoice-registration-number", "")).thenReturn("T1234567890123");
        when(systemConfigService.getString("company.address", "")).thenReturn("東京都千代田区");
        when(systemConfigService.getDecimal(any(), any())).thenReturn(new BigDecimal("0.08"));

        var detail = invoiceService.detail(invoiceId);

        assertEquals("株式会社テスト", detail.getCompanyName());
        assertEquals("T1234567890123", detail.getCompanyRegistrationNumber());
        assertEquals("東京都千代田区", detail.getCompanyAddress());
        // 0.08 → "8"（パーセント表記）
        assertEquals("8", detail.getTaxRatePercent());
    }
}
