package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

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

    /**
     * ログイン失敗回数とロック解除日時を更新する。
     * lockedUntil に null を渡すとロック解除（NULLに設定）される。
     */
    @Update("UPDATE sys_user SET failed_count = #{failedCount}, locked_until = #{lockedUntil} WHERE id = #{id}")
    int updateLockState(@Param("id") Long id,
                        @Param("failedCount") int failedCount,
                        @Param("lockedUntil") LocalDateTime lockedUntil);
}
