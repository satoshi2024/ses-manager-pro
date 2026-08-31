package com.ses.dto.integrationhub;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;

/** 公開APIの案件allow-list。案件名・顧客名・単価等を含めない。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalApiProject(
        String publicProjectId,
        String status,
        LocalDate startDate,
        LocalDate endDate,
        String publicCustomerId) {
}
