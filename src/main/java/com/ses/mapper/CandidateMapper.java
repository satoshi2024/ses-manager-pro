package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Candidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CandidateMapper extends BaseMapper<Candidate> {

    @Select("SELECT * FROM t_candidate WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0")
    Candidate selectByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT * FROM t_candidate WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND deleted_flag = 0 FOR UPDATE")
    Candidate selectByIdForUpdateForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT COUNT(*) FROM t_candidate WHERE tenant_id = #{tenantId} "
            + "AND converted_engineer_id = #{engineerId} AND deleted_flag = 0")
    long countByConvertedEngineerForTenant(@Param("engineerId") Long engineerId,
                                           @Param("tenantId") String tenantId);

    @Update("UPDATE t_candidate SET converted_engineer_id = #{engineerId}, version = version + 1 "
            + "WHERE id = #{candidateId} AND tenant_id = #{tenantId} AND converted_engineer_id IS NULL "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int linkConvertedEngineerForTenant(@Param("candidateId") Long candidateId,
                                       @Param("engineerId") Long engineerId,
                                       @Param("tenantId") String tenantId,
                                       @Param("expectedVersion") Integer expectedVersion);
}
