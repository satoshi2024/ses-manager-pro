package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.SysUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

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

    /**
     * ログイン失敗回数をデータベース上で原子的に加算する。期限切れロックは
     * 新しいカウント周期として扱い、閾値到達時のみロック日時を設定する。
     */
    @Update("""
        UPDATE sys_user
        SET failed_count = CASE
              WHEN (CASE WHEN locked_until IS NOT NULL AND locked_until <= #{now} THEN 1
                         ELSE COALESCE(failed_count, 0) + 1 END) >= #{maxFailed} THEN 0
              WHEN locked_until IS NOT NULL AND locked_until <= #{now} THEN 1
              ELSE COALESCE(failed_count, 0) + 1 END,
            locked_until = CASE
              WHEN (CASE WHEN locked_until IS NOT NULL AND locked_until <= #{now} THEN 1
                         ELSE COALESCE(failed_count, 0) + 1 END) >= #{maxFailed}
                THEN TIMESTAMPADD(MINUTE, #{lockMinutes}, #{now})
              WHEN locked_until IS NOT NULL AND locked_until <= #{now} THEN NULL
              ELSE locked_until END
        WHERE id = #{id} AND deleted_flag = 0
        """)
    int incrementLoginFailure(@Param("id") Long id,
                              @Param("now") LocalDateTime now,
                              @Param("maxFailed") int maxFailed,
                              @Param("lockMinutes") int lockMinutes);

    /**
     * 営業成績の過去実績表示用に、論理削除済みユーザーも含めて取得する。
     */
    @Select("""
        <script>
        SELECT * FROM sys_user
        WHERE id IN
        <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    List<SysUser> selectByIdsIncludingDeleted(@Param("ids") Collection<Long> ids);

    /**
     * sys_user.username のテーブル一意制約違反を防止するため、論理削除済みも含めて重複件数を取得する。
     */
    @Select("SELECT COUNT(*) FROM sys_user WHERE username = #{username} AND (#{excludeId} IS NULL OR id != #{excludeId})")
    long countUsernameIncludingDeleted(@Param("username") String username, @Param("excludeId") Long excludeId);
}
