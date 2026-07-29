package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** ユーザーとpermission groupの割当。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_user_permission_group")
public class UserPermissionGroup extends BaseEntity {
    private String tenantId;
    private Long userId;
    private Long groupId;
    private Long assignedBy;
    private LocalDateTime assignedAt;
}
