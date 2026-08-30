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
     * / ASSET_ASSIGNMENT / ASSET_LOST_INCIDENT
     */
    private String targetType;

    /** リンク先エンティティID */
    private Long targetId;

    /**
     * スキルシート確認日時（NULL=未確認。S14 engineer-self-service-portal-v2）。
     * 客先提出前チェックの対象（design §6.1）。確認ごとに更新する。
     */
    private java.time.LocalDateTime skillSheetConfirmedAt;

    /** 確認時のdocument version（t_document_version.version_no相当） */
    private String skillSheetConfirmedVersion;
}
