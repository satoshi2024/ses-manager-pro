package com.ses.dto.certification;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/**
 * 資格取得 record 応答。PII 暗号化列は含めない。
 */
@Data
@Builder
public class EngineerCertificationViewDto {
    private Long id;
    private Long engineerId;
    private Long certificationId;
    private String certificationDisplayName;
    private LocalDate acquiredOn;
    private LocalDate expiresOn;
    private String recordState;
    private Integer currentFlag;
    private Integer version;
    private String certificateNumberMasked;
    private boolean canViewFullNumber;
}
