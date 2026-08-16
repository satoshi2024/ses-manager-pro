package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.ses.common.base.BaseEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * サーベイテンプレート（m_survey_template）。
 * questions_json に定型scale質問と任意comment質問を定義する（design §5）。
 * 回答時は template_version を t_survey_response へ固定する（design §6.1）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
@TableName("m_survey_template")
public class SurveyTemplate extends BaseEntity {

    /** テンプレートキー */
    private String templateKey;

    /** テンプレート名 */
    private String title;

    /** 説明 */
    private String description;

    /** 質問定義JSON（key/text/type/confidential_flag） */
    private String questionsJson;

    /** DRAFT/ACTIVE/ARCHIVED */
    private String status;

    @Version
    private Integer version;

    /** 作成ユーザー */
    private Long createdBy;
}
