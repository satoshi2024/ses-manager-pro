package com.ses.dto.cloudsign;

/**
 * POST /documents の form request（固定OpenAPI 0.36.0 の確認済みfieldのみ）。
 * note には非PIIのoperation markerを含める（受信者には表示されない）。titleは受信者に表示される。
 */
public record CreateDocumentRequest(
        String title,
        String note,
        String message,
        boolean canTransfer,
        boolean isPrivate) {

    public CreateDocumentRequest(String title, String note, String message) {
        this(title, note, message, false, false);
    }
}
