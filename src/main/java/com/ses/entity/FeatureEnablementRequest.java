package com.ses.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 機能の開放の申請（{@code t_feature_enablement_request}、V203）。開放（OPEN）・設定の変更（SETTING_CHANGE）・署名の延長
 * （SIGN_OFF_EXTENSION）の未決から終了へ1回だけ遷移する。状態の更新は version の CAS で、列を名指しした {@code UpdateWrapper} で行う
 * （{@code FieldStrategy.ALWAYS} を使わない。CLAUDE.md）。{@code deletedFlag} を持たない（論理削除しない）。
 */
@Data
@TableName("t_feature_enablement_request")
public class FeatureEnablementRequest {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** OPEN / SETTING_CHANGE / SIGN_OFF_EXTENSION。 */
    private String requestType;

    /** 開放単位の key・設定の key・{@code signoff:<id>}。 */
    private String targetKey;

    /** SETTING_CHANGE の値（NULL は未設定へ戻す）・延長の新しい失効日。 */
    private String proposedValue;

    /** PENDING / APPROVED / REJECTED / WITHDRAWN / EXPIRED / STALE / CANCELLED。 */
    private String status;

    /** 未決の申請だけ1（STORED の生成列。書かない）。 */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private Integer pendingSlot;

    /** 条件の指紋（design §4.5）。 */
    private String conditionFingerprint;

    /** 条件の code と成否だけ（値・氏名を含めない）。 */
    private String conditionSummary;

    private String reason;

    private Long requestedBy;

    private LocalDateTime requestedAt;

    private LocalDateTime expiresAt;

    private Long decidedBy;

    private LocalDateTime decidedAt;

    private String decisionReason;

    /** 楽観 lock の版（CAS の条件に使う。MyBatis-Plus の {@code @Version} は使わない）。 */
    private Integer version;
}
