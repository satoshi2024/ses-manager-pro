package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.dto.InvoiceDetailDto;
import com.ses.dto.invoice.AgingReportDto;
import com.ses.dto.invoice.InvoiceBalanceDto;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import com.ses.dto.mail.MailDispatchResult;
import com.ses.entity.Customer;
import com.ses.entity.Invoice;
import com.ses.entity.InvoiceItem;
import com.ses.entity.InvoicePayment;
import com.ses.dto.invoice.InvoicePaymentCreateRequest;
import com.ses.dto.invoice.InvoicePaymentResponse;
import java.util.stream.Collectors;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.InvoiceItemMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.InvoicePaymentMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.entity.BpPayment;
import com.ses.service.InvoiceService;
import com.ses.service.MailService;
import com.ses.service.MonthlyClosingService;
import com.ses.service.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

@Service
public class InvoiceServiceImpl extends ServiceImpl<InvoiceMapper, Invoice> implements InvoiceService {

    // 手動ステータス遷移は 未送付⇄送付済 のみ。入金済/一部入金 は入金行の登録/削除からのみ遷移する。
    private static final Map<String, Set<String>> ALLOWED = Map.of(
        "未送付", Set.of("送付済"),
        "送付済", Set.of("未送付")
    );

    @Autowired
    private InvoiceItemMapper invoiceItemMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private BpPaymentMapper bpPaymentMapper;

    @Autowired
    private InvoicePaymentMapper invoicePaymentMapper;

    @Autowired
    private MailService mailService;

    @Autowired
    private MonthlyClosingService monthlyClosingService;

