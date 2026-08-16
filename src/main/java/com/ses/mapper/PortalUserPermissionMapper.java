package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PortalUserPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * ポータルユーザー権限マッパー（t_portal_user_permission）。
 */
@Mapper
public interface PortalUserPermissionMapper extends BaseMapper<PortalUserPermission> {

    @Select("SELECT permission_key FROM t_portal_user_permission WHERE user_id = #{userId}")
    List<String> selectPermissionKeys(Long userId);
}
