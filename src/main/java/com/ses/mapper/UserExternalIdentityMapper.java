package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.UserExternalIdentity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface UserExternalIdentityMapper extends BaseMapper<UserExternalIdentity> {

    @Select("SELECT * FROM t_user_external_identity WHERE tenant_id = #{tenantId} "
            + "AND provider_id = #{providerId} AND subject = #{subject} "
            + "AND deleted_flag = 0 LIMIT 1")
    UserExternalIdentity selectByTenantProviderAndSubject(@Param("tenantId") String tenantId,
                                                           @Param("providerId") Long providerId,
                                                           @Param("subject") String subject);

    /**
     * DuplicateKey 後の再読用。REPEATABLE READ の snapshot 読みを避け、
     * 先行 commit 済み行を current read で取得する。
     */
    @Select("SELECT * FROM t_user_external_identity WHERE tenant_id = #{tenantId} "
            + "AND provider_id = #{providerId} AND subject = #{subject} "
            + "AND deleted_flag = 0 LIMIT 1 FOR UPDATE")
    UserExternalIdentity selectByTenantProviderAndSubjectForUpdate(@Param("tenantId") String tenantId,
                                                                    @Param("providerId") Long providerId,
                                                                    @Param("subject") String subject);

    @Select("SELECT * FROM t_user_external_identity WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    UserExternalIdentity selectByIdForUpdate(@Param("id") Long id);

    /**
     * 指定providerに対してAPPROVEDの外部identity linkを持つ、指定roleの有効なユーザーのusername（重複なし）。
     * 本番起動時検査（{@code ProductionSecurityConfigurationValidator}）が、OIDCでログインできる管理者の人数を数えるのに使う。
     * 条件は {@code OidcLoginUserService} のDB側の照合（link・承認・内部ユーザーの有効性）と揃える。
     */
    @Select("SELECT DISTINCT u.username FROM t_user_external_identity x JOIN sys_user u ON u.id = x.user_id "
            + "WHERE x.tenant_id = #{tenantId} AND x.provider_id = #{providerId} "
            + "AND x.review_status = 'APPROVED' AND x.deleted_flag = 0 "
            + "AND u.deleted_flag = 0 AND u.status = 1 AND u.role = #{role}")
    List<String> selectApprovedActiveUsernamesByRole(@Param("tenantId") String tenantId,
                                                     @Param("providerId") Long providerId,
                                                     @Param("role") String role);

    @Update("UPDATE t_user_external_identity "
            + "SET review_status = 'APPROVED', reviewed_at = #{reviewedAt}, reviewed_by = #{reviewedBy}, "
            + "updated_at = #{reviewedAt} "
            + "WHERE id = #{id} AND deleted_flag = 0 "
            + "AND IFNULL(review_status, '') <> 'APPROVED'")
    int approveIfNotApproved(@Param("id") Long id,
                             @Param("reviewedBy") Long reviewedBy,
                             @Param("reviewedAt") LocalDateTime reviewedAt);
}
