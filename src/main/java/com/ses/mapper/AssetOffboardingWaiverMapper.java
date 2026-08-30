package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AssetOffboardingWaiver;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetOffboardingWaiverMapper extends BaseMapper<AssetOffboardingWaiver> {

    @Select("SELECT w.* FROM t_asset_offboarding_waiver w "
            + "JOIN t_approval_request r ON r.id = w.approval_request_id "
            + "WHERE w.engineer_id = #{engineerId} "
            + "AND w.lifecycle_case_id = #{lifecycleCaseId} "
            + "AND w.lifecycle_task_id = #{lifecycleTaskId} "
            + "AND w.deleted_flag = 0 "
            + "AND r.deleted_flag = 0 AND r.request_type = 'LIFECYCLE_EXCEPTION' "
            + "AND LOWER(r.status) = 'approved' ORDER BY w.approved_at DESC, w.id DESC LIMIT 1")
    AssetOffboardingWaiver selectValidByCaseAndTask(@Param("engineerId") Long engineerId,
                                                    @Param("lifecycleCaseId") Long lifecycleCaseId,
                                                    @Param("lifecycleTaskId") Long lifecycleTaskId);

    @Select("SELECT * FROM t_asset_offboarding_waiver "
            + "WHERE approval_request_id = #{approvalRequestId} AND deleted_flag = 0 LIMIT 1")
    AssetOffboardingWaiver selectByApprovalRequestId(@Param("approvalRequestId") Long approvalRequestId);
}
