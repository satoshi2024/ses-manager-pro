package com.ses.dto.leave;

import lombok.Data;

import java.time.LocalDate;

/** HR/管理者が行う休暇付与command（T071、本システム正モードの台帳へのGRANT）。 */
@Data
public class LeaveGrantRequest {
    private Long engineerId;
    private String leaveType;
    private Integer amountMinutes;
    private LocalDate entryDate;
    private String remarks;
}
