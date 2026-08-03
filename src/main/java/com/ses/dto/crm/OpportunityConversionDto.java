package com.ses.dto.crm;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 商機から作成された案件・見積の識別子。内部entityをそのまま変換APIへ公開しない。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OpportunityConversionDto {
    private Long opportunityId;
    private Long projectId;
    private Long quotationId;
}
