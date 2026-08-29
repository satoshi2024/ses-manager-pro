package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AssetAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AssetAssignmentMapper extends BaseMapper<AssetAssignment> {

    @Select("SELECT * FROM t_asset_assignment WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    AssetAssignment selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE t_asset_assignment SET actual_return_date = #{actualReturnDate}, "
            + "return_evidence_doc_id = #{returnEvidenceDocId}, status = 'RETURNED', note = #{note}, "
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted_flag = 0 "
            + "AND status IN ('ACTIVE', 'OVERDUE') AND actual_return_date IS NULL "
            + "AND version = #{expectedVersion}")
    int markReturnedWithCas(@Param("id") Long id,
                            @Param("actualReturnDate") LocalDate actualReturnDate,
                            @Param("returnEvidenceDocId") Long returnEvidenceDocId,
                            @Param("note") String note,
                            @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_asset_assignment SET actual_return_date = #{actualReturnDate}, "
            + "status = 'WAIVED', note = #{note}, version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND deleted_flag = 0 "
            + "AND status IN ('ACTIVE', 'OVERDUE') AND actual_return_date IS NULL "
            + "AND version = #{expectedVersion}")
    int markWaivedWithCas(@Param("id") Long id,
                          @Param("actualReturnDate") LocalDate actualReturnDate,
                          @Param("note") String note,
                          @Param("expectedVersion") Integer expectedVersion);

    @Select("SELECT COUNT(*) FROM t_asset_assignment " +
            "WHERE asset_id = #{assetId} AND deleted_flag = 0 " +
            "  AND (actual_return_date IS NULL OR actual_return_date >= #{startDate}) " +
            "  AND (#{endDate} IS NULL OR start_date <= #{endDate}) " +
            "  AND (#{excludeAssignmentId} IS NULL OR id != #{excludeAssignmentId})")
    int countOverlappingAssignments(@Param("assetId") Long assetId,
                                    @Param("startDate") LocalDate startDate,
                                    @Param("endDate") LocalDate endDate,
                                    @Param("excludeAssignmentId") Long excludeAssignmentId);

    @Select("SELECT * FROM t_asset_assignment " +
            "WHERE assignee_type = #{assigneeType} AND assignee_id = #{assigneeId} " +
            "  AND actual_return_date IS NULL AND deleted_flag = 0")
    List<AssetAssignment> selectActiveByAssignee(@Param("assigneeType") String assigneeType,
                                                @Param("assigneeId") Long assigneeId);
}
