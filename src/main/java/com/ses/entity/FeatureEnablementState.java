package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 開放単位の開放状態（{@code t_feature_enablement_state}、V203）。行が無い開放単位は CLOSED として扱う。開放の申請者・承認者は
 * 承認した申請の行と複合 FK で一致する。閉鎖・自動閉鎖・再開で NULL に戻す列は {@code UpdateWrapper.set(col, null)} で名指しする。
 */
@Data
@TableName("t_feature_enablement_state")
public class FeatureEnablementState {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** 開放単位の key（{@code Feature#name()}）。 */
    private String featureKey;

    /** CLOSED / OPEN / OPEN_PENDING_RESTART / AUTO_CLOSED / MANUALLY_CLOSED。 */
    private String state;

    private Integer version;

    private Long openRequestId;

    private Long openedBy;

    private Long approvedBy;

    private LocalDateTime openedAt;

    private LocalDateTime restartAppliedAt;

    private String closedReason;

    private Long closedBy;

    private LocalDateTime closedAt;

    /** 評価できない状態の始まり（全 instance が snapshot で共有する一時の閉鎖）。 */
    private LocalDateTime unverifiableSince;

    private String unverifiableReason;

    private LocalDateTime updatedAt;
}
