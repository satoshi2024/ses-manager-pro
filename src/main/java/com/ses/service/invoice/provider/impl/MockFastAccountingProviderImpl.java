package com.ses.service.invoice.provider.impl;

import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MockFastAccountingProviderImpl implements DigitalInvoiceProvider {

    private final Map<String, String> messageIdToProviderId = new ConcurrentHashMap<>();

    @Override
    public String sendInvoice(String xml, String specificationVersion, String messageId) {
        // Option B: messageId を Idempotency-Key として扱い、同一キーは同一 provider message を返す
        return messageIdToProviderId.computeIfAbsent(messageId,
                id -> "mock-fastaccounting-msg-" + id);
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        // モック実装: signatureが"valid-sig"の場合のみtrue
        return "valid-sig".equals(signatureHeader);
    }
}
