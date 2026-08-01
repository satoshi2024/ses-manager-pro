package com.ses.dto.crm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** リード転換結果。再実行時も同じ識別子を返す。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LeadConversionDto {
    private Long leadId;
    private Long customerId;
    private Long opportunityId;
}
