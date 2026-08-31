package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ApprovalAction;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApprovalActionMapper extends BaseMapper<ApprovalAction> {

    @Select("SELECT * FROM t_approval_action "
            + "WHERE request_id = #{requestId} AND action = 'APPROVE' "
            + "ORDER BY acted_at DESC, id DESC LIMIT 1")
    ApprovalAction selectLatestApprovalByRequestId(@Param("requestId") Long requestId);
}
