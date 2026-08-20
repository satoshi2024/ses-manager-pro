package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.DigitalInvoice;
import com.ses.entity.DigitalInvoiceEvent;

public interface DigitalInvoiceService extends IService<DigitalInvoice> {

    /**
     * Webhook等からのイベントを受け取り、状態を更新する。
     * @param event 受信したイベント
     */
    void processProviderEvent(DigitalInvoiceEvent event);

    /**
     * 既存のInvoiceをJP PINT送信キューに登録する。
     */
    DigitalInvoice enqueueInvoiceForSend(Long invoiceId, String specVersion, Long customerId);

    /**
     * ジョブ基盤を通じてキューに入っているDigitalInvoiceを実際にProviderへ送信する。
     * Standard profile 専用。CreditNote は {@link #processCreditNoteJob(Long)} を使う。
     */
    void processSendJob(Long jobId);

    /**
     * Credit Note（打消し電文）専用ジョブ。請求 Invoice XML を再送しない。
     */
    void processCreditNoteJob(Long jobId);

    /**
     * デジタルインボイスをキャンセル・打消しする。
     */
    void cancelInvoice(Long digitalInvoiceId);

    /**
     * 受信インボイスを処理する。重複検知、バリデーション、取引先特定を行う。
     */
    void processInboundInvoice(String providerMessageId, String eventId, String xmlContent, String rawPayloadHash, java.time.LocalDateTime eventAt);

    /**
     * 受信 review ACCEPT。照合済み列から InboundPurchaseRequest を組み立て accounting へ渡す（自動支払なし）。
     */
    com.ses.dto.invoice.InboundPurchaseRequest acceptInboundReview(Long digitalInvoiceId);
}

