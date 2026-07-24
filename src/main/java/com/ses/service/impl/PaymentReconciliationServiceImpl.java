package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.invoice.InvoiceBalanceDto;
import com.ses.dto.invoice.InvoicePaymentCreateRequest;
import com.ses.dto.invoice.InvoicePaymentResponse;
import com.ses.dto.reconciliation.BankDepositDto;
import com.ses.dto.reconciliation.PendingDepositDto;
import com.ses.dto.reconciliation.ReconciliationCandidateDto;
import com.ses.dto.reconciliation.ReconciliationFetchResultDto;
import com.ses.entity.BankDeposit;
import com.ses.mapper.BankDepositMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.service.FreeeIntegrationService;
import com.ses.service.InvoiceService;
import com.ses.service.billing.PaymentReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 入金消込の半自動化（FR-09）実装。
 * 突合候補は永続化せず、都度 {@link InvoiceMapper#selectOutstandingBalances()} を基に算出する
 * （請求書残高は他の入金・締め処理で変動しうるため、保存済みの分類は陳腐化しやすい）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentReconciliationServiceImpl implements PaymentReconciliationService {

    // スコア配分: 金額一致(高)・名義一致(中)・時期近傍(低)。高信頼(自動消込)は「金額一致＋名義一致」のみで判定し、
    // 時期は候補順位付けの補助情報として扱う（design 2章）。
    static final int WEIGHT_AMOUNT = 50;
    static final int WEIGHT_NAME = 30;
    static final int WEIGHT_DATE = 20;
    static final long DATE_WINDOW_DAYS = 45;
    private static final int MAX_CANDIDATES = 5;
    private static final List<String> CORPORATE_SUFFIXES = List.of(
            "株式会社", "（株）", "(株)", "有限会社", "（有）", "(有)", "合同会社",
            "一般社団法人", "公益社団法人", "特定非営利活動法人");

    private final BankDepositMapper bankDepositMapper;
    private final InvoiceMapper invoiceMapper;
    private final FreeeIntegrationService freeeIntegrationService;
    private final InvoiceService invoiceService;
    private final org.springframework.context.ApplicationContext applicationContext;

    @Override
    public ReconciliationFetchResultDto fetchAndReconcile(LocalDate from, LocalDate to) {
        LocalDate resolvedTo = to != null ? to : LocalDate.now();
        LocalDate resolvedFrom = from != null ? from : resolvedTo.minusDays(30);
        if (resolvedFrom.isAfter(resolvedTo)) {
            throw BusinessException.of(400, "error.reconciliation.invalidPeriod");
        }

        List<BankDepositDto> fetched = freeeIntegrationService.bankDeposits(resolvedFrom, resolvedTo);

        int newCount = 0;
        for (BankDepositDto d : fetched) {
            if (persistIfNew(d)) {
                newCount++;
            }
        }

        List<BankDeposit> unresolved = bankDepositMapper.selectList(
                new LambdaQueryWrapper<BankDeposit>().eq(BankDeposit::getStatus, "未消込"));

        int autoMatchedCount = 0;
        if (!unresolved.isEmpty()) {
            List<InvoiceBalanceDto> balances = invoiceMapper.selectOutstandingBalances();
            for (BankDeposit deposit : unresolved) {
                List<ReconciliationCandidateDto> candidates = scoreCandidates(deposit, balances);
                Long autoInvoiceId = resolveAutoMatch(candidates);
                if (autoInvoiceId != null && tryAutoApply(deposit.getId(), autoInvoiceId)) {
                    autoMatchedCount++;
                }
            }
        }

        long pendingCount = bankDepositMapper.selectCount(
                new LambdaQueryWrapper<BankDeposit>().eq(BankDeposit::getStatus, "未消込"));

        return new ReconciliationFetchResultDto(fetched.size(), newCount, autoMatchedCount, (int) pendingCount);
    }

    private boolean persistIfNew(BankDepositDto d) {
        if (d.getFreeeDepositId() == null || d.getFreeeDepositId().isBlank()) {
            return false;
        }
        BankDeposit entity = new BankDeposit();
        entity.setFreeeDepositId(d.getFreeeDepositId());
        entity.setDepositDate(d.getDepositDate());
        entity.setAmount(d.getAmount() != null ? d.getAmount() : BigDecimal.ZERO);
        entity.setPayerName(d.getPayerName());
        entity.setStatus("未消込");
        try {
            bankDepositMapper.insert(entity);
            return true;
        } catch (DuplicateKeyException e) {
            // 既存の freee_deposit_id（再取得時の重複）。冪等に無視する。
            return false;
        }
    }

    /**
     * 自動消込は「候補のうち金額一致＋名義一致」がちょうど1件の場合のみ行う。
     * 複数該当する場合は取り違えを避けるため自動確定せず、候補提示に回す。
     */
    private Long resolveAutoMatch(List<ReconciliationCandidateDto> candidates) {
        List<ReconciliationCandidateDto> highConfidence = candidates.stream()
                .filter(c -> c.isAmountMatch() && c.isNameMatch())
                .toList();
        return highConfidence.size() == 1 ? highConfidence.get(0).getInvoiceId() : null;
    }

    /** 自動消込を試みる。他の入金との競合等で失敗しても呼び出し元(fetch)全体は継続する。 */
    private boolean tryAutoApply(Long depositId, Long invoiceId) {
        try {
            applicationContext.getBean(PaymentReconciliationService.class).apply(depositId, invoiceId);
            return true;
        } catch (Exception e) {
            log.warn("入金自動消込に失敗しました depositId={} invoiceId={}", depositId, invoiceId, e);
            return false;
        }
    }

    @Override
    public List<PendingDepositDto> listPending() {
        List<BankDeposit> unresolved = bankDepositMapper.selectList(
                new LambdaQueryWrapper<BankDeposit>()
                        .eq(BankDeposit::getStatus, "未消込")
                        .orderByAsc(BankDeposit::getDepositDate));
        if (unresolved.isEmpty()) {
            return List.of();
        }

        List<InvoiceBalanceDto> balances = invoiceMapper.selectOutstandingBalances();
        List<PendingDepositDto> out = new ArrayList<>();
        for (BankDeposit deposit : unresolved) {
            List<ReconciliationCandidateDto> candidates = scoreCandidates(deposit, balances);
            List<ReconciliationCandidateDto> top = candidates.stream().limit(MAX_CANDIDATES).toList();

            PendingDepositDto dto = new PendingDepositDto();
            dto.setDepositId(deposit.getId());
            dto.setDepositDate(deposit.getDepositDate());
            dto.setAmount(deposit.getAmount());
            dto.setPayerName(deposit.getPayerName());
            dto.setClassification(top.isEmpty() ? "pending" : "candidate");
            dto.setCandidates(top);
            out.add(dto);
        }
        return out;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void apply(Long depositId, Long invoiceId) {
        if (invoiceId == null) {
            throw BusinessException.of(400, "error.reconciliation.invoiceRequired");
        }
        BankDeposit deposit = bankDepositMapper.selectOne(
                new QueryWrapper<BankDeposit>().eq("id", depositId).last("FOR UPDATE"));
        if (deposit == null) {
            throw BusinessException.of("error.reconciliation.depositNotFound");
        }
        if ("消込済".equals(deposit.getStatus())) {
            throw BusinessException.of(409, "error.reconciliation.alreadyReconciled");
        }

        InvoicePaymentCreateRequest request = new InvoicePaymentCreateRequest();
        request.setPaidDate(deposit.getDepositDate());
        request.setAmount(deposit.getAmount());
        request.setFee(BigDecimal.ZERO);
        request.setRemarks("入金消込(freee) 振込名義: " + (deposit.getPayerName() != null ? deposit.getPayerName() : ""));

        InvoicePaymentResponse payment = invoiceService.addPayment(invoiceId, request);

        int updated = bankDepositMapper.update(null, new UpdateWrapper<BankDeposit>()
                .eq("id", depositId)
                .eq("status", "未消込")
                .set("status", "消込済")
                .set("matched_invoice_id", invoiceId)
                .set("matched_payment_id", payment.getId()));
        if (updated == 0) {
            // FOR UPDATE で行ロック済みのため通常到達しないが、防御的にCASを二重チェックする。
            throw BusinessException.of(409, "error.reconciliation.alreadyReconciled");
        }
    }

    private List<ReconciliationCandidateDto> scoreCandidates(BankDeposit deposit, List<InvoiceBalanceDto> balances) {
        return balances.stream()
                .map(inv -> score(deposit, inv))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingInt(ReconciliationCandidateDto::getScore).reversed()
                        .thenComparing(c -> c.getDueDate() != null ? c.getDueDate() : LocalDate.MAX))
                .toList();
    }

    static ReconciliationCandidateDto score(BankDeposit deposit, InvoiceBalanceDto inv) {
        boolean amountMatch = inv.getBalance() != null && deposit.getAmount() != null
                && inv.getBalance().compareTo(deposit.getAmount()) == 0;
        boolean nameMatch = isNameMatch(inv.getCustomerName(), deposit.getPayerName());
        boolean dateNear = inv.getDueDate() != null && deposit.getDepositDate() != null
                && Math.abs(ChronoUnit.DAYS.between(inv.getDueDate(), deposit.getDepositDate())) <= DATE_WINDOW_DAYS;

        int score = (amountMatch ? WEIGHT_AMOUNT : 0) + (nameMatch ? WEIGHT_NAME : 0) + (dateNear ? WEIGHT_DATE : 0);
        if (score == 0) {
            return null;
        }

        ReconciliationCandidateDto dto = new ReconciliationCandidateDto();
        dto.setInvoiceId(inv.getInvoiceId());
        dto.setInvoiceNo(inv.getInvoiceNo());
        dto.setCustomerId(inv.getCustomerId());
        dto.setCustomerName(inv.getCustomerName());
        dto.setDueDate(inv.getDueDate());
        dto.setBalance(inv.getBalance());
        dto.setScore(score);
        dto.setAmountMatch(amountMatch);
        dto.setNameMatch(nameMatch);
        dto.setDateNear(dateNear);
        return dto;
    }

    /** 顧客名と振込名義の正規化一致判定（法人格表記・空白を除去した部分一致）。 */
    static boolean isNameMatch(String customerName, String payerName) {
        String a = normalizeName(customerName);
        String b = normalizeName(payerName);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.contains(b) || b.contains(a);
    }

    static String normalizeName(String s) {
        if (s == null) {
            return "";
        }
        String n = s.replaceAll("[\\s　]+", "");
        for (String suffix : CORPORATE_SUFFIXES) {
            n = n.replace(suffix, "");
        }
        return n.toLowerCase();
    }
}
