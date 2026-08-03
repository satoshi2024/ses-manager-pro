package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 文書と業務エンティティの関連（t_document_link）。
 * 文書の認可母集団はこのリンク先の業務entityのscopeから導出する（design §6.2）。
 * link先が複数ある文書は和集合で可視とする。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_document_link")
public class DocumentLink extends BaseEntity {

    /** 文書ID */
    private Long documentId;

    /**
     * リンク先種別。
     * CUSTOMER / BP_COMPANY / ENGINEER / PROJECT / PROPOSAL / QUOTATION
     * / CONTRACT / WORK_RECORD / INVOICE / BANK_DEPOSIT / BP_PAYMENT
     */
    private String targetType;

    /** リンク先エンティティID */
    private Long targetId;
}
