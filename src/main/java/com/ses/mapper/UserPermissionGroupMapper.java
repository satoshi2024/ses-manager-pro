package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.UserPermissionGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserPermissionGroupMapper extends BaseMapper<UserPermissionGroup> {

    /** replace処理用。論理削除済み行も含めて一度物理削除し、一意制約による再割当失敗を防ぐ。 */
    @Delete("DELETE FROM t_user_permission_group WHERE tenant_id = #{tenantId} AND user_id = #{userId}")
    int deleteAllForUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);
}
