package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.EngineerAccountLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EngineerAccountLinkMapper extends BaseMapper<EngineerAccountLink> {

    /**
     * 組織スコープに入る要員ID。
     *
     * <p>帰属は {@code t_engineer.organization_id} を正とし、未設定の要員だけ
     * アカウント連携ユーザーの主所属へフォールバックする。アカウント連携は要員セルフサービスを
     * 使う要員にしか存在しないため、連携を必須にすると大半の要員が誰からも見えなくなる。
     */
    @Select("""
        <script>
        SELECT DISTINCT e.id
        FROM t_engineer e
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = e.id
        LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
             AND uo.primary_flag = 1 AND uo.deleted_flag = 0
             AND uo.valid_from &lt;= #{asOf}
             AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
        WHERE e.deleted_flag = 0
          AND (
            <if test="organizationIds != null and organizationIds.size() > 0">
              COALESCE(e.organization_id, uo.organization_id) IN <foreach collection="organizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
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
            @Param("organizationIds") List<Long> organizationIds,
            @Param("directUserIds") List<Long> directUserIds,
            @Param("asOf") java.time.LocalDate asOf);

    @Select("SELECT * FROM t_engineer_account_link WHERE sys_user_id = #{sysUserId} LIMIT 1")
    EngineerAccountLink selectByUserId(@Param("sysUserId") Long sysUserId);

    @Select("SELECT * FROM t_engineer_account_link WHERE engineer_id = #{engineerId} LIMIT 1")
    EngineerAccountLink selectByEngineerId(@Param("engineerId") Long engineerId);

    @Select("<script>SELECT * FROM t_engineer_account_link WHERE engineer_id IN <foreach collection='engineerIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<EngineerAccountLink> selectByEngineerIds(@Param("engineerIds") List<Long> engineerIds);

    /**
     * 紐付け済みのログインユーザーID。ユーザー一覧で「要員ロールなのに紐付いていない」行を
     * 1クエリで判定するために使う（1件ずつ {@link #selectByUserId} を引くとN+1になる）。
     */
    @Select("<script>SELECT sys_user_id FROM t_engineer_account_link WHERE sys_user_id IN <foreach collection='sysUserIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<Long> selectLinkedUserIds(@Param("sysUserIds") List<Long> sysUserIds);
}
