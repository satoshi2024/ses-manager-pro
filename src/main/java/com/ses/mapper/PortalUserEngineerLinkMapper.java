package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PortalUserEngineerLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * ポータルユーザーと要員の紐付け（V188）。
 * 退社自動処理の対象特定に使う照会は、紐付け・ポータルユーザー・ポータル組織の3者とも案件tenantに限定する。
 */
@Mapper
public interface PortalUserEngineerLinkMapper extends BaseMapper<PortalUserEngineerLink> {

    /**
     * 要員の現行紐付けのうち、紐付けtenant・ポータル組織tenantがともに案件tenantで、ポータルユーザーと組織が
     * 未削除のもの（退社自動処理の対象）。ポータルユーザーID順。
     */
    @Select("SELECT l.* FROM t_portal_user_engineer_link l "
            + "JOIN t_portal_user u ON u.id = l.portal_user_id AND u.deleted_flag = 0 "
            + "JOIN m_portal_organization o ON o.id = u.portal_org_id AND o.deleted_flag = 0 "
            + "WHERE l.engineer_id = #{engineerId} AND l.tenant_id = #{tenantId} AND o.tenant_id = #{tenantId} "
            + "AND l.released_at IS NULL AND l.deleted_flag = 0 ORDER BY l.portal_user_id")
    List<PortalUserEngineerLink> selectActiveForEngineerInTenant(@Param("engineerId") Long engineerId,
                                                                 @Param("tenantId") String tenantId);

    /**
     * 要員の現行紐付けのうち、紐付けtenantが案件tenantと違う、または未削除のポータルユーザーの組織が無い・
     * 組織tenantが案件tenantと違うものの件数（データ不整合。退社自動処理はfail-closedにする）。
     */
    @Select("SELECT COUNT(*) FROM t_portal_user_engineer_link l "
            + "LEFT JOIN t_portal_user u ON u.id = l.portal_user_id AND u.deleted_flag = 0 "
            + "LEFT JOIN m_portal_organization o ON o.id = u.portal_org_id AND o.deleted_flag = 0 "
            + "WHERE l.engineer_id = #{engineerId} AND l.released_at IS NULL AND l.deleted_flag = 0 "
            + "AND (l.tenant_id <> #{tenantId} OR (u.id IS NOT NULL AND (o.id IS NULL OR o.tenant_id <> #{tenantId})))")
    Long countActiveScopeMismatchForEngineer(@Param("engineerId") Long engineerId,
                                             @Param("tenantId") String tenantId);

    /**
     * 案件tenantのポータル組織に属する未削除のポータルユーザーのうち、emailが一致し、どの要員とも現行紐付けを
     * 持たないもののID（退社ゲートの「要確認」候補。自動処理はしない）。
     */
    @Select("SELECT u.id FROM t_portal_user u "
            + "JOIN m_portal_organization o ON o.id = u.portal_org_id AND o.deleted_flag = 0 "
            + "WHERE u.deleted_flag = 0 AND o.tenant_id = #{tenantId} AND LOWER(u.email) = LOWER(#{email}) "
            + "AND NOT EXISTS (SELECT 1 FROM t_portal_user_engineer_link l WHERE l.portal_user_id = u.id "
            + "AND l.released_at IS NULL AND l.deleted_flag = 0) ORDER BY u.id")
    List<Long> selectUnlinkedPortalUserIdsByEmailInTenant(@Param("email") String email,
                                                          @Param("tenantId") String tenantId);

    /** ポータルユーザーの現行紐付け（未解除・未削除）。無ければnull。 */
    @Select("SELECT * FROM t_portal_user_engineer_link WHERE portal_user_id = #{portalUserId} "
            + "AND released_at IS NULL AND deleted_flag = 0")
    PortalUserEngineerLink selectActiveByPortalUserId(@Param("portalUserId") Long portalUserId);
}
