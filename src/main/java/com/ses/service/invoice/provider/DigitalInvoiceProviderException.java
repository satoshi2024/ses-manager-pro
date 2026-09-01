package com.ses.service.invoice.provider;

import com.ses.common.util.CorrelationContext;
import lombok.Getter;

/** 本文を持たないプロバイダ障害。照合用メタデータだけを保持する。 */
@Getter
public class DigitalInvoiceProviderException extends RuntimeException {

    private final Integer httpStatus;
    private final String providerCode;
    private final String providerRequestId;
    private final String providerOperationId;

    public DigitalInvoiceProviderException(Integer httpStatus, String providerCode,
                                           String providerRequestId, String providerOperationId) {
        super("外部プロバイダとの連携に失敗しました。");
        this.httpStatus = httpStatus != null && httpStatus >= 100 && httpStatus <= 599 ? httpStatus : null;
        this.providerCode = providerCode != null && providerCode.length() <= 64
                && providerCode.matches("[A-Za-z0-9._:-]{1,64}") ? providerCode : null;
        this.providerRequestId = CorrelationContext.safeIdentifier(providerRequestId);
        this.providerOperationId = CorrelationContext.safeIdentifier(providerOperationId);
    }
}
