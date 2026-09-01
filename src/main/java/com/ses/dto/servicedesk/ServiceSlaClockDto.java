package com.ses.dto.servicedesk;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * SLA計時状況DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceSlaClockDto {

    private Long id;

    private Long serviceRequestId;

    private Integer roundNo;

    private Long policyId;

    private String policyName;

    private LocalDateTime responseDeadline;

    private LocalDateTime resolveDeadline;

    private LocalDateTime firstRespondedAt;

    private Boolean responseBreached;

    private LocalDateTime resolvedAt;

    private Boolean resolveBreached;

    private Integer totalPauseMinutes;

    private LocalDateTime lastPausedAt;

    private String status;
}
