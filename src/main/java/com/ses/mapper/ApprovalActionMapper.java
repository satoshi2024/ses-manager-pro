package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ApprovalAction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import com.ses.service.accounting.AccountingTenantContextHolder;

@Mapper
public interface ApprovalActionMapper extends BaseMapper<ApprovalAction> {

    @Select("SELECT * FROM t_approval_action "
            + "WHERE tenant_id = #{tenantId} AND request_id = #{requestId} AND action = 'APPROVE' "
            + "ORDER BY acted_at DESC, id DESC LIMIT 1")
    ApprovalAction selectLatestApprovalByRequestId(@Param("requestId") Long requestId,
                                                    @Param("tenantId") String tenantId);

    /** 月次締めの最終承認者は、requestのtenant・round・stepを同時に条件にする。 */
    @Select("SELECT * FROM t_approval_action "
            + "WHERE tenant_id = #{tenantId} AND request_id = #{requestId} "
            + "AND round_no = #{roundNo} AND step_no = #{stepNo} AND action = 'APPROVE' "
            + "ORDER BY acted_at DESC, id DESC LIMIT 1")
    ApprovalAction selectLatestApprovalForStep(@Param("requestId") Long requestId,
                                               @Param("tenantId") String tenantId,
                                               @Param("roundNo") Integer roundNo,
                                               @Param("stepNo") Integer stepNo);

    default ApprovalAction selectLatestApprovalByRequestId(Long requestId) {
        return selectLatestApprovalByRequestId(requestId,
                AccountingTenantContextHolder.requireTenantContext());
    }
}
