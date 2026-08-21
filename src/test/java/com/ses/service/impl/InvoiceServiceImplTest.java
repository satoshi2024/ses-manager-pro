package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import com.ses.dto.invoice.InvoicePaymentCreateRequest;
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
import static org.mockito.ArgumentMatchers.isNull;
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
    private com.ses.service.MonthlyClosingService monthlyClosingService;

    @Mock
    private BpPaymentMapper bpPaymentMapper;

    @Mock
    private com.ses.mapper.InvoicePaymentMapper invoicePaymentMapper;

    @Mock
    private com.ses.service.MailService mailService;

    @Mock
    private com.ses.service.security.DataScopeService dataScopeService;

    @Mock
    private com.ses.service.security.OrganizationScopeService organizationScopeService;

    @Mock
    private com.ses.mapper.WorkRecordMapper workRecordMapper;

    @Mock
    private com.ses.service.CustomerContactService customerContactService;

    @Mock
    private com.ses.mapper.BankDepositMapper bankDepositMapper;

    @InjectMocks
    private InvoiceServiceImpl invoiceService;

    @BeforeEach
    void setUp() {
        // ServiceImpl の baseMapper フィールドを手動で注入
        ReflectionTestUtils.setField(invoiceService, "baseMapper", invoiceMapper);
        lenient().when(organizationScopeService.hasFullAccess()).thenReturn(true);
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
    void testGenerate_ManagerUsesOnlyOrganizationScopedWorkRecords() {
        Long customerId = 1L;
        String billingMonth = "2026-07";
        UnbilledWorkRecordDto scoped = new UnbilledWorkRecordDto();
        scoped.setWorkRecordId(10L);
        scoped.setBillingAmount(new BigDecimal("100000"));
        scoped.setEngineerName("組織A要員");
        scoped.setProjectName("組織A案件");

        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.allowedOrganizationIds(LocalDate.of(2026, 7, 1)))
                .thenReturn(java.util.Set.of(11L));
        when(organizationScopeService.allowedDirectUserIds(LocalDate.of(2026, 7, 1)))
                .thenReturn(java.util.Set.of());
        when(invoiceMapper.selectUnbilledWorkRecordsScoped(any(), any(), any(), any(), any()))
                .thenReturn(Collections.singletonList(scoped));
        when(invoiceMapper.selectMaxInvoiceNoIncludingDeleted(anyString())).thenReturn(null);
        when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);
        when(invoiceItemMapper.insert(any(InvoiceItem.class))).thenReturn(1);
        when(systemConfigService.getDecimal(any(), any())).thenReturn(new BigDecimal("0.10"));

        invoiceService.generate(customerId, billingMonth);

        verify(invoiceMapper).selectUnbilledWorkRecordsScoped(any(), any(), any(), any(), any());
        verify(invoiceMapper, never()).selectUnbilledWorkRecords(customerId, billingMonth);
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

        when(invoiceMapper.selectOne(any())).thenReturn(invoice);
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

        when(invoiceMapper.selectOne(any())).thenReturn(invoice);
        
        InvoicePayment existing = new InvoicePayment();
        existing.setAmount(new BigDecimal("100000"));
        when(invoicePaymentMapper.selectList(any())).thenReturn(java.util.List.of(existing));

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.voidInvoice(invoiceId));
        assertTrue(ex.getMessage().contains("error.invoice.cancelPaidInvoice"));
    }

    @Test
    void testVoidInvoice_NotFoundThrowsException() {
        Long invoiceId = 1L;
        when(invoiceMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.voidInvoice(invoiceId));
        assertTrue(ex.getMessage().contains("error.invoice.notFound"));
    }

    @Test
    void testChangeStatus_InvalidTransition() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("未送付");

        when(invoiceMapper.selectOne(any())).thenReturn(invoice);

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

        when(invoiceMapper.selectOne(any())).thenReturn(invoice);

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

        when(invoiceMapper.selectOne(any())).thenReturn(invoice);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.changeStatus(invoiceId, "送付済", null));
        assertTrue(ex.getMessage().contains("error.invoice.statusTransitionInvalid"));
    }

    @Test
    void testChangeStatus_SentToUnsent() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("送付済");

        when(invoiceMapper.selectOne(any())).thenReturn(invoice);
        when(invoiceMapper.updateById(any(Invoice.class))).thenReturn(1);

        invoiceService.changeStatus(invoiceId, "未送付", null);

        verify(invoiceMapper, times(1)).updateById(any(Invoice.class));
    }

    // ===== 債権管理（ar-management / P2） =====

    @Test
    void testResolvePaymentStatus_Boundaries() {
        BigDecimal total = new BigDecimal("110000");
        assertEquals("送付済", InvoiceServiceImpl.resolvePaymentStatus(BigDecimal.ZERO, total, "送付済"));
        assertEquals("一部入金", InvoiceServiceImpl.resolvePaymentStatus(new BigDecimal("50000"), total, "送付済"));
        assertEquals("入金済", InvoiceServiceImpl.resolvePaymentStatus(new BigDecimal("110000"), total, "一部入金"));
        // 手数料込みで到達するケースも paidTotal>=total で入金済
        assertEquals("入金済", InvoiceServiceImpl.resolvePaymentStatus(new BigDecimal("110500"), total, "一部入金"));
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
    void testAging_営業DataScopeを残高SQLへ渡す() {
        LocalDate asOf = LocalDate.of(2026, 7, 17);
        when(organizationScopeService.hasFullAccess()).thenReturn(true);
        when(dataScopeService.isSalesDataScoped()).thenReturn(true);
        when(dataScopeService.allowedCustomerIds()).thenReturn(java.util.Set.of(7L));

        com.ses.dto.invoice.InvoiceBalanceDto balance = new com.ses.dto.invoice.InvoiceBalanceDto();
        balance.setInvoiceId(11L);
        balance.setCustomerId(7L);
        balance.setCustomerName("客A");
        balance.setStatus("送付済");
        balance.setBalance(new BigDecimal("30000"));
        balance.setDueDate(asOf.minusDays(1));
        when(invoiceMapper.selectOutstandingBalancesScoped(any(), any())).thenReturn(java.util.List.of(balance));

        com.ses.dto.invoice.AgingReportDto report = invoiceService.aging(asOf);

        assertEquals(0, new BigDecimal("30000").compareTo(report.getTotal().getD1to30()));
        verify(invoiceMapper).selectOutstandingBalancesScoped(
                isNull(), eq(java.util.List.of(7L)));
        verify(invoiceMapper, never()).selectOutstandingBalances();
    }

    @Test
    void testAddPayment_マネージャーは請求対象月の組織scope外を拒否する() {
        Invoice invoice = new Invoice();
        invoice.setId(55L);
        invoice.setCustomerId(7L);
        invoice.setBillingMonth("2026-07");
        invoice.setStatus("送付済");
        invoice.setTotal(new BigDecimal("100000"));
        when(invoiceMapper.selectOne(any())).thenReturn(invoice);
        when(organizationScopeService.hasFullAccess()).thenReturn(false);
        when(organizationScopeService.allowedInvoiceIds(LocalDate.of(2026, 7, 1)))
                .thenReturn(java.util.Set.of());

        InvoicePaymentCreateRequest request = new InvoicePaymentCreateRequest();
        request.setAmount(new BigDecimal("1000"));
        request.setPaidDate(LocalDate.of(2026, 7, 20));

        assertThrows(BusinessException.class, () -> invoiceService.addPayment(55L, request));

        verify(organizationScopeService).allowedInvoiceIds(LocalDate.of(2026, 7, 1));
        verify(invoicePaymentMapper, never()).insert(any(InvoicePayment.class));
    }

    @Test
    void testAddPayment_PartialSetsPartiallyPaid() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setTotal(new BigDecimal("110000"));
        invoice.setStatus("送付済");
        when(invoiceMapper.selectOne(any())).thenReturn(invoice);

        com.ses.dto.invoice.InvoicePaymentCreateRequest req = new com.ses.dto.invoice.InvoicePaymentCreateRequest();
        req.setAmount(new BigDecimal("50000"));
        req.setPaidDate(LocalDate.of(2026, 7, 10));

        InvoicePayment newPayment = new InvoicePayment();
        newPayment.setAmount(new BigDecimal("50000"));
        newPayment.setPaidDate(LocalDate.of(2026, 7, 10));

        // sumPaid（既存なし）→ insert後 recalc（新1件）
        when(invoicePaymentMapper.selectList(any()))
                .thenReturn(java.util.Collections.emptyList())
                .thenReturn(java.util.List.of(newPayment));
        when(invoicePaymentMapper.insert(any(InvoicePayment.class))).thenReturn(1);

        invoiceService.addPayment(invoiceId, req);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Invoice>> cap =
                org.mockito.ArgumentCaptor.captor();
        verify(invoiceMapper).update(any(), cap.capture());
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue("一部入金"));
    }

    @Test
    void testAddPayment_OverPaymentRejected() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setTotal(new BigDecimal("110000"));
        when(invoiceMapper.selectOne(any())).thenReturn(invoice);

        InvoicePayment existing = new InvoicePayment();
        existing.setAmount(new BigDecimal("100000"));
        when(invoicePaymentMapper.selectList(any())).thenReturn(java.util.List.of(existing));

        com.ses.dto.invoice.InvoicePaymentCreateRequest req = new com.ses.dto.invoice.InvoicePaymentCreateRequest();
        req.setAmount(new BigDecimal("20000"));
        req.setPaidDate(LocalDate.now());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> invoiceService.addPayment(invoiceId, req));
        assertTrue(ex.getMessage().contains("error.invoice.overPayment"));
        verify(invoicePaymentMapper, never()).insert(any(InvoicePayment.class));
    }

    @Test
    void testAddPayment_VoidedOrMissingInvoiceRejected() {
        when(invoiceMapper.selectOne(any())).thenReturn(null);
        com.ses.dto.invoice.InvoicePaymentCreateRequest req = new com.ses.dto.invoice.InvoicePaymentCreateRequest();
        req.setAmount(new BigDecimal("1000"));
        req.setPaidDate(LocalDate.now());
        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.addPayment(9L, req));
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
        when(invoiceMapper.selectOne(any())).thenReturn(invoice);

        com.ses.dto.invoice.InvoicePaymentCreateRequest req = new com.ses.dto.invoice.InvoicePaymentCreateRequest();
        req.setAmount(new BigDecimal("30000"));
        req.setPaidDate(LocalDate.now());

        InvoicePayment newPayment = new InvoicePayment();
        newPayment.setAmount(new BigDecimal("30000"));
        newPayment.setPaidDate(LocalDate.now());
        when(invoicePaymentMapper.selectList(any()))
                .thenReturn(java.util.Collections.emptyList())
                .thenReturn(java.util.List.of(newPayment));
        when(invoicePaymentMapper.insert(any(InvoicePayment.class))).thenReturn(1);

        assertDoesNotThrow(() -> invoiceService.addPayment(invoiceId, req));
        verify(invoicePaymentMapper, times(1)).insert(any(InvoicePayment.class));
    }

    @Test
    void testDeletePayment_RollsBackToSent() {
        Long invoiceId = 1L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setTotal(new BigDecimal("110000"));
        when(invoiceMapper.selectOne(any())).thenReturn(invoice);

        InvoicePayment existing = new InvoicePayment();
        existing.setId(5L);
        existing.setInvoiceId(invoiceId);
        when(invoicePaymentMapper.selectById(5L)).thenReturn(existing);
        when(invoicePaymentMapper.deleteById(5L)).thenReturn(1);
        // recalc: 削除後は0件
        when(invoicePaymentMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

        invoiceService.deletePayment(invoiceId, 5L);

        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Invoice>> cap =
                org.mockito.ArgumentCaptor.captor();
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
        assertTrue(ex.getMessage().contains("error.invoice.reminderNotAllowed"));
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
        when(mailService.sendWithTemplate(any(), any(), any(), any()))
                .thenReturn(new com.ses.dto.mail.MailDispatchResult(1L, "QUEUED"));

        var result = invoiceService.sendReminder(invoiceId, 7L);
        assertEquals("QUEUED", result.getStatus());
        verify(mailService, times(1)).sendWithTemplate(eq(7L), any(), eq("ap@example.com"), eq(1L));
    }

    @Test
    void testSendReminder_UsesSelectedActiveContact() {
        Long invoiceId = 2L;
        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setStatus("送付済");
        invoice.setCustomerId(5L);
        invoice.setInvoiceNo("INV-202607-0002");
        invoice.setTotal(new BigDecimal("110000"));
        invoice.setDueDate(LocalDate.now().minusDays(10));
        when(invoiceMapper.selectById(invoiceId)).thenReturn(invoice);
        when(customerMapper.selectById(5L)).thenReturn(com.ses.entity.Customer.builder()
                .companyName("客A").contactEmail("legacy@example.com").build());
        when(customerContactService.resolveRecipientEmail(5L, 88L, LocalDate.now()))
                .thenReturn("current@example.com");
        when(invoicePaymentMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        when(mailService.sendWithTemplate(any(), any(), any(), any(), any(), any()))
                .thenReturn(new com.ses.dto.mail.MailDispatchResult(2L, "QUEUED"));

        var result = invoiceService.sendReminder(invoiceId, 7L, 88L);
        assertEquals("QUEUED", result.getStatus());
        verify(mailService).sendWithTemplate(eq(7L), any(), eq("current@example.com"), eq(invoiceId), eq(88L), isNull());
    }

    @Test
    void testChangeBpPaymentStatus_NotFound() {
        when(bpPaymentMapper.selectByIdForUpdate(anyLong())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> invoiceService.changeBpPaymentStatus(1L, "支払済", null));
        assertTrue(ex.getMessage().contains("error.invoice.bpPaymentNotFound"));
    }

    @Test
    void testChangeBpPaymentStatus_ClearPaidDate() {
        BpPayment bp = new BpPayment();
        bp.setId(1L);
        bp.setStatus("支払済");
        when(bpPaymentMapper.selectByIdForUpdate(1L)).thenReturn(bp);
        when(bpPaymentMapper.update(any(), any())).thenReturn(1);

        invoiceService.changeBpPaymentStatus(1L, "未払", null);

        verify(bpPaymentMapper, times(1)).update(any(), any());
    }

    @Test
    void testChangeBpPaymentStatus_InvalidStatus() {
        BpPayment bp = new BpPayment();
        bp.setId(1L);
        when(bpPaymentMapper.selectByIdForUpdate(1L)).thenReturn(bp);

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

    // ===== payment-reconciliation (FR-09) との整合 =====

    /**
     * 入金消込で作られた入金を削除したら、銀行入金明細の消込も取り消して未消込へ戻す。
     *
     * <p>t_bank_deposit.matched_payment_id は t_invoice_payment を ON DELETE RESTRICT で
     * 参照しており、t_invoice_payment は物理削除。解除せずに削除するとFK違反で500になり、
     * 誤った自動消込を取り消す手段が無くなる（消込解除APIも存在しない）。
     */
    @Test
    void deletePayment_消込済みの銀行入金を未消込へ戻してから削除する() {
        Long invoiceId = 1L;
        Long paymentId = 55L;

        Invoice invoice = new Invoice();
        invoice.setId(invoiceId);
        invoice.setCustomerId(9L);
        invoice.setTotal(new BigDecimal("110000"));
        invoice.setStatus("送付済");
        when(invoiceMapper.selectOne(any())).thenReturn(invoice);

        com.ses.entity.InvoicePayment payment = new com.ses.entity.InvoicePayment();
        payment.setId(paymentId);
        payment.setInvoiceId(invoiceId);
        payment.setAmount(new BigDecimal("110000"));
        when(invoicePaymentMapper.selectById(paymentId)).thenReturn(payment);
        when(invoicePaymentMapper.selectList(any())).thenReturn(java.util.List.of());

        invoiceService.deletePayment(invoiceId, paymentId);

        // 消込解除が「入金の物理削除より先に」行われること（順序が逆だとFK違反になる）
        org.mockito.InOrder inOrder = inOrder(bankDepositMapper, invoicePaymentMapper);
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.Wrapper<com.ses.entity.BankDeposit>> captor =
                org.mockito.ArgumentCaptor.captor();
        inOrder.verify(bankDepositMapper).update(isNull(), captor.capture());
        inOrder.verify(invoicePaymentMapper).deleteById(paymentId);

        String setSql = captor.getValue().getSqlSet();
        assertTrue(setSql.contains("status"), setSql);
        assertTrue(setSql.contains("matched_invoice_id"), setSql);
        assertTrue(setSql.contains("matched_payment_id"), setSql);
    }

    // ===== INV-01: 一括督促の短トランザクション / i18n キー返却 =====

    @Test
    void sendReminders_doesNotLeakExceptionMessageToClient() {
        Invoice invoice = new Invoice();
        invoice.setId(11L);
        invoice.setStatus("送付済");
        invoice.setCustomerId(5L);
        invoice.setInvoiceNo("INV-R-1");
        invoice.setBillingMonth("2026-07");
        invoice.setTotal(new BigDecimal("110000"));
        invoice.setDueDate(LocalDate.now().minusDays(3));
        when(invoiceMapper.selectById(11L)).thenReturn(invoice);
        when(customerMapper.selectById(5L)).thenReturn(com.ses.entity.Customer.builder()
                .companyName("客A").contactEmail("ap@example.com").build());
        when(invoicePaymentMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        when(mailService.sendWithTemplate(any(), any(), any(), any()))
                .thenThrow(new RuntimeException("SMTP host secret leaked"));

        var results = invoiceService.sendReminders(java.util.List.of(11L), 7L, LocalDate.now());

        assertEquals(1, results.size());
        assertEquals("FAILED", results.get(0).getStatus());
        assertEquals("error.invoice.reminderFailed", results.get(0).getReason());
        assertFalse(results.get(0).getReason().contains("SMTP"));
        assertFalse(results.get(0).getReason().contains("secret"));
    }

    @Test
    void sendReminders_returnsI18nKeysForBusinessSkipsAndDuplicates() {
        Invoice paid = new Invoice();
        paid.setId(1L);
        paid.setStatus("入金済");
        paid.setDueDate(LocalDate.now().minusDays(1));
        Invoice overdue = new Invoice();
        overdue.setId(2L);
        overdue.setStatus("送付済");
        overdue.setCustomerId(5L);
        overdue.setInvoiceNo("INV-R-2");
        overdue.setBillingMonth("2026-07");
        overdue.setTotal(new BigDecimal("50000"));
        overdue.setDueDate(LocalDate.now().minusDays(2));
        when(invoiceMapper.selectById(1L)).thenReturn(paid);
        when(invoiceMapper.selectById(2L)).thenReturn(overdue);
        when(customerMapper.selectById(5L)).thenReturn(com.ses.entity.Customer.builder()
                .companyName("客B").contactEmail("b@example.com").build());
        when(invoicePaymentMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());
        when(mailService.sendWithTemplate(any(), any(), any(), any()))
                .thenReturn(new com.ses.dto.mail.MailDispatchResult(99L, "QUEUED"));

        var results = invoiceService.sendReminders(java.util.List.of(1L, 2L, 2L), 7L, LocalDate.now());

        assertEquals(3, results.size());
        assertEquals("SKIPPED", results.get(0).getStatus());
        assertEquals("error.invoice.reminderAlreadyPaid", results.get(0).getReason());
        assertEquals("QUEUED", results.get(1).getStatus());
        assertEquals("SKIPPED", results.get(2).getStatus());
        assertEquals("error.invoice.reminderDuplicate", results.get(2).getReason());
        verify(mailService, times(1)).sendWithTemplate(eq(7L), any(), eq("b@example.com"), eq(2L));
    }

    @Test
    void reminderFailureReason_hidesNonErrorMessages() {
        assertEquals("error.invoice.reminderFailed",
                InvoiceServiceImpl.reminderFailureReason(new RuntimeException("db password=secret")));
        assertEquals("error.invoice.notFound",
                InvoiceServiceImpl.reminderFailureReason(BusinessException.of("error.invoice.notFound")));
    }
}

