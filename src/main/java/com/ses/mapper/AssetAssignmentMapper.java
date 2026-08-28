package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AssetAssignment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface AssetAssignmentMapper extends BaseMapper<AssetAssignment> {

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
