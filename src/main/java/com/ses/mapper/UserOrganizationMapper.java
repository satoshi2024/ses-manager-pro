package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.UserOrganization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** ユーザー所属履歴Mapper。 */
@Mapper
public interface UserOrganizationMapper extends BaseMapper<UserOrganization> {

    @Select("SELECT * FROM t_user_organization WHERE user_id = #{userId} AND deleted_flag = 0 ORDER BY id FOR UPDATE")
    List<UserOrganization> selectByUserForUpdate(@Param("userId") Long userId);

    @Select("SELECT * FROM t_user_organization WHERE manager_user_id = #{userId} AND valid_from <= #{asOf} AND (valid_to IS NULL OR valid_to >= #{asOf}) AND deleted_flag = 0")
    List<UserOrganization> selectActiveByManagerUserId(@Param("userId") Long userId, @Param("asOf") java.time.LocalDate asOf);

    @Select("SELECT organization_id FROM t_user_organization WHERE user_id = #{userId} AND primary_flag = 1 AND valid_from <= #{asOf} AND (valid_to IS NULL OR valid_to >= #{asOf}) AND deleted_flag = 0 ORDER BY id DESC LIMIT 1")
    Long selectPrimaryOrganizationId(@Param("userId") Long userId, @Param("asOf") java.time.LocalDate asOf);
}
