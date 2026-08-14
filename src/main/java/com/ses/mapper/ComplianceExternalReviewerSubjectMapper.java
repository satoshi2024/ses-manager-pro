package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ComplianceExternalReviewerSubject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ComplianceExternalReviewerSubjectMapper extends BaseMapper<ComplianceExternalReviewerSubject> {

    /** P1-2: tenant境界付きsubject解決（裸selectByIdの置換）。 */
    @Select("SELECT * FROM t_compliance_external_reviewer_subject "
            + "WHERE tenant_id = #{tenantId} AND id = #{id} AND deleted_flag = 0")
    ComplianceExternalReviewerSubject selectByTenantAndId(@Param("tenantId") String tenantId,
                                                          @Param("id") Long id);
}
