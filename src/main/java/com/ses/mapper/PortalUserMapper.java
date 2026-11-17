package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PortalUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ポータルユーザーマッパー（t_portal_user）。
 * 内部sys_userとは別identity（G3）。emailでloginする。
 */
@Mapper
public interface PortalUserMapper extends BaseMapper<PortalUser> {

    @Select("SELECT * FROM t_portal_user WHERE email = #{email} AND deleted_flag = 0")
    PortalUser selectByEmail(String email);

    /**
     * 論理削除済みを含めてemailで検索する（招待受諾のreactivate判定用。
     * email UNIQUEはdeleted行も保持するため。loginはselectByEmailを使用すること）。
     */
    @Select("SELECT * FROM t_portal_user WHERE email = #{email}")
    PortalUser selectByEmailIncludingDeleted(String email);

    /**
     * 招待受諾によるreactivate（S13-R1-P0-01）。
     * 論理削除済み行も対象にするため、@TableLogicの自動条件を回避するカスタムUPDATE。
     * portal_org_id は招待の組織へ必ず付け替える（組織跨ぎの残留防止）。
     */
    @Update("UPDATE t_portal_user SET portal_org_id = #{portalOrgId}, display_name = #{displayName},"
            + " password_hash = #{passwordHash}, status = 'ACTIVE',"
            + " mfa_enabled_at = NULL, totp_secret_encrypted = NULL, totp_secret_key_version = NULL,"
            + " recovery_code_hash = NULL, recovery_code_used_at = NULL, last_used_step = NULL,"
            + " deleted_flag = 0"
            + " WHERE id = #{id}")
    int reactivate(@Param("id") Long id, @Param("portalOrgId") Long portalOrgId,
                   @Param("displayName") String displayName, @Param("passwordHash") String passwordHash);

    /**
     * 最終login日時を記録する（login成功時。SUSPENDED判定はservice側）。
     */
    @Update("UPDATE t_portal_user SET last_login_at = #{at} WHERE id = #{id} AND deleted_flag = 0")
    int updateLastLogin(@Param("id") Long id, @Param("at") LocalDateTime at);

    /**
     * 組織のACTIVE userのIDをlocking readで取得する（ADMIN招待受諾の組織管理者確認用。RB-PRERUN 3）。
     * 受諾のtransactionは先に招待を非lock読みしているため、REPEATABLE READのread viewより後にcommitされた
     * 組織管理者も見えるよう、通常のSELECTではなくlocking readで最新の確定値を読む。
     */
    @Select("SELECT id FROM t_portal_user WHERE portal_org_id = #{orgId} AND status = 'ACTIVE'"
            + " AND deleted_flag = 0 FOR UPDATE")
    List<Long> selectActiveIdsByOrgForUpdate(@Param("orgId") Long orgId);
}
