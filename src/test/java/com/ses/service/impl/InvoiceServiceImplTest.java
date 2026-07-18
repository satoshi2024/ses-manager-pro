package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import com.ses.entity.Invoice;
import com.ses.entity.InvoiceItem;
import com.ses.entity.InvoicePayment;
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

    @Mock
    private com.ses.mapper.InvoicePaymentMapper invoicePaymentMapper;

    @Mock
    private com.ses.service.MailService mailService;

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
        assertTrue(ex.getMessage().contains("error.invoice.cancelPaidInvoice"));
    }

    @Test
    void testVoidInvoice_NotFoundThrowsException() {
        Long invoiceId = 1L;
        when(invoiceMapper.selectById(invoiceId)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.voidInvoice(invoiceId));
        assertTrue(ex.getMessage().contains("error.invoice.notFound"));
    }

    @Test
    void testChangeStatus_InvalidTransition() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("未送付");

        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.changeStatus(invoiceId, "入金済", null));
        assertTrue(ex.getMessage().contains("error.invoice.statusTransitionInvalid"));
    }

    @Test
    void testChangeStatus_ManualPaidRejected() {
        // 入金済への手動遷移は廃止。送付済→入金済 は不正遷移として拒否される（入金行から遷移させる）。
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("送付済");

        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.changeStatus(invoiceId, "入金済", null));
        assertTrue(ex.getMessage().contains("error.invoice.statusTransitionInvalid"));
    }

    @Test
    void testChangeStatus_ManualRevertFromPaidRejected() {
        // 入金済→送付済 の手動巻き戻しも廃止（入金行の削除で表現する）。
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("入金済");

        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.changeStatus(invoiceId, "送付済", null));
        assertTrue(ex.getMessage().contains("error.invoice.statusTransitionInvalid"));
    }

    @Test
    void testChangeStatus_SentToUnsent() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("送付済");

        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);
        when(invoiceMapper.updateById(any(Invoice.class))).thenReturn(1);

        invoiceService.changeStatus(invoiceId, "未送付", null);

        verify(invoiceMapper, times(1)).updateById(any(Invoice.class));
    }

    // ===== 債権管理（ar-management / P2） =====

    @Test
    void testResolvePaymentStatus_Boundaries() {
        BigDecimal total = new BigDecimal("110000");
        assertEquals("送付済", InvoiceServiceImpl.resolvePaymentStatus(BigDecimal.ZERO, total));
        assertEquals("一部入金", InvoiceServiceImpl.resolvePaymentStatus(new BigDecimal("50000"), total));
        assertEquals("入金済", InvoiceServiceImpl.resolvePaymentStatus(new BigDecimal("110000"), total));
        // 手数料込みで到達するケースも paidTotal>=total で入金済
        assertEquals("入金済", InvoiceServiceImpl.resolvePaymentStatus(new BigDecimal("110500"), total));
    }

    @Test
    void testClassifyBucket_Boundaries() {
        LocalDate asOf = LocalDate.of(2026, 7, 17);
        // 経過0日(当日)=期限内
        assertEquals("notDue", InvoiceServiceImpl.classifyBucket(asOf, asOf));
        // 期限が未来=期限内
        assertEquals("notDue", InvoiceServiceImpl.classifyBucket(asOf.plusDays(5), asOf));
        // 1日超過=1-30
        assertEquals("d1to30", InvoiceServiceImpl.classifyBucket(asOf.minusDays(1), asOf));
        assertEquals("d1to30", InvoiceServiceImpl.classifyBucket(asOf.minusDays(30), asOf));
        // 31日=31-60
        assertEquals("d31to60", InvoiceServiceImpl.classifyBucket(asOf.minusDays(31), asOf));
        assertEquals("d61to90", InvoiceServiceImpl.classifyBucket(asOf.minusDays(61), asOf));
        assertEquals("d91plus", InvoiceServiceImpl.classifyBucket(asOf.minusDays(91), asOf));
        // 期限未設定
        assertEquals("noDueDate", InvoiceServiceImpl.classifyBucket(null, asOf));
    }

    @Test
    void testAging_UnsentGoesToUnsentColumnNotOverdue() {
        // 未送付かつ期限超過の請求書は d31to60 ではなく unsent 列に入る（R2-2）。
        com.ses.dto.invoice.InvoiceBalanceDto unsent = new com.ses.dto.invoice.InvoiceBalanceDto();
        unsent.setCustomerId(1L);
        unsent.setCustomerName("客A");
        unsent.setStatus("未送付");
        unsent.setDueDate(LocalDate.now().minusDays(45)); // 期限超過だが未送付
        unsent.setBalance(new BigDecimal("50000"));
        com.ses.dto.invoice.InvoiceBalanceDto sent = new com.ses.dto.invoice.InvoiceBalanceDto();
        sent.setCustomerId(1L);
        sent.setCustomerName("客A");
        sent.setStatus("送付済");
        sent.setDueDate(LocalDate.now().minusDays(45)); // 31-60日
        sent.setBalance(new BigDecimal("30000"));
        when(invoiceMapper.selectOutstandingBalances()).thenReturn(java.util.List.of(unsent, sent));

        com.ses.dto.invoice.AgingReportDto report = invoiceService.aging(LocalDate.now());
        assertEquals(0, new BigDecimal("50000").compareTo(report.getTotal().getUnsent()));
        assertEquals(0, new BigDecimal("30000").compareTo(report.getTotal().getD31to60()));
    }

    @Test
    void testAddPayment_PartialSetsPartiallyPaid() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setTotal(new BigDecimal("110000"));
        invoice.setStatus("送付済");
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        InvoicePayment newPayment = new InvoicePayment();
        newPayment.setAmount(new BigDecimal("50000"));
        newPayment.setPaidDate(LocalDate.of(2026, 7, 10));

        // sumPaid（既存なし）→ insert後 recalc（新1件）
        when(invoicePaymentMapper.selectList(any()))
                .thenReturn(java.util.Collections.emptyList())
                .thenReturn(java.util.List.of(newPayment));
        when(invoicePaymentMapper.insert(any(InvoicePayment.class))).thenReturn(1);

        invoiceService.addPayment(invoiceId, newPayment);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper> cap =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(invoiceMapper).update(any(), cap.capture());
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue("一部入金"));
    }

    @Test
    void testAddPayment_OverPaymentRejected() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setTotal(new BigDecimal("110000"));
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        InvoicePayment existing = new InvoicePayment();
        existing.setAmount(new BigDecimal("100000"));
        when(invoicePaymentMapper.selectList(any())).thenReturn(java.util.List.of(existing));

        InvoicePayment newPayment = new InvoicePayment();
        newPayment.setAmount(new BigDecimal("20000"));
        newPayment.setPaidDate(LocalDate.now());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoiceService.addPayment(invoiceId, newPayment));
        assertTrue(ex.getMessage().contains("error.invoice.overPayment"));
        verify(invoicePaymentMapper, never()).insert(any(InvoicePayment.class));
    }

    @Test
    void testAddPayment_VoidedOrMissingInvoiceRejected() {
        when(invoiceMapper.selectById(anyLong())).thenReturn(null);
        InvoicePayment p = new InvoicePayment();
        p.setAmount(new BigDecimal("1000"));
        p.setPaidDate(LocalDate.now());
        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.addPayment(9L, p));
        assertTrue(ex.getMessage().contains("error.invoice.notFound"));
    }

    @Test
    void testAddPayment_OnUnsentAllowed() {
        // 先行入金は実務で発生するため許可され、ステータスは一部入金へ。
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setTotal(new BigDecimal("110000"));
        invoice.setStatus("未送付");
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        InvoicePayment newPayment = new InvoicePayment();
        newPayment.setAmount(new BigDecimal("30000"));
        newPayment.setPaidDate(LocalDate.now());
        when(invoicePaymentMapper.selectList(any()))
                .thenReturn(java.util.Collections.emptyList())
                .thenReturn(java.util.List.of(newPayment));
        when(invoicePaymentMapper.insert(any(InvoicePayment.class))).thenReturn(1);

        assertDoesNotThrow(() -> invoiceService.addPayment(invoiceId, newPayment));
        verify(invoicePaymentMapper, times(1)).insert(any(InvoicePayment.class));
    }

    @Test
    void testDeletePayment_RollsBackToSent() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setTotal(new BigDecimal("110000"));
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        InvoicePayment existing = new InvoicePayment();
        existing.setId(5L);
        existing.setInvoiceId(invoiceId);
        when(invoicePaymentMapper.selectById(5L)).thenReturn(existing);
        when(invoicePaymentMapper.deleteById(5L)).thenReturn(1);
        // recalc: 削除後は0件
        when(invoicePaymentMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

        invoiceService.deletePayment(invoiceId, 5L);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper> cap =
                org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper.class);
        verify(invoiceMapper).update(any(), cap.capture());
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue("送付済"));
    }

    @Test
    void testSendReminder_MissingEmail() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("送付済");
        invoice.setCustomerId(5L);
        invoice.setTotal(new BigDecimal("110000"));
        invoice.setDueDate(LocalDate.now().minusDays(5));
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);
        com.ses.entity.Customer c = com.ses.entity.Customer.builder().companyName("客A").build();
        when(customerMapper.selectById(5L)).thenReturn(c);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoiceService.sendReminder(invoiceId, 1L));
        assertTrue(ex.getMessage().contains("error.invoice.customerEmailMissing"));
    }

    @Test
    void testSendReminder_NotOverdueRejected() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("送付済");
        invoice.setDueDate(LocalDate.now().plusDays(5));
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoiceService.sendReminder(invoiceId, 1L));
        assertTrue(ex.getMessage().contains("error.invoice.reminderNotOverdue"));
    }

    @Test
    void testSendReminder_Success() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("一部入金");
        invoice.setCustomerId(5L);
        invoice.setInvoiceNo("INV-202607-0001");
        invoice.setTotal(new BigDecimal("110000"));
        invoice.setDueDate(LocalDate.now().minusDays(10));
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);
        com.ses.entity.Customer c = com.ses.entity.Customer.builder()
                .companyName("客A").contactEmail("ap@example.com").build();
        when(customerMapper.selectById(5L)).thenReturn(c);
        when(invoicePaymentMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        when(mailService.sendWithTemplate(any(), any(), any()))
                .thenReturn(new com.ses.dto.mail.MailDispatchResult(1L, "QUEUED"));

        var result = invoiceService.sendReminder(invoiceId, 7L);
        assertEquals("QUEUED", result.getStatus());
        verify(mailService, times(1)).sendWithTemplate(eq(7L), any(), eq("ap@example.com"));
    }

    @Test
    void testChangeBpPaymentStatus_NotFound() {
        when(bpPaymentMapper.selectById(anyLong())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.changeBpPaymentStatus(1L, "支払済", null));
        assertTrue(ex.getMessage().contains("error.invoice.bpPaymentNotFound"));
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
        assertTrue(ex.getMessage().contains("error.invoice.statusInvalid"));
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

    // ===== R8: 請求書への適用税率の保存 =====

    @Test
    void testGenerate_SavesTaxRateAtIssue() {
        Long customerId = 1L;
        String billingMonth = "2026-07";

        UnbilledWorkRecordDto dto = new UnbilledWorkRecordDto();
        dto.setWorkRecordId(10L);
        dto.setBillingAmount(new BigDecimal("100000"));
        when(invoiceMapper.selectUnbilledWorkRecords(customerId, billingMonth))
                .thenReturn(Collections.singletonList(dto));
        when(invoiceMapper.selectMaxInvoiceNoIncludingDeleted(anyString())).thenReturn(null);
        when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);
        when(invoiceItemMapper.insert(any(InvoiceItem.class))).thenReturn(1);
        when(systemConfigService.getDecimal(any(), any())).thenReturn(new BigDecimal("0.10"));

        Invoice invoice = invoiceService.generate(customerId, billingMonth);

        // 生成時点の税率が保存されること
        assertEquals(0, new BigDecimal("0.10").compareTo(invoice.getTaxRate()));
    }

    @Test
    void testDetail_UsesSavedTaxRateOverCurrentConfig() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setCustomerId(5L);
        invoice.setTaxRate(new BigDecimal("0.10")); // 生成時点は10%
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);
        when(customerMapper.selectById(5L)).thenReturn(null);
        when(invoiceItemMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(systemConfigService.getString(any(), any())).thenReturn("");
        // 現在の設定は8%に改定済みだが、保存値(10%)が優先されること
        lenient().when(systemConfigService.getDecimal(any(), any())).thenReturn(new BigDecimal("0.08"));

        var detail = invoiceService.detail(invoiceId);

        assertEquals("10", detail.getTaxRatePercent());
    }

    @Test
    void testDetail_FallsBackToConfigWhenTaxRateNull() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setCustomerId(5L);
        invoice.setTaxRate(null); // 本対応以前の既存行
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);
        when(customerMapper.selectById(5L)).thenReturn(null);
        when(invoiceItemMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(systemConfigService.getString(any(), any())).thenReturn("");
        when(systemConfigService.getDecimal(any(), any())).thenReturn(new BigDecimal("0.10"));

        var detail = invoiceService.detail(invoiceId);

        assertEquals("10", detail.getTaxRatePercent());
    }
}
