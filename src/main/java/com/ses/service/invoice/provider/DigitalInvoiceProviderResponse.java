package com.ses.service.invoice.provider;

/**
 * プロバイダ応答の安全なメタデータ。レスポンス本文・error_descriptionは保持しない。
 */
public record DigitalInvoiceProviderResponse(
        String providerMessageId,
        String providerOperationId,
        String providerRequestId,
        Integer httpStatus,
        String providerCode) {

    public static DigitalInvoiceProviderResponse success(String providerMessageId) {
        return new DigitalInvoiceProviderResponse(providerMessageId, null, null, 200, null);
    }
}
