package com.ses.service.integrationhub;

import java.time.LocalDate;

/**
 * 公開API用ステータスマッパーの統合ファサード。
 * Project, Contract, Invoice, renewalStatus, settlementStatus の変換処理を一元提供する。
 */
public final class ExternalApiStatusMapper {

    private ExternalApiStatusMapper() {
    }

    public static String toProjectStatus(String internalStatus) {
        return ExternalApiProjectStatusMapper.toExternalStatus(internalStatus);
    }

    public static String toContractStatus(String internalStatus) {
        return ExternalApiContractStatusMapper.toExternalStatus(internalStatus);
    }

    public static String toInvoiceStatus(String internalStatus) {
        return ExternalApiInvoiceStatusMapper.toExternalStatus(internalStatus);
    }

    public static String toRenewalStatus(String internalStatus) {
        return ExternalApiRenewalStatusMapper.toExternalStatus(internalStatus);
    }

    public static String toSettlementStatus(String internalInvoiceStatus, LocalDate paidDate) {
        return ExternalApiSettlementStatusMapper.toExternalStatus(internalInvoiceStatus, paidDate);
    }

    public static String toSettlementStatus(String internalInvoiceStatus) {
        return ExternalApiSettlementStatusMapper.toExternalStatus(internalInvoiceStatus);
    }
}
