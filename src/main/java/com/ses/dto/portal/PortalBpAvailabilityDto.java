package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * BP portal向け空き要員DTO（自社提出分のみ。review前は内部候補に出ない: R3.2）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalBpAvailabilityDto {
    private Long id;
    private String initialName;
    private String skillsJson;
    private BigDecimal unitPrice;
    private LocalDate availableFrom;
    private Integer experienceYears;
    private String status;
    private String remarks;
    private LocalDateTime createdAt;
    /** 却下コメント等のreview結果（内部側で設定） */
    private String reviewComment;
}
