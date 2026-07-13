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

@Service
public class InvoiceServiceImpl extends ServiceImpl<InvoiceMapper, Invoice> implements InvoiceService {

    @Autowired
    private InvoiceItemMapper invoiceItemMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private SystemConfigService systemConfigService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Invoice generate(Long customerId, String billingMonth) {
        List<UnbilledWorkRecordDto> unbilledList = baseMapper.selectUnbilledWorkRecords(customerId, billingMonth);
        if (unbilledList == null || unbilledList.isEmpty()) {
            throw new BusinessException("請求対象の確定実績がありません");
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
        throw new BusinessException("請求書番号の採番に失敗しました。再試行してください。");
    }

    @Override
    public String generateInvoiceNo(String billingMonth) {
        String monthStr = billingMonth.replace("-", "");
        String prefix = "INV-" + monthStr + "-";
        
        QueryWrapper<Invoice> queryWrapper = new QueryWrapper<>();
        queryWrapper.likeRight("invoice_no", prefix);
        queryWrapper.orderByDesc("invoice_no");
        queryWrapper.last("LIMIT 1");
        
        Invoice maxInvoice = baseMapper.selectOne(queryWrapper);
        if (maxInvoice == null) {
            return prefix + "0001";
        }
        
        String maxNo = maxInvoice.getInvoiceNo();
        int seq = Integer.parseInt(maxNo.substring(prefix.length()));
        return String.format("%s%04d", prefix, seq + 1);
    }

    @Override
    public void changeStatus(Long id, String status, LocalDate paidDate) {
        Invoice invoice = this.getById(id);
        if (invoice == null) {
            throw new BusinessException("請求書が見つかりません");
        }
        invoice.setStatus(status);
        if ("入金済".equals(status) && paidDate != null) {
            invoice.setPaidDate(paidDate);
        }
        this.updateById(invoice);
    }

    @Override
    public InvoiceDetailDto detail(Long id) {
        Invoice invoice = this.getById(id);
        if (invoice == null) {
            throw new BusinessException("請求書が見つかりません");
        }

        Customer customer = customerMapper.selectById(invoice.getCustomerId());
        
        QueryWrapper<InvoiceItem> itemQuery = new QueryWrapper<>();
        itemQuery.eq("invoice_id", id);
        List<InvoiceItem> items = invoiceItemMapper.selectList(itemQuery);

        InvoiceDetailDto dto = new InvoiceDetailDto();
        org.springframework.beans.BeanUtils.copyProperties(invoice, dto);
        dto.setCustomer(customer);
        dto.setItems(items);

        return dto;
    }
}
