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

    /**
     * NF07 の flag ON の前提（W3-14b、production-bridge-phased-plan.md §5.1）: {@code privacy-admin} の有効な所属者の username。
     *
     * <p>「有効」は privacy を実際に使える状態を指す: 所属行が未削除、group が設定tenantで有効・未削除、ユーザーが
     * 設定tenantの管理者roleで有効（{@code status=1}）・未削除・未ロック（{@code locked_until} が無いか {@code now} 以前）、
     * かつ {@code privacy.*}・{@code '*'} の拒否行を持つ有効な他の group に所属していない（拒否が優先するため）。
     * 条件は {@code AuthorizationServiceImpl}・{@code SecurityContextCallerContextProvider} の判定と揃える。
     */
    @Select("""
        SELECT DISTINCT u.username
        FROM t_user_permission_group upg
        JOIN m_permission_group pg ON pg.id = upg.group_id
        JOIN sys_user u ON u.id = upg.user_id
        WHERE upg.tenant_id = #{tenantId}
          AND upg.deleted_flag = 0
          AND pg.tenant_id = #{tenantId}
          AND pg.group_key = 'privacy-admin'
          AND pg.enabled = 1
          AND pg.deleted_flag = 0
          AND u.tenant_id = #{tenantId}
          AND u.role = #{adminRole}
          AND u.status = 1
          AND u.deleted_flag = 0
          AND (u.locked_until IS NULL OR u.locked_until <= #{now})
          AND NOT EXISTS (SELECT 1
                          FROM t_user_permission_group o
                          JOIN m_permission_group og ON og.id = o.group_id
                          JOIN t_permission_group_action d ON d.group_id = og.id
                          WHERE o.tenant_id = #{tenantId}
                            AND o.user_id = u.id
                            AND o.deleted_flag = 0
                            AND og.tenant_id = #{tenantId}
                            AND og.enabled = 1
                            AND og.deleted_flag = 0
                            AND d.tenant_id = #{tenantId}
                            AND d.action_key IN ('privacy.*', '*')
                            AND d.deny_flag = 1
                            AND d.deleted_flag = 0)
        ORDER BY u.username
        """)
    List<String> selectEffectivePrivacyAdminUsernames(@Param("tenantId") String tenantId,
                                                      @Param("adminRole") String adminRole,
                                                      @Param("now") java.time.LocalDateTime now);
}
