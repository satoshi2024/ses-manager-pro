package com.ses.dto.leave;

import lombok.Data;

/** 休暇残数照会のread DTO。external正の場合はmode=externalで残数は参照しない。 */
@Data
public class LeaveBalanceDto {
    private String leaveType;
    private Integer balanceMinutes;
    private String mode;
}
