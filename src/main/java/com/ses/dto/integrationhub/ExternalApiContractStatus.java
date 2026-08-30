package com.ses.dto.integrationhub;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/** 公開APIの契約状態allow-list。金額・要員名・顧客情報を含めない。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalApiContractStatus(
        String publicContractId,
        String publicProjectId,
        String status,
        LocalDate startDate,
        LocalDate endDate,
        String renewalStatus) {
}
