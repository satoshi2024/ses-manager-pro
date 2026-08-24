package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.UserExternalIdentity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

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

    @Update("UPDATE t_user_external_identity "
            + "SET review_status = 'APPROVED', reviewed_at = #{reviewedAt}, reviewed_by = #{reviewedBy}, "
            + "updated_at = #{reviewedAt} "
            + "WHERE id = #{id} AND deleted_flag = 0 "
            + "AND IFNULL(review_status, '') <> 'APPROVED'")
    int approveIfNotApproved(@Param("id") Long id,
                             @Param("reviewedBy") Long reviewedBy,
                             @Param("reviewedAt") LocalDateTime reviewedAt);
}
