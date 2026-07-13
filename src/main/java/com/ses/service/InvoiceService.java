package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.InvoiceDetailDto;
import com.ses.entity.Invoice;

import java.time.LocalDate;

public interface InvoiceService extends IService<Invoice> {
    Invoice generate(Long customerId, String billingMonth);
    String generateInvoiceNo(String billingMonth);
    void changeStatus(Long id, String status, LocalDate paidDate);
    void changeBpPaymentStatus(Long id, String status, LocalDate paidDate);
    void voidInvoice(Long id);
    InvoiceDetailDto detail(Long id);
}
