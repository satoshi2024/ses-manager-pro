package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PortalUserPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * ポータルユーザー権限マッパー（t_portal_user_permission）。
 */
@Mapper
public interface PortalUserPermissionMapper extends BaseMapper<PortalUserPermission> {

    @Select("SELECT permission_key FROM t_portal_user_permission WHERE user_id = #{userId}")
    List<String> selectPermissionKeys(Long userId);

    /**
     * 指定userのうち権限を持つuserのIDをlocking readで取得する（ADMIN招待受諾の組織管理者確認用。RB-PRERUN 3）。
     * 呼出側は {@code userIds} を空にしないこと。
     */
    @Select({"<script>",
            "SELECT user_id FROM t_portal_user_permission WHERE permission_key = #{permissionKey} AND user_id IN",
            "<foreach collection='userIds' item='userId' open='(' separator=',' close=')'>#{userId}</foreach>",
            "FOR UPDATE",
            "</script>"})
    List<Long> selectUserIdsWithPermissionForUpdate(@Param("userIds") Collection<Long> userIds,
                                                    @Param("permissionKey") String permissionKey);
}
