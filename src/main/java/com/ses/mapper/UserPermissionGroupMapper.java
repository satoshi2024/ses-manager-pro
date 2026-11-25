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
     * 設定tenantの管理者roleで有効（{@code status=1}）・未削除、指定 provider（設定tenant・issuer の有効な OIDC provider）に
     * APPROVED の外部identity link を持ち OIDC でログインできる（{@code UserExternalIdentityMapper#selectApprovedActiveUsernamesByRole}
     * と同じ条件）、かつ {@code '*'}・{@code privacy.} 接頭辞の拒否行を持つ有効な group に所属していない（拒否が優先するため。
     * privacy-admin group 自体の検査 {@code PermissionGroupMapper#countPrivacyAdminGroupsGrantingPrivacy} と同じ条件）。
     *
     * <p>アカウントロック（{@code locked_until}）は条件にしない。ロックは未認証者がlogin失敗で起こせる一時的な状態で、
     * 起動時の人数に含めないと外部からアプリ全体の起動を止められるため（W3-14b review P2-1。既存の
     * {@code ProductionSecurityConfigurationValidator#validateOidcAdministrators} も見ない）。ロック中の利用者は
     * 実行時に {@code SecurityContextCallerContextProvider} が要求ごとに拒否する。
     *
     * <p>拒否行の扱いは実行時の判定（{@code AuthorizationServiceImpl}）と次のとおり揃える（A1H-1 delta review P3-N3・P3-N4）。
     * (1) 無効（{@code enabled = 0}）・論理削除の group、論理削除の所属行・拒否行は、実行時も許可・拒否のどちらにも使わないため、
     * ここでも除外の理由にしない（無効にした拒否 group では privacy から外せない）。(2) action key は両側とも同じ列の照合順序
     * （{@code utf8mb4_unicode_ci}）で比べるため、大文字小文字の違う拒否行（{@code PRIVACY.*}）は両側で同じく拒否として効く。
     * (3) 意図した差: {@code privacy.<x>} の一部だけの拒否行も、ここでは管理者として数えない（実行時はその action だけを拒否する）。
     * 少なく数える＝起動を止める側に倒す。固定: {@code PrivacyAdministratorDeterminationConsistencyTest}（H2）、
     * {@code PrivacyHoldServiceMySqlTest}（実 MySQL。大文字小文字）。
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
          AND EXISTS (SELECT 1
                      FROM t_user_external_identity x
                      WHERE x.tenant_id = #{identityTenantId}
                        AND x.provider_id = #{providerId}
                        AND x.user_id = u.id
                        AND x.review_status = 'APPROVED'
                        AND x.deleted_flag = 0)
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
                            AND (d.action_key = '*' OR d.action_key LIKE 'privacy.%')
                            AND d.deny_flag = 1
                            AND d.deleted_flag = 0)
        ORDER BY u.username
        """)
    List<String> selectEffectivePrivacyAdminUsernames(@Param("tenantId") String tenantId,
                                                      @Param("adminRole") String adminRole,
                                                      @Param("identityTenantId") String identityTenantId,
                                                      @Param("providerId") Long providerId);

    /**
     * 利用者が {@code '*'}・{@code privacy.} 接頭辞の拒否行を持つ有効な group に所属する数（0 でなければ privacy の管理者として扱わない）。
     * 条件は {@link #selectEffectivePrivacyAdminUsernames} の {@code NOT EXISTS} と同じ（機能の開放の行為者の判定が、開放の条件の
     * 数え方と揃える。review rb-fe-c P3-3）。
     */
    @Select("""
        SELECT COUNT(*)
        FROM t_user_permission_group o
        JOIN m_permission_group og ON og.id = o.group_id
        JOIN t_permission_group_action d ON d.group_id = og.id
        WHERE o.tenant_id = #{tenantId}
          AND o.user_id = #{userId}
          AND o.deleted_flag = 0
          AND og.tenant_id = #{tenantId}
          AND og.enabled = 1
          AND og.deleted_flag = 0
          AND d.tenant_id = #{tenantId}
          AND (d.action_key = '*' OR d.action_key LIKE 'privacy.%')
          AND d.deny_flag = 1
          AND d.deleted_flag = 0
        """)
    int countPrivacyDenyMemberships(@Param("tenantId") String tenantId, @Param("userId") Long userId);
}
