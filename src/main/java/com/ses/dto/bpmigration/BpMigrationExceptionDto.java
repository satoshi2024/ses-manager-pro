package com.ses.dto.bpmigration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BpMigrationExceptionDto {
    private String sourceType; // AVAILABILITY / PAYMENT
    private Long sourceId;
    private String rawCompanyName;
    private String normalizedCompanyName;
    private String reason; // EMPTY_NAME / MULTIPLE_CANDIDATES / CORPORATE_NUMBER_MISMATCH
    private Long suggestedBpCompanyId;
}
