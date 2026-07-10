package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * メールテンプレートエンティティ
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_email_template")
public class EmailTemplate extends BaseEntity {

    /** テンプレート名 */
    private String templateName;

    /** 件名テンプレート */
    private String subjectTemplate;

    /** 本文テンプレート */
    private String bodyTemplate;

    /** テンプレート種別: '提案','面接依頼','お礼','フォローアップ','その他' */
    private String templateType;
}
