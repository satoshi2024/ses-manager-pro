package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * サーベイ回答（t_survey_response。回答単位=質問単位）。
 * answer_value（1〜5、NULL=未回答）と任意commentを分離する（design §5）。
 * comment_visibility=CONFIDENTIAL はHR/管理者のみ可視（R4.3）。
 * UNIQUE(campaign_id, engineer_id, question_key)で再回答は上書き更新する。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_survey_response")
public class SurveyResponse extends BaseEntity {

    /** キャンペーンID */
    private Long campaignId;

    /** 回答要員ID */
    private Long engineerId;

    /** 質問キー */
    private String questionKey;

    /** scale回答（1〜5。NULL=未回答） */
    private Integer answerValue;

    /** 任意コメント */
    private String comment;

    /** PUBLIC/CONFIDENTIAL */
    private String commentVisibility;

    /** 回答同意フラグ */
    private Integer consentFlag;

    /** 回答時template version */
    private Integer templateVersion;
}
