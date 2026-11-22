package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 機能の開放の遷移の履歴（{@code t_feature_enablement_event}、V203。追記専用）。氏名と自由記述を含めない（design §2.7）。
 * 更新・削除は {@link com.ses.mapper.FeatureEnablementEventMapper} と DB の trigger が拒否する。
 */
@Data
@TableName("t_feature_enablement_event")
public class FeatureEnablementEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tenantId;

    /** FEATURE / REQUEST / SIGN_OFF / BUNDLE / SETTING / SUBGATE / MASTER。 */
    private String subjectType;

    /** 開放単位・種別・設定の key。 */
    private String subjectKey;

    private Long subjectId;

    private String eventType;

    private String fromState;

    private String toState;

    private String reasonCode;

    /** 条件の code と成否だけ。 */
    private String conditionSummary;

    /** USER / SYSTEM。 */
    private String actorType;

    private Long actorUserId;

    private String correlationId;

    /** 遷移の後の世代。 */
    private Long generation;

    private LocalDateTime occurredAt;
}
