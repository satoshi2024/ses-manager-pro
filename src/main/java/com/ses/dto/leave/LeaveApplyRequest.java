package com.ses.dto.leave;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalTime;

/** 本人が申請する休暇command（T071）。sourceはmanual固定、分計算はサーバー側のcalendarを正とする。 */
@Data
public class LeaveApplyRequest {
    private String leaveType;
    private LocalDate startDate;
    private LocalDate endDate;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime startTime;
    @JsonFormat(pattern = "HH:mm")
    private LocalTime endTime;
    private String reason;
}
