package com.ses.dto.leave;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** 休暇申請のread DTO。内部entityを直接公開しない。 */
@Data
public class LeaveDto {
    private Long id;
    private Long engineerId;
    private String engineerName;
    private String leaveType;
    private LocalDate startDate;
    private LocalDate endDate;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;
    private Integer requestedMinutes;
    private String reason;
    private String status;
    private String approvalStatus;
    private Long approvalRequestId;
    private Integer version;
}
