package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * サーベイキャンペーン（t_survey_campaign）。
 * 定期回答の配布単位。DRAFT→ACTIVE（配信）→CLOSED。
 * 未回答は平均値の母数へ含めない（design §6.1）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("t_survey_campaign")
public class SurveyCampaign extends BaseEntity {

    /** テンプレートID */
    private Long templateId;

    /** キャンペーン名 */
    private String title;

    /** 回答期間開始 */
    private LocalDate periodFrom;

    /** 回答期間終了 */
    private LocalDate periodTo;

    /** DRAFT/ACTIVE/CLOSED */
    private String status;

    /** 作成ユーザー */
    private Long createdBy;
}
