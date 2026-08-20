package com.ses.service.invoice.provider;

import com.ses.dto.invoice.CanonicalInvoice;

public interface DigitalInvoiceProvider {
    
    /**
     * デジタルインボイスを送信する。
     * @param xml 送信するXMLコンテンツ
     * @param specificationVersion JP PINTバージョン
     * @return provider_message_id (プロバイダ側で採番された送信メッセージID)
     */
    String sendInvoice(String xml, String specificationVersion);

    /**
     * Webhookの署名が正しいか検証する。(raw bodyを用いる)
     * @param rawBody Webhookの生リクエストボディ
     * @param signatureHeader Webhookの署名ヘッダ
     * @return 署名が正しければtrue
     */
    boolean verifyWebhookSignature(String rawBody, String signatureHeader);
}
