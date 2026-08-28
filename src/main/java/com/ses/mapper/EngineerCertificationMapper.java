package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.EngineerCertification;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface EngineerCertificationMapper extends BaseMapper<EngineerCertification> {

    @Select("SELECT * FROM t_engineer_certification WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    EngineerCertification selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT COUNT(*) FROM t_engineer_certification "
            + "WHERE tenant_id = #{tenantId} AND engineer_id = #{engineerId} "
            + "AND certification_id = #{certificationId} AND acquired_on = #{acquiredOn} "
            + "AND record_state IN ('DRAFT','SUBMITTED','VERIFIED','ACTIVE') "
            + "AND deleted_flag = 0 AND (#{excludeId} IS NULL OR id <> #{excludeId})")
    long countNonTerminalAcquisition(@Param("tenantId") String tenantId,
                                     @Param("engineerId") Long engineerId,
                                     @Param("certificationId") Long certificationId,
                                     @Param("acquiredOn") java.time.LocalDate acquiredOn,
                                     @Param("excludeId") Long excludeId);

    @Update("UPDATE t_engineer_certification SET record_state = #{recordState}, "
            + "current_flag = #{currentFlag}, current_holder_key = #{currentHolderKey}, "
            + "acquired_on = #{acquiredOn}, expires_on = #{expiresOn}, "
            + "expiry_rule_version = #{expiryRuleVersion}, revision = #{revision}, "
            + "updated_by = #{updatedBy}, version = version + 1 "
            + "WHERE id = #{id} AND version = #{expectedVersion} AND deleted_flag = 0")
    int updateLifecycleCas(@Param("id") Long id,
                           @Param("expectedVersion") Integer expectedVersion,
                           @Param("recordState") String recordState,
                           @Param("currentFlag") Integer currentFlag,
                           @Param("currentHolderKey") Long currentHolderKey,
                           @Param("acquiredOn") java.time.LocalDate acquiredOn,
                           @Param("expiresOn") java.time.LocalDate expiresOn,
                           @Param("expiryRuleVersion") Integer expiryRuleVersion,
                           @Param("revision") Integer revision,
                           @Param("updatedBy") Long updatedBy);
}
