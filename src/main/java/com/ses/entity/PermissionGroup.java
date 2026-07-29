package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** roleから段階移行するpermission group。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("m_permission_group")
public class PermissionGroup extends BaseEntity {
    private String tenantId;
    private String groupKey;
    private String groupName;
    private String description;
    private Integer enabled;
}
