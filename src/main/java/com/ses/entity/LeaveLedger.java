package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 休暇残数の付与/消化台帳（G6: 本システムが正 / V98）。
 * 残数 = ΣGRANT − ΣCONSUME（要員×休暇種別、申請日時点）。
 * 消化行は休暇承認時にINSERTし、却下・取下・取消で戻す。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_leave_ledger")
public class LeaveLedger extends BaseEntity {
    private Long engineerId;
    private Long legalEntityId;
    private String leaveType;
    private String ledgerType;
    private Integer amountMinutes;
    private LocalDate entryDate;
    private Long leaveRequestId;
    private String source;
    private String sourceExternalId;
    private String remarks;

    @Version
    private Integer version;
}
