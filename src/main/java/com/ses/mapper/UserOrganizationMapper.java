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

    /**
     * 組織統合で付け替える対象を行ロック付きで取得する。
     *
     * <p>{@code valid_to IS NULL}だけを対象にすると、期間限定（統合日以降に終了予定）の
     * 現在有効な所属を取りこぼし、無効化された統合元組織に有効な所属だけが残ってしまう
     * （第十三次Review P1-2）。統合日時点でまだ終了していない所属（無期限、または
     * 終了日が統合日以降）を対象にする。
     */
    @Select("SELECT * FROM t_user_organization WHERE organization_id = #{organizationId} "
            + "AND (valid_to IS NULL OR valid_to >= #{mergeDate}) "
            + "AND deleted_flag = 0 ORDER BY id FOR UPDATE")
    List<UserOrganization> selectByUserOrganizationForUpdate(@Param("organizationId") Long organizationId,
                                                              @Param("mergeDate") java.time.LocalDate mergeDate);

    /** 退職者を上長から外す。承認者不在の所属を残さないため。 */
    @org.apache.ibatis.annotations.Update(
            "UPDATE t_user_organization SET manager_user_id = NULL WHERE manager_user_id = #{userId} AND deleted_flag = 0")
    int clearManager(@Param("userId") Long userId);
}
