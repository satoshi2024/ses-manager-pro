package com.ses.dto.contractdocument;

/**
 * 契約書作成リクエスト（JSON body）。
 * 宛先氏名・メールをquery stringに載せない（HFP-02-BUG-06）。
 */
public record CreateContractDocumentRequest(
        Long contractId,
        Long templateId,
        String recipientName,
        String recipientEmail) {
}
