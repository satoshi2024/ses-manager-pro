package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.dto.InvoiceDetailDto;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import com.ses.entity.Customer;
import com.ses.entity.Invoice;
import com.ses.entity.InvoiceItem;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.InvoiceItemMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.entity.BpPayment;
import com.ses.service.InvoiceService;
import com.ses.service.SystemConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

@Service
public class InvoiceServiceImpl extends ServiceImpl<InvoiceMapper, Invoice> implements InvoiceService {

    private static final Map<String, Set<String>> ALLOWED = Map.of(
        "未送付", Set.of("送付済"),
        "送付済", Set.of("入金済", "未送付"),
        "入金済", Set.of("送付済")
    );

    @Autowired
    private InvoiceItemMapper invoiceItemMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private BpPaymentMapper bpPaymentMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Invoice generate(Long customerId, String billingMonth) {
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

        String oldStatus = invoice.getStatus();
        if (!ALLOWED.getOrDefault(oldStatus, Set.of()).contains(status)) {
            throw BusinessException.of("error.invoice.statusTransitionInvalid", oldStatus, status);
        }

        if ("入金済".equals(status)) {
            if (paidDate == null) {
                throw BusinessException.of("error.invoice.paymentDateRequired");
            }
            invoice.setStatus(status);
            invoice.setPaidDate(paidDate);
            this.updateById(invoice);
        } else if ("入金済".equals(oldStatus)) {
            this.update(new UpdateWrapper<Invoice>()
                    .eq("id", id)
                    .set("status", status)
                    .set("paid_date", null));
        } else {
            invoice.setStatus(status);
            this.updateById(invoice);
        }
    }

    @Override
    public void changeBpPaymentStatus(Long id, String status, LocalDate paidDate) {
        BpPayment bpPayment = bpPaymentMapper.selectById(id);
        if (bpPayment == null) {
            throw BusinessException.of("error.invoice.bpPaymentNotFound");
        }
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
        if ("入金済".equals(invoice.getStatus())) {
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
        BigDecimal rate = systemConfigService.getDecimal("billing.tax-rate", new BigDecimal("0.10"));
        dto.setTaxRatePercent(rate.multiply(new BigDecimal("100")).stripTrailingZeros().toPlainString());

        return dto;
    }
}
















