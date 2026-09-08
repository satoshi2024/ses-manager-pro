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

    private LocalDateTime responseBreachedAt;

    /** 旧履歴でresponse breach検知時刻を復元できない場合。 */
    private Boolean responseBreachTimeUnknown;

    private LocalDateTime resolvedAt;

    private Boolean resolveBreached;

    private LocalDateTime resolveBreachedAt;

    /** 旧履歴でresolve breach検知時刻を復元できない場合。 */
    private Boolean resolveBreachTimeUnknown;

    private Integer totalPauseMinutes;

    private LocalDateTime lastPausedAt;

    private String status;
}
