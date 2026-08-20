package com.ses.service.invoice.provider.impl;

import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class MockFastAccountingProviderImpl implements DigitalInvoiceProvider {

    @Override
    public String sendInvoice(String xml, String specificationVersion, String messageId) {
        // モック実装: Sandbox未契約のため、ダミーのprovider message IDを返す
        return "mock-fastaccounting-msg-" + UUID.randomUUID();
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        // モック実装: signatureが"valid-sig"の場合のみtrue
        return "valid-sig".equals(signatureHeader);
    }
}