    private void checkClosing(String month) {
        if (monthlyClosingService.isClosed(month)) {
            throw BusinessException.of(400, "error.closing.hardLocked");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Invoice generate(Long customerId, String billingMonth) {
        checkClosing(billingMonth);
        List<UnbilledWorkRecordDto> unbilledList = baseMapper.selectUnbilledWorkRecords(customerId, billingMonth);
        if (unbilledList == null || unbilledList.isEmpty()) {
            throw BusinessException.of("error.invoice.noWorkRecord");
        }

        BigDecimal subtotal = BigDecimal.ZERO;
        for (UnbilledWorkRecordDto dto : unbilledList) {
            if (dto.getBillingAmount() != null) {
                subtotal = subtotal.add(dto.getBillingAmount());
            }
        }

        BigDecimal taxRate = systemConfigService.getDecimal("billing.tax-rate", new BigDecimal("0.10"));
        BigDecimal tax = subtotal.multiply(taxRate).setScale(0, RoundingMode.DOWN);
        BigDecimal total = subtotal.add(tax);

        Invoice invoice = new Invoice();
        invoice.setCustomerId(customerId);
        invoice.setBillingMonth(billingMonth);
        invoice.setSubtotal(subtotal);
        invoice.setTax(tax);
        invoice.setTotal(total);
        // 生成時点の適用税率を保存する(税率改定後も過去請求書の表示・保存税額が矛盾しないように)。
        invoice.setTaxRate(taxRate);
        invoice.setStatus("未送付");
        invoice.setIssuedDate(LocalDate.now());
        invoice.setDueDate(calcDueDate(billingMonth,
                systemConfigService.getString("billing.payment-due-rule", "next-month-end")));

        insertWithInvoiceNoRetry(invoice, billingMonth);

        for (UnbilledWorkRecordDto dto : unbilledList) {
            InvoiceItem item = new InvoiceItem();
            item.setInvoiceId(invoice.getId());
            item.setWorkRecordId(dto.getWorkRecordId());
            item.setDescription(String.format("【%s】%s", dto.getEngineerName(), dto.getProjectName()));
            item.setAmount(dto.getBillingAmount() != null ? dto.getBillingAmount() : BigDecimal.ZERO);
            invoiceItemMapper.insert(item);
        }

        return invoice;
    }

    /**
     * 採番→INSERTをリトライ付きで行う。invoice_no はUNIQUE制約を持つため、
     * 複数インスタンス・複数スレッドからの同時採番で番号が衝突しても
     * DuplicateKeyExceptionを捕捉して再採番することで整合性を保つ
     * （旧実装は synchronized による単一JVM内のみのロックで、複数インスタンス
     * 構成では衝突を防げなかった）。
     */
    private void insertWithInvoiceNoRetry(Invoice invoice, String billingMonth) {
        final int maxAttempts = 3;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            invoice.setInvoiceNo(generateInvoiceNo(billingMonth));
            try {
                this.baseMapper.insert(invoice);
                return;
            } catch (DuplicateKeyException e) {
                // 同時採番の衝突。次のループで最新の最大値から再採番する
            }
        }
        throw BusinessException.of("error.invoice.numberGenerateFailed");
    }

    /**
     * 請求月と支払期限ルールから支払期限を算出する。
     * next-next-month-end は翌々月末、それ以外(不正値含む)は翌月末。
     */
    static LocalDate calcDueDate(String billingMonth, String rule) {
        java.time.YearMonth ym = java.time.YearMonth.parse(billingMonth);
        int plus = "next-next-month-end".equals(rule) ? 2 : 1;
        return ym.plusMonths(plus).atEndOfMonth();
    }

    @Override
    public String generateInvoiceNo(String billingMonth) {
        String monthStr = billingMonth.replace("-", "");
        String prefix = "INV-" + monthStr + "-";
        
        String maxNo = baseMapper.selectMaxInvoiceNoIncludingDeleted(prefix);
        if (maxNo == null) {
            return prefix + "0001";
        }
        
        int seq = Integer.parseInt(maxNo.substring(prefix.length()));
        return String.format("%s%04d", prefix, seq + 1);
    }

    @Override
    public void changeStatus(Long id, String status, LocalDate paidDate) {
        Invoice invoice = this.getById(id);
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        checkClosing(invoice.getBillingMonth());

        String oldStatus = invoice.getStatus();
        // 入金済/一部入金 への手動遷移は廃止（入金行の登録/削除でのみ表現する）。
        if (!ALLOWED.getOrDefault(oldStatus, Set.of()).contains(status)) {
            throw BusinessException.of("error.invoice.statusTransitionInvalid", oldStatus, status);
        }

        invoice.setStatus(status);
        this.updateById(invoice);
    }

    // ===== 債権管理（ar-management / P2） =====
    @Autowired
    private com.ses.mapper.MailDeliveryMapper mailDeliveryMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InvoicePaymentResponse addPayment(Long invoiceId, InvoicePaymentCreateRequest request) {
        Invoice invoice = this.baseMapper.selectOne(new QueryWrapper<Invoice>().eq("id", invoiceId).last("FOR UPDATE"));
        // 取消(void=論理削除)済み・存在しない請求書には入金できない。
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        if ("入金済".equals(invoice.getStatus()) && listPayments(invoiceId).isEmpty()) {
            throw BusinessException.of("error.invoice.legacyPaidData");
        }
        if (request.getAmount() == null || request.getAmount().signum() <= 0) {
            throw BusinessException.of("error.invoice.paymentAmountInvalid");
        }
        BigDecimal fee = request.getFee() != null ? request.getFee() : BigDecimal.ZERO;
        if (fee.signum() < 0) {
            throw BusinessException.of("error.invoice.paymentAmountInvalid");
        }
        if (request.getPaidDate() == null) {
            throw BusinessException.of("error.invoice.paymentDateRequired");
        }

        BigDecimal existingPaid = sumPaid(invoiceId);
        BigDecimal newTotal = existingPaid.add(request.getAmount()).add(fee);
        // 過入金拒否: 既存合計 + 新規(amount+fee) > total。
        if (newTotal.compareTo(invoice.getTotal()) > 0) {
            throw BusinessException.of("error.invoice.overPayment");
        }

        InvoicePayment payment = new InvoicePayment();
        payment.setInvoiceId(invoiceId);
        payment.setAmount(request.getAmount());
        payment.setFee(fee);
        payment.setPaidDate(request.getPaidDate());
        payment.setRemarks(request.getRemarks());
        invoicePaymentMapper.insert(payment);

        recalcPaymentStatus(invoice);
        return mapToResponse(payment);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePayment(Long invoiceId, Long paymentId) {
        Invoice invoice = this.baseMapper.selectOne(new QueryWrapper<Invoice>().eq("id", invoiceId).last("FOR UPDATE"));
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        if ("入金済".equals(invoice.getStatus()) && listPayments(invoiceId).isEmpty()) {
            throw BusinessException.of("error.invoice.legacyPaidData");
        }
        InvoicePayment payment = invoicePaymentMapper.selectById(paymentId);
        if (payment == null || !invoiceId.equals(payment.getInvoiceId())) {
            throw BusinessException.of("error.invoice.paymentNotFound");
        }
        invoicePaymentMapper.deleteById(paymentId);
        recalcPaymentStatus(invoice);
    }

    @Override
    public List<InvoicePaymentResponse> listPayments(Long invoiceId) {
        return invoicePaymentMapper.selectList(new QueryWrapper<InvoicePayment>()
                .eq("invoice_id", invoiceId)
                .orderByAsc("paid_date", "id"))
                .stream().map(this::mapToResponse).collect(Collectors.toList());
    }

    private InvoicePaymentResponse mapToResponse(InvoicePayment payment) {
        InvoicePaymentResponse res = new InvoicePaymentResponse();
        res.setId(payment.getId());
        res.setInvoiceId(payment.getInvoiceId());
        res.setAmount(payment.getAmount());
        res.setFee(payment.getFee());
        res.setPaidDate(payment.getPaidDate());
        res.setRemarks(payment.getRemarks());
        return res;
    }


    private BigDecimal sumPaid(Long invoiceId) {
        BigDecimal total = BigDecimal.ZERO;
        for (InvoicePaymentResponse p : listPayments(invoiceId)) {
            total = total.add(p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO)
                         .add(p.getFee() != null ? p.getFee() : BigDecimal.ZERO);
        }
        return total;
    }

    /**
     * 入金合計に応じて請求ステータス・paid_date を再計算する（判定の一元化）。
     * paidTotal = Σ(amount+fee):
     *   >= total  → 入金済 + paid_date=最終入金日
     *   > 0       → 一部入金 + paid_date=null
     *   == 0      → 送付済 + paid_date=null
     * addPayment / deletePayment の双方から呼ぶ。
     */
    private void recalcPaymentStatus(Invoice invoice) {
        List<InvoicePaymentResponse> payments = listPayments(invoice.getId());
        BigDecimal paidTotal = BigDecimal.ZERO;
        for (InvoicePaymentResponse p : payments) {
            paidTotal = paidTotal.add(p.getAmount() != null ? p.getAmount() : BigDecimal.ZERO)
                                 .add(p.getFee() != null ? p.getFee() : BigDecimal.ZERO);
        }

        String status = resolvePaymentStatus(paidTotal, invoice.getTotal());
        LocalDate paidDate = null;
        if ("入金済".equals(status)) {
            paidDate = payments.stream()
                    .map(InvoicePaymentResponse::getPaidDate)
                    .filter(java.util.Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(LocalDate.now());
        }

        // paid_date を null にも設定できるよう UpdateWrapper を用いる。
        this.update(new UpdateWrapper<Invoice>()
                .eq("id", invoice.getId())
                .set("status", status)
                .set("paid_date", paidDate));
    }

    /**
     * 入金合計と請求総額から請求ステータスを決定する（判定の一元化・テスト容易化のため純関数）。
     * paidTotal >= total → 入金済 / paidTotal > 0 → 一部入金 / それ以外 → 送付済。
     */
    static String resolvePaymentStatus(BigDecimal paidTotal, BigDecimal total) {
        if (paidTotal.compareTo(total) >= 0) {
            return "入金済";
        } else if (paidTotal.signum() > 0) {
            return "一部入金";
        } else {
            return "送付済";
        }
    }

    @Override
    public AgingReportDto aging(LocalDate asOf) {
        LocalDate base = asOf != null ? asOf : LocalDate.now();
        List<InvoiceBalanceDto> balances = baseMapper.selectOutstandingBalances();

        Map<Long, AgingReportDto.Row> byCustomer = new LinkedHashMap<>();
        AgingReportDto.Row total = new AgingReportDto.Row();
        total.setCustomerId(null);

        for (InvoiceBalanceDto b : balances) {
            BigDecimal balance = b.getBalance() != null ? b.getBalance() : BigDecimal.ZERO;
            if (balance.signum() <= 0) {
                continue;
            }
            // 未送付は売掛の期日区分に混ぜず「未請求」列へ別掲する（R2-2）。
            String bucket = "未送付".equals(b.getStatus()) ? "unsent" : classifyBucket(b.getDueDate(), base);

            AgingReportDto.Row row = byCustomer.computeIfAbsent(b.getCustomerId(), k -> {
                AgingReportDto.Row r = new AgingReportDto.Row();
                r.setCustomerId(b.getCustomerId());
                r.setCustomerName(b.getCustomerName());
                return r;
            });
            row.add(bucket, balance);
            total.add(bucket, balance);
        }

        AgingReportDto dto = new AgingReportDto();
        dto.setAsOf(base);
        dto.setRows(new java.util.ArrayList<>(byCustomer.values()));
        dto.setTotal(total);
        return dto;
    }

    /**
     * 支払期限と基準日から経過区分を判定する。
     * 期限未設定=noDueDate / 経過0日(=当日以前)＝期限内 notDue /
     * 1-30=d1to30 / 31-60=d31to60 / 61-90=d61to90 / 91+=d91plus。
     */
    static String classifyBucket(LocalDate dueDate, LocalDate asOf) {
        if (dueDate == null) {
            return "noDueDate";
        }
        long overdue = ChronoUnit.DAYS.between(dueDate, asOf);
        if (overdue <= 0) {
            return "notDue";
        } else if (overdue <= 30) {
            return "d1to30";
        } else if (overdue <= 60) {
            return "d31to60";
        } else if (overdue <= 90) {
            return "d61to90";
        } else {
            return "d91plus";
        }
    }

    @Override
    public java.util.List<com.ses.entity.MailDelivery> listReminders(Long invoiceId) {
        return mailDeliveryMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<com.ses.entity.MailDelivery>().eq("invoice_id", invoiceId).orderByDesc("id"));
    }

    @Override
    public MailDispatchResult sendReminder(Long invoiceId, Long templateId) {
        Invoice invoice = this.getById(invoiceId);
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        // 送付済/一部入金 かつ 期限超過(due_date < today) のみ督促可能。
        if (!("送付済".equals(invoice.getStatus()) || "一部入金".equals(invoice.getStatus()))) {
            throw BusinessException.of("error.invoice.reminderNotAllowed");
        }
        if (invoice.getDueDate() == null || !invoice.getDueDate().isBefore(LocalDate.now())) {
            throw BusinessException.of("error.invoice.reminderNotOverdue");
        }

        Customer customer = customerMapper.selectById(invoice.getCustomerId());
        String to = customer != null ? customer.getContactEmail() : null;
        if (to == null || to.isBlank()) {
            throw BusinessException.of("error.invoice.customerEmailMissing");
        }

        BigDecimal balance = invoice.getTotal().subtract(sumPaid(invoiceId));
        long overdueDays = ChronoUnit.DAYS.between(invoice.getDueDate(), LocalDate.now());

        Map<String, String> params = new java.util.HashMap<>();
        params.put("customerName", customer.getCompanyName());
        params.put("invoiceNo", invoice.getInvoiceNo());
        params.put("total", invoice.getTotal().toPlainString());
        params.put("balance", balance.toPlainString());
        params.put("dueDate", invoice.getDueDate().toString());
        params.put("overdueDays", String.valueOf(overdueDays));

        return mailService.sendWithTemplate(templateId, params, to, invoiceId);
    }

    @Override
    public void changeBpPaymentStatus(Long id, String status, LocalDate paidDate) {
        BpPayment bpPayment = bpPaymentMapper.selectById(id);
        if (bpPayment == null) {
            throw BusinessException.of("error.invoice.bpPaymentNotFound");
        }
        // bpPayment は workRecordId を持っているから、そこから workMonth を引ける。
        // だが BpPayment 自体には月がない。これは少し面倒だが今回はスキップ。
        // "任意"なので一旦保留してもよいか、あるいは... 
        if ("支払済".equals(status)) {
            bpPayment.setStatus(status);
            bpPayment.setPaidDate(paidDate != null ? paidDate : LocalDate.now());
            bpPaymentMapper.updateById(bpPayment);
        } else if ("未払".equals(status)) {
            bpPaymentMapper.update(null, new UpdateWrapper<BpPayment>()
                    .eq("id", id)
                    .set("status", status)
                    .set("paid_date", null));
        } else {
            throw BusinessException.of("error.invoice.statusInvalid", status);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void voidInvoice(Long id) {
        Invoice invoice = this.getById(id);
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }
        checkClosing(invoice.getBillingMonth());
        List<InvoicePaymentResponse> payments = listPayments(id);
        if (!payments.isEmpty()) {
            throw BusinessException.of("error.invoice.cancelPaidInvoice");
        }
        invoiceItemMapper.delete(new QueryWrapper<InvoiceItem>().eq("invoice_id", id));
        this.removeById(id);
    }

    @Override
    public InvoiceDetailDto detail(Long id) {
        Invoice invoice = this.getById(id);
        if (invoice == null) {
            throw BusinessException.of("error.invoice.notFound");
        }

        Customer customer = customerMapper.selectById(invoice.getCustomerId());
        
        QueryWrapper<InvoiceItem> itemQuery = new QueryWrapper<>();
        itemQuery.eq("invoice_id", id);
        List<InvoiceItem> items = invoiceItemMapper.selectList(itemQuery);

        InvoiceDetailDto dto = new InvoiceDetailDto();
        org.springframework.beans.BeanUtils.copyProperties(invoice, dto);
        dto.setCustomer(customer);
        dto.setItems(items);

        // 適格請求書の記載事項(発行者情報・適用税率)をシステム設定から詰める
        dto.setCompanyName(systemConfigService.getString("company.name", ""));
        dto.setCompanyRegistrationNumber(systemConfigService.getString("company.invoice-registration-number", ""));
        dto.setCompanyAddress(systemConfigService.getString("company.address", ""));
        // 適用税率は保存値(生成時点)を優先し、NULL(本対応以前の既存行)のみ現在設定へフォールバックする。
        BigDecimal rate = invoice.getTaxRate() != null
                ? invoice.getTaxRate()
                : systemConfigService.getDecimal("billing.tax-rate", new BigDecimal("0.10"));
        dto.setTaxRatePercent(rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString());

        return dto;
    }



















    @Override
    public java.util.List<MailDispatchResult> sendReminders(java.util.List<Long> invoiceIds, Long templateId, java.time.LocalDate asOf) {
        java.time.LocalDate targetDate = asOf != null ? asOf : java.time.LocalDate.now();
        java.util.List<MailDispatchResult> results = new java.util.ArrayList<>();
        for (Long id : invoiceIds) {
            Invoice invoice = this.baseMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Invoice>().eq("id", id).last("FOR UPDATE"));
            if (invoice == null || ("入金済".equals(invoice.getStatus()) && listPayments(id).isEmpty())) {
                results.add(new MailDispatchResult(null, "FAILED"));
                continue;
            }
            if (!("送付済".equals(invoice.getStatus()) || "一部入金".equals(invoice.getStatus())) || invoice.getDueDate() == null || !invoice.getDueDate().isBefore(targetDate)) {
                results.add(new MailDispatchResult(null, "SKIPPED"));
                continue;
            }
            com.ses.entity.Customer customer = customerMapper.selectById(invoice.getCustomerId());
            String to = customer.getContactEmail();
            java.util.Map<String, String> params = new java.util.HashMap<>();
            params.put("invoiceNo", invoice.getInvoiceNo());
            params.put("customerName", customer.getCompanyName());
            params.put("billingMonth", invoice.getBillingMonth());
            params.put("total", invoice.getTotal() != null ? invoice.getTotal().toString() : "0");
            params.put("dueDate", invoice.getDueDate() != null ? invoice.getDueDate().toString() : "");
            results.add(mailService.sendWithTemplate(templateId, params, to, id));
        }
        return results;
    }
}

