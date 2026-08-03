package com.ses.dto.crm;

import lombok.Data;

/** リード転換時の楽観ロック指定。 */
@Data
public class LeadConversionRequest {
    private Integer version;
}
