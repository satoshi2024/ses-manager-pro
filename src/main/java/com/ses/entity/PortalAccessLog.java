package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ポータル操作監査ログ（t_portal_access_log。append-only）。
 * R4.2: portalのdownload/検収/提出/口座変更を外部user/組織/IP/時刻で監査する。
 * 論理削除後の監査参照に備えFKは持たない。
 */
@Data
@TableName("t_portal_access_log")
public class PortalAccessLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** portal user ID */
    private Long portalUserId;

    /** portal組織ID */
    private Long portalOrgId;

    /** portal user email */
    private String email;

    /** 組織種別: CUSTOMER / BP */
    private String orgType;

    /** 操作（DOWNLOAD_QUOTATION / ACCEPT / REJECT / SUBMIT / CONFIRM_RECEIPT / BANK_REQUEST 等） */
    private String action;

    /** 対象種別 */
    private String targetType;

    /** 対象ID */
    private Long targetId;

    /** 接続元IPのSHA-256 hash */
    private String ipHash;

    /** User-Agent */
    private String userAgent;

    /** 記録日時 */
    private LocalDateTime createdAt;
}
