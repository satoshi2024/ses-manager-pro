package com.ses.dto.compliance;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcurementComplianceFinding {
    private String code;
    private String severity; // ERROR / WARNING / INFO
    private String message;
    private String field;
    private String sourceUrl;
}
