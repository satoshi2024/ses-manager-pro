package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文書種別マスタ（m_document_type）。
 * 電帳法を意識した保存年数・起算日ルール・法的hold可否を管理する。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_document_type")
public class DocumentType extends BaseEntity {

    /** 種別コード (例: CONTRACT / INVOICE_OUT / SIGNED_PDF) */
    private String code;

    /** 種別名 */
    private String name;

    /** 方向: OUTGOING(発行) / INCOMING(受領) / INTERNAL(社内) */
    private String direction;

    /** 法定保存年数 */
    private Integer retentionYears;

    /**
     * 起算日ルール。
     * TRANSACTION_DATE / SIGNED_AT / CLOSED_AT / DISPATCH_END
     */
    private String retentionStartRule;

    /**
     * 法的holdの可否 (1=可, 0=不可)。
     * 個人情報のみを目的とした文書など、法的holdに馴染まないものは0とする。
     */
    @TableField("legal_hold_supported")
    private Integer legalHoldSupported;
}
