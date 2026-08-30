package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LicenseAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface LicenseAssignmentMapper extends BaseMapper<LicenseAssignment> {

    @Select("SELECT * FROM t_license_assignment WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    LicenseAssignment selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE t_license_assignment SET status = 'RELEASED', released_date = #{releasedDate}, "
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted_flag = 0 AND status = 'ACTIVE' "
            + "AND released_date IS NULL AND version = #{expectedVersion}")
    int releaseWithCas(@Param("id") Long id,
                       @Param("releasedDate") java.time.LocalDate releasedDate,
                       @Param("expectedVersion") Integer expectedVersion);

    @Select("""
            <script>
            SELECT DISTINCT plan_id FROM t_license_assignment
            WHERE deleted_flag = 0 AND assignee_type = 'ENGINEER'
              AND status = 'ACTIVE' AND released_date IS NULL
              AND assignee_id IN
              <foreach collection="engineerIds" item="engineerId" open="(" separator="," close=")">#{engineerId}</foreach>
            </script>
            """)
    List<Long> selectActivePlanIdsByEngineerIds(@Param("engineerIds") List<Long> engineerIds);

    @Select("SELECT * FROM t_license_assignment " +
            "WHERE assignee_type = #{assigneeType} AND assignee_id = #{assigneeId} " +
            "  AND (status = 'ACTIVE' OR released_date IS NULL) AND deleted_flag = 0")
    List<LicenseAssignment> selectActiveByAssignee(@Param("assigneeType") String assigneeType,
                                                   @Param("assigneeId") Long assigneeId);
}
