package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.Candidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CandidateMapper extends BaseMapper<Candidate> {

    @Select("""
            <script>
            SELECT c.* FROM t_candidate c
            WHERE c.tenant_id = #{tenantId} AND c.deleted_flag = 0
            <if test="name != null and name != ''">AND c.name LIKE CONCAT('%', #{name}, '%')</if>
            <if test="stage != null and stage != ''">AND c.current_stage = #{stage}</if>
            <if test="skillKeyword != null and skillKeyword != ''">
              AND c.skill_summary LIKE CONCAT('%', #{skillKeyword}, '%')
            </if>
            ORDER BY c.id DESC
            </script>
            """)
    Page<Candidate> selectPageForTenant(Page<Candidate> page,
                                        @Param("tenantId") String tenantId,
                                        @Param("name") String name,
                                        @Param("stage") String stage,
                                        @Param("skillKeyword") String skillKeyword);

    @Select("SELECT * FROM t_candidate WHERE tenant_id = #{tenantId} AND deleted_flag = 0 "
            + "AND next_action_date <= #{today} "
            + "AND current_stage NOT IN ('入社', '不採用', '内定辞退') "
            + "ORDER BY next_action_date ASC")
    java.util.List<Candidate> selectOverdueForTenant(@Param("tenantId") String tenantId,
                                                     @Param("today") java.time.LocalDate today);

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

    @Update("""
            <script>
            UPDATE t_candidate
            <set>
              <if test="candidate.name != null">name = #{candidate.name},</if>
              <if test="candidate.contactEmail != null">contact_email = #{candidate.contactEmail},</if>
              <if test="candidate.contactPhone != null">contact_phone = #{candidate.contactPhone},</if>
              <if test="candidate.skillSummary != null">skill_summary = #{candidate.skillSummary},</if>
              <if test="candidate.desiredRate != null">desired_rate = #{candidate.desiredRate},</if>
              <if test="candidate.source != null">source = #{candidate.source},</if>
              <if test="candidate.nextActionDate != null">next_action_date = #{candidate.nextActionDate},</if>
              <if test="candidate.remarks != null">remarks = #{candidate.remarks},</if>
              version = version + 1
            </set>
            WHERE id = #{candidate.id} AND tenant_id = #{tenantId}
              AND version = #{expectedVersion} AND deleted_flag = 0
            </script>
            """)
    int updateByIdForTenant(@Param("candidate") Candidate candidate,
                            @Param("tenantId") String tenantId,
                            @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_candidate SET current_stage = #{newStage}, version = version + 1 "
            + "WHERE id = #{candidateId} AND tenant_id = #{tenantId} "
            + "AND current_stage = #{currentStage} AND version = #{expectedVersion} AND deleted_flag = 0")
    int updateStageForTenant(@Param("candidateId") Long candidateId,
                             @Param("tenantId") String tenantId,
                             @Param("currentStage") String currentStage,
                             @Param("newStage") String newStage,
                             @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_candidate SET deleted_flag = 1, version = version + 1 "
            + "WHERE id = #{candidateId} AND tenant_id = #{tenantId} "
            + "AND version = #{expectedVersion} AND deleted_flag = 0")
    int deleteByIdForTenant(@Param("candidateId") Long candidateId,
                            @Param("tenantId") String tenantId,
                            @Param("expectedVersion") Integer expectedVersion);
}
