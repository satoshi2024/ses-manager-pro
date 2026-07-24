package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.invoice.InvoiceBalanceDto;
import com.ses.dto.invoice.InvoicePaymentCreateRequest;
import com.ses.dto.invoice.InvoicePaymentResponse;
import com.ses.dto.reconciliation.BankDepositDto;
import com.ses.dto.reconciliation.PendingDepositDto;
import com.ses.dto.reconciliation.ReconciliationFetchResultDto;
import com.ses.entity.BankDeposit;
import com.ses.mapper.BankDepositMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.InvoiceService;
import com.ses.service.billing.PaymentReconciliationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentReconciliationServiceImplTest {

    @Mock
    private BankDepositMapper bankDepositMapper;

    @Mock
    private InvoiceMapper invoiceMapper;

    @Mock
    private FreeeIntegrationService freeeIntegrationService;

    @Mock
    private InvoiceService invoiceService;

    @Mock
    private ApplicationContext applicationContext;

    @InjectMocks
    private PaymentReconciliationServiceImpl service;

    @BeforeEach
    void setUp() {
        // fetchAndReconcile内の自動消込はSpringプロキシ経由(@Transactional有効化)で自身のapply()を呼ぶため、
        // ApplicationContext#getBean が自分自身を返すようにする（既存のFreeeIntegrationServiceImpl#refresh呼び出しと同じ手法）。
        lenient().when(applicationContext.getBean(PaymentReconciliationService.class)).thenReturn(service);
    }

    private BankDepositDto depositDto(String freeeId, LocalDate date, BigDecimal amount, String payerName) {
        BankDepositDto d = new BankDepositDto();
        d.setFreeeDepositId(freeeId);
        d.setDepositDate(date);
        d.setAmount(amount);
        d.setPayerName(payerName);
        return d;
    }

    private BankDeposit depositEntity(Long id, LocalDate date, BigDecimal amount, String payerName, String status) {
        BankDeposit e = new BankDeposit();
        e.setId(id);
        e.setDepositDate(date);
        e.setAmount(amount);
        e.setPayerName(payerName);
        e.setStatus(status);
        return e;
    }

    private InvoiceBalanceDto invoiceBalance(Long invoiceId, String invoiceNo, Long customerId, String customerName,
                                              BigDecimal balance, LocalDate dueDate) {
        InvoiceBalanceDto b = new InvoiceBalanceDto();
        b.setInvoiceId(invoiceId);
        b.setInvoiceNo(invoiceNo);
        b.setCustomerId(customerId);
        b.setCustomerName(customerName);
        b.setBalance(balance);
        b.setDueDate(dueDate);
        return b;
    }

    // ===== fetchAndReconcile: 高信頼一致(金額＋名義)は自動消込される =====
    @Test
    void fetchAndReconcile_autoMatchesWhenAmountAndNameMatch() {
        LocalDate depositDate = LocalDate.of(2026, 7, 20);
        when(freeeIntegrationService.bankDeposits(any(), any()))
                .thenReturn(List.of(depositDto("D1", depositDate, new BigDecimal("550000"), "サンプル商事株式会社")));
        when(bankDepositMapper.insert(any(BankDeposit.class))).thenReturn(1);

        BankDeposit unresolved = depositEntity(1L, depositDate, new BigDecimal("550000"), "サンプル商事株式会社", "未消込");
        when(bankDepositMapper.selectList(any())).thenReturn(List.of(unresolved));
        when(invoiceMapper.selectOutstandingBalances()).thenReturn(List.of(
                invoiceBalance(55L, "INV-202607-0003", 9L, "サンプル商事", new BigDecimal("550000"), LocalDate.of(2026, 8, 31))
        ));

        // apply()内部のFOR UPDATE取得
        when(bankDepositMapper.selectOne(any())).thenReturn(unresolved);
        InvoicePaymentResponse paymentResponse = new InvoicePaymentResponse();
        paymentResponse.setId(999L);
        when(invoiceService.addPayment(eq(55L), any(InvoicePaymentCreateRequest.class))).thenReturn(paymentResponse);
        when(bankDepositMapper.update(isNull(), any())).thenReturn(1);
        when(bankDepositMapper.selectCount(any())).thenReturn(0L);

        ReconciliationFetchResultDto result = service.fetchAndReconcile(depositDate.minusDays(1), depositDate);

        assertEquals(1, result.getFetchedCount());
        assertEquals(1, result.getNewCount());
        assertEquals(1, result.getAutoMatchedCount());
        assertEquals(0, result.getPendingCount());

        ArgumentCaptor<InvoicePaymentCreateRequest> captor = ArgumentCaptor.forClass(InvoicePaymentCreateRequest.class);
        verify(invoiceService, times(1)).addPayment(eq(55L), captor.capture());
        assertEquals(new BigDecimal("550000"), captor.getValue().getAmount());
        assertEquals(depositDate, captor.getValue().getPaidDate());
    }

    // ===== fetchAndReconcile: 重複したfreee_deposit_idは新規カウントされない(冪等) =====
    @Test
    void fetchAndReconcile_skipsDuplicateFreeeDepositId() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        when(freeeIntegrationService.bankDeposits(any(), any()))
                .thenReturn(List.of(depositDto("D1", date, new BigDecimal("1000"), "テスト")));
        when(bankDepositMapper.insert(any(BankDeposit.class))).thenThrow(new DuplicateKeyException("dup"));
        when(bankDepositMapper.selectList(any())).thenReturn(List.of());
        when(bankDepositMapper.selectCount(any())).thenReturn(0L);

        ReconciliationFetchResultDto result = service.fetchAndReconcile(date.minusDays(1), date);

        assertEquals(1, result.getFetchedCount());
        assertEquals(0, result.getNewCount());
        assertEquals(0, result.getAutoMatchedCount());
        verify(invoiceService, never()).addPayment(any(), any());
    }

    // ===== fetchAndReconcile: 複数請求書が高信頼一致した場合は取り違え防止のため自動確定しない =====
    @Test
    void fetchAndReconcile_doesNotAutoApplyWhenMultipleHighConfidenceMatches() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        when(freeeIntegrationService.bankDeposits(any(), any()))
                .thenReturn(List.of(depositDto("D1", date, new BigDecimal("100000"), "サンプル商事株式会社")));
        when(bankDepositMapper.insert(any(BankDeposit.class))).thenReturn(1);

        BankDeposit unresolved = depositEntity(1L, date, new BigDecimal("100000"), "サンプル商事株式会社", "未消込");
        when(bankDepositMapper.selectList(any())).thenReturn(List.of(unresolved));
        when(invoiceMapper.selectOutstandingBalances()).thenReturn(List.of(
                invoiceBalance(1L, "INV-1", 9L, "サンプル商事", new BigDecimal("100000"), date),
                invoiceBalance(2L, "INV-2", 9L, "サンプル商事", new BigDecimal("100000"), date)
        ));
        when(bankDepositMapper.selectCount(any())).thenReturn(1L);

        ReconciliationFetchResultDto result = service.fetchAndReconcile(date.minusDays(1), date);

        assertEquals(0, result.getAutoMatchedCount());
        assertEquals(1, result.getPendingCount());
        verify(invoiceService, never()).addPayment(any(), any());
    }

    // ===== listPending: 金額のみ一致 → 候補提示（自動確定しない） =====
    @Test
    void listPending_classifiesAsCandidateWhenOnlyAmountMatches() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        BankDeposit unresolved = depositEntity(1L, date, new BigDecimal("300000"), "フリコミ ホゲカブ", "未消込");
        when(bankDepositMapper.selectList(any())).thenReturn(List.of(unresolved));
        when(invoiceMapper.selectOutstandingBalances()).thenReturn(List.of(
                invoiceBalance(1L, "INV-1", 1L, "全然違う株式会社", new BigDecimal("300000"), LocalDate.of(2020, 1, 1))
        ));

        List<PendingDepositDto> pending = service.listPending();

        assertEquals(1, pending.size());
        assertEquals("candidate", pending.get(0).getClassification());
        assertEquals(1, pending.get(0).getCandidates().size());
        assertTrue(pending.get(0).getCandidates().get(0).isAmountMatch());
        assertFalse(pending.get(0).getCandidates().get(0).isNameMatch());
    }

    // ===== listPending: 過入金(金額不一致だが名義一致) → 候補止まり。自動消込されない =====
    @Test
    void listPending_overpaymentWithNameMatchIsCandidateNotAuto() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        BankDeposit unresolved = depositEntity(1L, date, new BigDecimal("600000"), "サンプル商事株式会社", "未消込");
        when(bankDepositMapper.selectList(any())).thenReturn(List.of(unresolved));
        when(invoiceMapper.selectOutstandingBalances()).thenReturn(List.of(
                invoiceBalance(1L, "INV-1", 1L, "サンプル商事", new BigDecimal("550000"), date)
        ));

        List<PendingDepositDto> pending = service.listPending();

        assertEquals("candidate", pending.get(0).getClassification());
        assertFalse(pending.get(0).getCandidates().get(0).isAmountMatch());
        assertTrue(pending.get(0).getCandidates().get(0).isNameMatch());
    }

    // ===== listPending: 金額・名義とも不一致 → 保留（候補なし） =====
    @Test
    void listPending_classifiesAsPendingWhenNoCandidateFound() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        BankDeposit unresolved = depositEntity(1L, date, new BigDecimal("777"), "不明", "未消込");
        when(bankDepositMapper.selectList(any())).thenReturn(List.of(unresolved));
        when(invoiceMapper.selectOutstandingBalances()).thenReturn(List.of(
                invoiceBalance(1L, "INV-1", 1L, "全く関係ない会社", new BigDecimal("999999"), LocalDate.of(2020, 1, 1))
        ));

        List<PendingDepositDto> pending = service.listPending();

        assertEquals("pending", pending.get(0).getClassification());
        assertTrue(pending.get(0).getCandidates().isEmpty());
    }

    // ===== apply: 手動確定で正しくrecalcPaymentStatus連動(addPayment)を呼び、消込済へ遷移する =====
    @Test
    void apply_manuallyConfirmsAndDelegatesToInvoiceServiceAddPayment() {
        LocalDate date = LocalDate.of(2026, 7, 20);
        BankDeposit unresolved = depositEntity(1L, date, new BigDecimal("200000"), "テスト入金", "未消込");
        when(bankDepositMapper.selectOne(any())).thenReturn(unresolved);
        InvoicePaymentResponse resp = new InvoicePaymentResponse();
        resp.setId(42L);
        when(invoiceService.addPayment(eq(10L), any())).thenReturn(resp);
        when(bankDepositMapper.update(isNull(), any())).thenReturn(1);

        service.apply(1L, 10L);

        verify(invoiceService, times(1)).addPayment(eq(10L), any());
        verify(bankDepositMapper, times(1)).update(isNull(), any());
    }

    // ===== apply: 二重消込防止。既に消込済の入金へ再度applyすると例外になる =====
    @Test
    void apply_rejectsDoubleReconciliation() {
        BankDeposit alreadyDone = depositEntity(1L, LocalDate.now(), new BigDecimal("100"), "x", "消込済");
        when(bankDepositMapper.selectOne(any())).thenReturn(alreadyDone);

        assertThrows(BusinessException.class, () -> service.apply(1L, 10L));
        verify(invoiceService, never()).addPayment(any(), any());
    }

    @Test
    void apply_throwsWhenDepositNotFound() {
        when(bankDepositMapper.selectOne(any())).thenReturn(null);
        assertThrows(BusinessException.class, () -> service.apply(999L, 10L));
    }

    // ===== 突合スコアリングの純関数テスト =====
    @Test
    void normalizeName_stripsCorporateSuffixesAndWhitespace() {
        assertEquals(PaymentReconciliationServiceImpl.normalizeName("サンプル商事"),
                PaymentReconciliationServiceImpl.normalizeName("株式会社サンプル商事"));
        assertEquals("", PaymentReconciliationServiceImpl.normalizeName(null));
    }

    @Test
    void isNameMatch_trueForNormalizedSubstringMatch() {
        assertTrue(PaymentReconciliationServiceImpl.isNameMatch("サンプル商事株式会社", "サンプル商事"));
        assertFalse(PaymentReconciliationServiceImpl.isNameMatch("サンプル商事株式会社", "全く関係ない会社"));
        assertFalse(PaymentReconciliationServiceImpl.isNameMatch(null, "サンプル商事"));
    }

    @Test
    void score_returnsNullWhenNoFactorMatches() {
        BankDeposit deposit = depositEntity(1L, LocalDate.of(2026, 1, 1), new BigDecimal("1"), "不明", "未消込");
        InvoiceBalanceDto inv = invoiceBalance(1L, "INV-1", 1L, "関係ない会社", new BigDecimal("999999999"), LocalDate.of(2000, 1, 1));
        assertNull(PaymentReconciliationServiceImpl.score(deposit, inv));
    }
}
