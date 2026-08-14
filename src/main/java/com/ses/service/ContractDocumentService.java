package com.ses.service;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.cloudsign.ConfirmedSendRequest;
import com.ses.entity.ContractDocument;
public interface ContractDocumentService extends IService<ContractDocument> {
    ContractDocument create(Long contractId, Long templateId, String recipientName, String recipientEmail);
    /**
     * 送信queue受付（HFP-02-AC-04-01）。外部APIを呼ばず、確認済みpayloadの検証と状態CASだけを行う。
     * 二重クリック・並列request・worker再実行は同じoperationとして扱い、同一payloadの再queueは既存operationを返す。
     */
    ContractDocument queueSend(Long id, ConfirmedSendRequest request);
    byte[] download(Long id);
}
