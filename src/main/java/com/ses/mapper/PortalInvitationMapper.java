package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PortalInvitation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * ポータル招待マッパー（t_portal_invitation）。
 * 招待tokenの一回性はDB CAS（UPDATE ... WHERE used_at IS NULL）で保証する（design §6.3）。
 * アプリ側の「存在チェック→更新」にしない（同時使用で二重登録されるため）。
 */
@Mapper
public interface PortalInvitationMapper extends BaseMapper<PortalInvitation> {

    @Select("SELECT * FROM t_portal_invitation WHERE token_hash = #{tokenHash} AND deleted_flag = 0")
    PortalInvitation selectByTokenHash(String tokenHash);

    /**
     * 一回性CAS: used_atがNULLの行だけを消費済みにする。更新0件=既に使用済み（同時使用の敗者）。
     */
    @Update("UPDATE t_portal_invitation SET used_at = #{usedAt}, accepted_by = #{acceptedBy}"
            + " WHERE id = #{id} AND used_at IS NULL AND deleted_flag = 0")
    int consumeIfUnused(@Param("id") Long id, @Param("usedAt") LocalDateTime usedAt,
                        @Param("acceptedBy") Long acceptedBy);

    /**
     * 同一組織×emailで未使用・未期限切れの招待が既に存在するか（重複招待の防止）。
     */
    @Select("SELECT COUNT(*) FROM t_portal_invitation WHERE portal_org_id = #{portalOrgId}"
            + " AND email = #{email} AND used_at IS NULL AND expires_at > #{now} AND deleted_flag = 0")
    long countActiveInvitation(@Param("portalOrgId") Long portalOrgId, @Param("email") String email,
                               @Param("now") LocalDateTime now);
}
