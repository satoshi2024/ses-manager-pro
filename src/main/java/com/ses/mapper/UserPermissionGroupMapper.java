package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.UserPermissionGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface UserPermissionGroupMapper extends BaseMapper<UserPermissionGroup> {

    /** replace処理用。論理削除済み行も含めて一度物理削除し、一意制約による再割当失敗を防ぐ。 */
    @Delete("DELETE FROM t_user_permission_group WHERE tenant_id = #{tenantId} AND user_id = #{userId}")
    int deleteAllForUser(@Param("tenantId") String tenantId, @Param("userId") Long userId);

    /** 有効なpermission groupのメンバーを承認候補として取得する。 */
    @Select("""
        SELECT upg.user_id
        FROM t_user_permission_group upg
        JOIN m_permission_group pg ON pg.id = upg.group_id
        JOIN sys_user u ON u.id = upg.user_id
        WHERE upg.tenant_id = #{tenantId}
          AND pg.tenant_id = #{tenantId}
          AND pg.group_key = #{groupKey}
          AND pg.enabled = 1
          AND pg.deleted_flag = 0
          AND upg.deleted_flag = 0
          AND u.status = 1
          AND u.deleted_flag = 0
        ORDER BY upg.user_id
        """)
    List<Long> selectActiveUserIdsByGroupKey(@Param("tenantId") String tenantId,
                                             @Param("groupKey") String groupKey);
}
