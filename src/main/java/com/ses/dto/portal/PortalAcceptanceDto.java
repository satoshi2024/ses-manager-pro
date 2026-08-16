package com.ses.dto.portal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 顧客portal向け検収DTO（field-inventory §3.1。内部ID・version・createdByを含まない）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PortalAcceptanceDto {
    private Long id;
    private String workMonth;
    private String status;
    private LocalDateTime submittedAt;
    private LocalDateTime acceptedAt;
    private String rejectComment;
    private BigDecimal hoursSnapshot;
    private BigDecimal amountSnapshot;
    private String customerContactNameSnapshot;
    private String contractNo;
    private String engineerName;
    /** 検収書原本（archive CLEAN後）がdownload可能か */
    private boolean documentAvailable;
}
