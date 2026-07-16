package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * API操作監査ログエンティティ（t_audit_log）。
 * BaseEntityは継承しない（更新日時・論理削除の概念を持たない追記専用ログ）。
 */
@Data
@TableName("t_audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    private String method;

    private String uri;

    private Integer status;
    private String applicationCode;
    private Boolean successFlag;

    private LocalDateTime createdAt;
}
