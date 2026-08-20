package com.ses.service.invoice.provider.impl;

import com.ses.common.exception.BusinessException;
import com.ses.service.invoice.provider.DigitalInvoiceProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 本番で実プロバイダ未配線時の fail-closed 実装（S16-P1-03）。
 * Mock は prod に載せない。sandbox 未接続のまま誤って本番送信しない。
 */
@Component
@Profile("prod")
@ConditionalOnMissingBean(DigitalInvoiceProvider.class)
public class FailClosedDigitalInvoiceProvider implements DigitalInvoiceProvider {

    @Override
    public String sendInvoice(String xml, String specificationVersion, String messageId) {
        throw new BusinessException(503,
                "デジタルインボイスプロバイダが未設定です。本番送信は拒否されます。");
    }

    @Override
    public boolean verifyWebhookSignature(String rawBody, String signatureHeader) {
        // 実プロバイダ未配線時は署名を一切通さない（fail-closed）
        return false;
    }
}
