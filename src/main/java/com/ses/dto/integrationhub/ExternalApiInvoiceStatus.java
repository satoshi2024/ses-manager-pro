package com.ses.dto.integrationhub;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.LocalDate;

/** 公開APIの請求状態allow-list。金額・口座・顧客情報を含めない。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalApiInvoiceStatus(
        String publicInvoiceId,
        String publicContractId,
        String status,
        LocalDate issueDate,
        LocalDate dueDate,
        Instant paidAt,
        String settlementStatus) {
}
