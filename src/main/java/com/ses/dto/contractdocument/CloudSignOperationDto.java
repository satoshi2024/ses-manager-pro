package com.ses.dto.contractdocument;

/**
 * 送信queue受付のoperation DTO（HFP-02-AC-10-02）。
 * 「queue受付」でありprovider送信完了ではないことをUIへ伝える。
 */
public record CloudSignOperationDto(
        Long documentId,
        String operationId,
        String dispatchState) {
}
