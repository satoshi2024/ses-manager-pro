package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PortalUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * ポータルユーザーマッパー（t_portal_user）。
 * 内部sys_userとは別identity（G3）。emailでloginする。
 */
@Mapper
public interface PortalUserMapper extends BaseMapper<PortalUser> {

    @Select("SELECT * FROM t_portal_user WHERE email = #{email} AND deleted_flag = 0")
    PortalUser selectByEmail(String email);

    /**
     * 最終login日時を記録する（login成功時。SUSPENDED判定はservice側）。
     */
    @Update("UPDATE t_portal_user SET last_login_at = #{at} WHERE id = #{id} AND deleted_flag = 0")
    int updateLastLogin(@Param("id") Long id, @Param("at") LocalDateTime at);
}
