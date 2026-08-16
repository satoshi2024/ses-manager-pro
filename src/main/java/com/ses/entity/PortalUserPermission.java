package com.ses.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ポータルユーザー権限エンティティ（t_portal_user_permission）。
 * 組織種別の既定に加えて管理者が個別付与する。append-onlyでなく、解除は行削除。
 */
@Data
@TableName("t_portal_user_permission")
public class PortalUserPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** portal user ID */
    private Long userId;

    /** 権限キー（例: document.view / acceptance.operate / availability.manage / bank-account.request） */
    private String permissionKey;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createdAt;
}
