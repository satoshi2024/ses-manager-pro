package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** permission groupが許可するaction key。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_permission_group_action")
public class PermissionGroupAction extends BaseEntity {
    private String tenantId;
    private Long groupId;
    private String actionKey;
}
