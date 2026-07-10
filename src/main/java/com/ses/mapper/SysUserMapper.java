package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * システムユーザーマッパー
 * sys_userテーブルに対するデータアクセスインターフェース
 */
public interface SysUserMapper extends BaseMapper<SysUser> {

    /**
     * ユーザー名でユーザーを検索する
     * ログイン認証時にユーザー情報を取得するために使用
     *
     * @param username ユーザー名
     * @return SysUser ユーザーエンティティ（見つからない場合はnull）
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted_flag = 0")
    SysUser selectByUsername(@Param("username") String username);
}
