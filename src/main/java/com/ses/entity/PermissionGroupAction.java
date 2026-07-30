package com.ses.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.ses.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * permission groupのaction key。
 *
 * <p>{@code denyFlag=1}は明示拒否であり、同一groupのbaseline許可（{@code action_key='*'}）より
 * 優先される。baselineだけでは「旧menu相当の範囲から機密actionを外す」を表現できず、
 * 許可listの列挙に頼るとURI rootから機械生成されるaction keyを取りこぼすため
 * （V64で発生した回帰）、拒否指定を持つ。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_permission_group_action")
public class PermissionGroupAction extends BaseEntity {
    private String tenantId;
    private Long groupId;
    private String actionKey;
    /** 1:明示拒否（baseline許可より優先）。0:許可。 */
    private Integer denyFlag;
}
