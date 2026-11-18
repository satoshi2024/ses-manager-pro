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

    @Select("SELECT * FROM sys_user WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0")
    SysUser selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    /** 信頼済みの要員ID/tenantから失敗回数を更新するtransactionで対象行をロックする。 */
    @Select("SELECT * FROM sys_user WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0 FOR UPDATE")
    SysUser selectByIdAndTenantForUpdate(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT * FROM sys_user WHERE tenant_id = #{tenantId} AND role = #{role} "
            + "AND status = 1 AND deleted_flag = 0 ORDER BY id")
    List<SysUser> selectActiveByRoleAndTenant(@Param("role") String role,
                                               @Param("tenantId") String tenantId);

    /** ロール候補を現在tenant内だけで取得する（無効ユーザーも理由判定のため含める）。 */
    @Select("SELECT * FROM sys_user WHERE tenant_id = #{tenantId} AND role = #{role} "
            + "AND deleted_flag = 0 ORDER BY id")
    List<SysUser> selectByRoleAndTenant(@Param("role") String role,
                                        @Param("tenantId") String tenantId);

    /** 内部候補一覧を現在tenant内だけで取得する。 */
    @Select("SELECT * FROM sys_user WHERE tenant_id = #{tenantId} "
            + "AND deleted_flag = 0 ORDER BY id")
    List<SysUser> selectByTenant(@Param("tenantId") String tenantId);

    /** 管理者向けユーザー候補の表示名もtenant境界をSQLで適用する。 */
    @Select("SELECT username FROM sys_user WHERE tenant_id = #{tenantId} "
            + "AND deleted_flag = 0 ORDER BY id")
    List<String> selectUsernamesByTenant(@Param("tenantId") String tenantId);

    @Select("SELECT * FROM sys_user WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    SysUser selectByIdForUpdate(@Param("id") Long id);

    /**
     * NF01 lock rank 3 の利用者行X lock（細粒度epoch計画 §3.4「所属writer（release・closeAssignmentsForUser）」）。
     * 論理削除済みの行も返す（利用者削除は論理削除の後に所属を閉じるため、削除済みの行もlockしてからactor epochを進める）。
     */
    @Select("SELECT * FROM sys_user WHERE id = #{id} FOR UPDATE")
    SysUser selectByIdForUpdateIncludingDeleted(@Param("id") Long id);

    /**
     * NF01 lock rank 3 の利用者行lock（細粒度epoch計画 §3.1・§3.4）。論理削除済みの行は返さない。
     * {@code tenantId} が {@code null} ならIDだけで照合する。{@code exclusive=false} は {@code FOR SHARE} で、
     * H2は解釈しないため呼出しは {@code ActorShareLock} だけにする（H2では {@code exclusive=true} で代える）。
     */
    @Select("<script>SELECT * FROM sys_user WHERE id = #{id} AND deleted_flag = 0"
            + "<if test='tenantId != null'> AND tenant_id = #{tenantId}</if> "
            + "<choose><when test='exclusive'>FOR UPDATE</when><otherwise>FOR SHARE</otherwise></choose></script>")
    SysUser selectActiveByIdForLock(@Param("id") Long id, @Param("tenantId") String tenantId,
                                    @Param("exclusive") boolean exclusive);

    /**
     * ユーザー名でユーザーを検索する
     * ログイン認証時にユーザー情報を取得するために使用
     *
     * @param username ユーザー名
     * @return SysUser ユーザーエンティティ（見つからない場合はnull）
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted_flag = 0")
    SysUser selectByUsername(@Param("username") String username);

    /** ログイン失敗の更新transaction内で最新状態を取得し、同じユーザーへの加算を直列化する。 */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted_flag = 0 FOR UPDATE")
    SysUser selectByUsernameForUpdate(@Param("username") String username);

    @Select("SELECT * FROM sys_user WHERE username = #{username} AND tenant_id = #{tenantId} "
            + "AND deleted_flag = 0")
    SysUser selectByUsernameAndTenant(@Param("username") String username,
                                      @Param("tenantId") String tenantId);

    /** emailは一意とは限らないため、OIDC自動linkに使わず衝突検知専用にcollectionで返す。 */
    @Select("SELECT * FROM sys_user WHERE LOWER(email) = LOWER(#{email}) AND deleted_flag = 0")
    Collection<SysUser> selectByEmail(@Param("email") String email);

    /**
     * ログイン失敗回数とロック解除日時を更新する。
     * lockedUntil に null を渡すとロック解除（NULLに設定）される。
     */
    @Update("UPDATE sys_user SET failed_count = #{failedCount}, locked_until = #{lockedUntil} WHERE id = #{id}")
    int updateLockState(@Param("id") Long id,
                        @Param("failedCount") int failedCount,
                        @Param("lockedUntil") LocalDateTime lockedUntil);

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
