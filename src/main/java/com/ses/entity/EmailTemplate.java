package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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

    /**
     * m_email_template には deleted_flag 列が存在しない（論理削除の対象外）ため、
     * BaseEntity 由来の論理削除カラムをマッピング対象外にする。
     * これを無効化しないと一覧取得・保存・削除の全てが SQL エラーになる。
     */
    @TableField(exist = false)
    private Integer deletedFlag;

    /** テンプレート名 */
    private String templateName;

    /** 件名テンプレート */
    private String subjectTemplate;

    /** 本文テンプレート */
    private String bodyTemplate;

    /** テンプレート種別: '提案','面接依頼','お礼','フォローアップ','その他' */
    private String templateType;
}
