package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.EngineerAccountLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface EngineerAccountLinkMapper extends BaseMapper<EngineerAccountLink> {

    /**
     * 組織スコープに入る要員ID。
     *
     * <p>帰属は対象日の {@code t_engineer_accounting_history} を正とし、履歴行が無い場合だけ
     * 現在の {@code t_engineer.organization_id}、さらに未設定時だけアカウント連携ユーザーの
     * 対象日主所属へフォールバックする。履歴が {@code UNKNOWN} の場合は明示的に除外する。
     * アカウント連携は要員セルフサービスを使う要員にしか存在しないため、連携を必須にすると
     * 大半の要員が誰からも見えなくなる。
     */
    @Select("""
        <script>
        SELECT DISTINCT e.id
        FROM t_engineer e
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = e.id
        LEFT JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0
        LEFT JOIN t_engineer_accounting_history eh ON eh.engineer_id = e.id
             AND eh.deleted_flag = 0
             AND eh.valid_from &lt;= #{asOf}
             AND (eh.valid_to IS NULL OR eh.valid_to &gt;= #{asOf})
        LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
             AND uo.primary_flag = 1 AND uo.deleted_flag = 0
             AND uo.valid_from &lt;= #{asOf}
             AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
        WHERE e.deleted_flag = 0
          AND e.tenant_id = #{tenantId}
          AND (l.id IS NULL OR (l.tenant_id = #{tenantId} AND u.tenant_id = #{tenantId}))
          AND (
            <if test="organizationIds != null and organizationIds.size() > 0">
              CASE
                WHEN eh.id IS NULL THEN COALESCE(e.organization_id, uo.organization_id)
                WHEN eh.organization_history_status = 'UNKNOWN' THEN NULL
                ELSE eh.organization_id
              END IN <foreach collection="organizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            <if test="directUserIds != null and directUserIds.size() > 0">
              <if test="organizationIds != null and organizationIds.size() > 0">OR</if>
              l.sys_user_id IN <foreach collection="directUserIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            <if test="(organizationIds == null or organizationIds.size() == 0) and (directUserIds == null or directUserIds.size() == 0)">1 = 0</if>
          )
        </script>
        """)
    List<Long> selectEngineerIdsByOrganizationScope(
            @Param("tenantId") String tenantId,
            @Param("organizationIds") List<Long> organizationIds,
            @Param("directUserIds") List<Long> directUserIds,
            @Param("asOf") java.time.LocalDate asOf);

    @Select("SELECT * FROM t_engineer_account_link WHERE sys_user_id = #{sysUserId} LIMIT 1")
    EngineerAccountLink selectByUserId(@Param("sysUserId") Long sysUserId);

    @Select("SELECT l.* FROM t_engineer_account_link l "
            + "JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0 "
            + "JOIN t_engineer e ON e.id = l.engineer_id AND e.deleted_flag = 0 "
            + "WHERE l.sys_user_id = #{sysUserId} AND l.tenant_id = #{tenantId} "
            + "AND u.tenant_id = #{tenantId} AND e.tenant_id = #{tenantId} LIMIT 1")
    EngineerAccountLink selectByUserIdAndTenant(@Param("sysUserId") Long sysUserId,
                                                @Param("tenantId") String tenantId);

    @Select("SELECT * FROM t_engineer_account_link WHERE engineer_id = #{engineerId} LIMIT 1")
    EngineerAccountLink selectByEngineerId(@Param("engineerId") Long engineerId);

    @Select("SELECT l.* FROM t_engineer_account_link l "
            + "JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0 "
            + "JOIN t_engineer e ON e.id = l.engineer_id AND e.deleted_flag = 0 "
            + "WHERE l.engineer_id = #{engineerId} AND l.tenant_id = #{tenantId} "
            + "AND u.tenant_id = #{tenantId} AND e.tenant_id = #{tenantId} LIMIT 1")
    EngineerAccountLink selectByEngineerIdAndTenant(@Param("engineerId") Long engineerId,
                                                    @Param("tenantId") String tenantId);

    @Select("<script>SELECT * FROM t_engineer_account_link WHERE engineer_id IN <foreach collection='engineerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<EngineerAccountLink> selectByEngineerIds(@Param("engineerIds") List<Long> engineerIds);

    @Select("<script>SELECT l.* FROM t_engineer_account_link l "
            + "JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0 "
            + "JOIN t_engineer e ON e.id = l.engineer_id AND e.deleted_flag = 0 "
            + "WHERE l.tenant_id = #{tenantId} AND u.tenant_id = #{tenantId} "
            + "AND e.tenant_id = #{tenantId} AND l.engineer_id IN "
            + "<foreach collection='engineerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<EngineerAccountLink> selectByEngineerIdsAndTenant(@Param("engineerIds") List<Long> engineerIds,
                                                           @Param("tenantId") String tenantId);

    /** サーベイ・通知等の受信者母集団を、両端のtenant ownership付きで取得する。 */
    @Select("SELECT l.* FROM t_engineer_account_link l "
            + "JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0 "
            + "JOIN t_engineer e ON e.id = l.engineer_id AND e.deleted_flag = 0 "
            + "WHERE l.tenant_id = #{tenantId} AND u.tenant_id = #{tenantId} "
            + "AND e.tenant_id = #{tenantId} ORDER BY l.id")
    List<EngineerAccountLink> selectAllForTenant(@Param("tenantId") String tenantId);

    /**
     * 紐付け済みのログインユーザーID。ユーザー一覧で「要員ロールなのに紐付いていない」行を
     * 1クエリで判定するために使う（1件ずつ {@link #selectByUserId} を引くとN+1になる）。
     */
    @Select("<script>SELECT sys_user_id FROM t_engineer_account_link WHERE sys_user_id IN <foreach collection='sysUserIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Long> selectLinkedUserIds(@Param("sysUserIds") List<Long> sysUserIds);

    @Select("<script>SELECT l.sys_user_id FROM t_engineer_account_link l "
            + "JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0 "
            + "JOIN t_engineer e ON e.id = l.engineer_id AND e.deleted_flag = 0 "
            + "WHERE l.tenant_id = #{tenantId} AND u.tenant_id = #{tenantId} "
            + "AND e.tenant_id = #{tenantId} AND l.sys_user_id IN "
            + "<foreach collection='sysUserIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Long> selectLinkedUserIdsAndTenant(@Param("sysUserIds") List<Long> sysUserIds,
                                            @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM t_engineer_account_link "
            + "WHERE id = #{id} AND tenant_id = #{tenantId}")
    int deleteByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    /** 手動修復用。両端の明示ownershipが同一tenantの場合だけlinkのtenantを補正する。 */
    @Update("UPDATE t_engineer_account_link SET tenant_id = #{tenantId} "
            + "WHERE id = #{id} "
            + "AND (tenant_id IS NULL OR "
            + "(CONVERT(tenant_id USING utf8mb4) COLLATE utf8mb4_unicode_ci "
            + "<> CONVERT(#{tenantId} USING utf8mb4) COLLATE utf8mb4_unicode_ci)) "
            + "AND EXISTS (SELECT 1 FROM sys_user u WHERE u.id = t_engineer_account_link.sys_user_id "
            + "AND u.tenant_id = #{tenantId} AND u.deleted_flag = 0) "
            + "AND EXISTS (SELECT 1 FROM t_engineer e WHERE e.id = t_engineer_account_link.engineer_id "
            + "AND e.tenant_id = #{tenantId} AND e.deleted_flag = 0)")
    int assignTenantForRepair(@Param("id") Long id, @Param("tenantId") String tenantId);
}
