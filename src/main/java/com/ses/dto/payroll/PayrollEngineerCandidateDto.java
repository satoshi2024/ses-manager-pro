package com.ses.dto.payroll;
import lombok.Data;
/** 給与対応付けの内部要員候補（deleted_flag=0 かつ employment_type != 'BP'）。 */
@Data public class PayrollEngineerCandidateDto {
    private Long id;
    private String fullName;
    private String employmentType;
}
