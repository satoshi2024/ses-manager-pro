package com.ses.dto.compliance;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * R23-P1-01 §5: typed request DTO for mapping version作成。
 * MapをAPI契約にしない（既存Base APIのMap/entity契約の置換）。
 */
@Data
public class ComplianceMappingCreateRequest {
    private String mappingCode;
    private String mappingVersion;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private List<ComplianceMappingSourceInput> sources;
}
