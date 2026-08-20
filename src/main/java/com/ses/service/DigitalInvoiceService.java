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
     */
    void processSendJob(Long jobId);

    /**
     * 受信したインボイスを処理する。重複検知、バリデーション、取引先特定を行う。
     */
    /**
     * デジタルインボイスをキャンセル・打消しする。
     */
    void cancelInvoice(Long digitalInvoiceId);

    void processInboundInvoice(String providerMessageId, String eventId, String xmlContent, String rawPayloadHash, java.time.LocalDateTime eventAt);
}

