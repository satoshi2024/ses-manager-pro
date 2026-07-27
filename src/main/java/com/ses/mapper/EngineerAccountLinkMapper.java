package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.EngineerAccountLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EngineerAccountLinkMapper extends BaseMapper<EngineerAccountLink> {

    @Select("""
        <script>
        SELECT DISTINCT l.engineer_id
        FROM t_engineer_account_link l
        JOIN t_user_organization uo ON uo.user_id = l.sys_user_id
        WHERE uo.deleted_flag = 0
          AND uo.valid_from &lt;= #{asOf}
          AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{asOf})
          AND (
            <if test="organizationIds != null and organizationIds.size() > 0">
              uo.organization_id IN <foreach collection="organizationIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </if>
            <if test="directUserIds != null and directUserIds.size() > 0">
              <if test="organizationIds != null and organizationIds.size() > 0">OR</if>
              uo.user_id IN <foreach collection="directUserIds" item="id" open="(" separator="," close=")">#{id}</foreach>
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
}
