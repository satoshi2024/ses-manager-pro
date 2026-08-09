package com.ses.dto.attendance;

import lombok.Data;

/** 締め済み勤怠の再open申請command。理由は承認申請payloadへ不変保存する。 */
@Data
public class AttendanceReopenRequest {
    private String month;
    private String reason;
}
