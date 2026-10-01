package com.ses.dto.closing;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 月次締め画面へ返す未確定勤怠の公開項目。
 * tenant・監査・内部帰属情報をWorkRecord entityからそのまま公開しない。
 */
@Data
public class MonthlyClosingWorkRecordDto {

    private Long workRecordId;
    private Long contractId;
    private String workMonth;
    private BigDecimal actualHours;
    private String status;
    private String remarks;
    private String rejectComment;
    private Integer version;
}
