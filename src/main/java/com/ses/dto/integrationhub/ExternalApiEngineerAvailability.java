package com.ses.dto.integrationhub;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.util.List;

/** 公開APIの要員稼働allow-list。内部Engineerを継承・serializeしない。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalApiEngineerAvailability(
        String publicEngineerId,
        String availabilityStatus,
        LocalDate availableFrom,
        LocalDate availableTo,
        List<String> skillTagCode) {
}
