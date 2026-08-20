package com.ses.service.invoice;

import com.ses.entity.Customer;
import com.ses.service.CustomerService;
import com.ses.service.DigitalInvoiceService;
import com.ses.service.MailService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InvoiceDeliveryDispatcher {

    private final CustomerService customerService;
    private final DigitalInvoiceService digitalInvoiceService;
    private final MailService mailService;

    /**
     * 顧客の送付方法設定(delivery_preference)に基づき、請求書の送付をディスパッチする。
     */
    public void dispatch(Long invoiceId, Long customerId, String specVersion) {
        Customer customer = customerService.getById(customerId);
        if (customer == null) {
            throw new IllegalArgumentException("Customer not found: " + customerId);
        }

        String pref = customer.getDeliveryPreference();
        if ("PEPPOL".equalsIgnoreCase(pref)) {
            // JP PINT送信キューに登録
            digitalInvoiceService.enqueueInvoiceForSend(invoiceId, specVersion, customerId);
        } else if ("EMAIL".equalsIgnoreCase(pref)) {
            // PDF/EMAIL フォールバック (既存処理のモック)
            mailService.send(customer.getContactEmail(), "請求書送付のお知らせ", "請求書を添付します。", invoiceId);
        } else {
            // PDF (手動送付等)
            // 何もしない、もしくはPDF生成タスクキューに積む
        }
    }
}
