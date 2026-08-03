package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ApprovalParticipant;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ApprovalParticipantMapper extends BaseMapper<ApprovalParticipant> {

    @Delete("DELETE FROM t_approval_participant WHERE request_id = #{requestId}")
    int deleteByRequestId(@Param("requestId") Long requestId);
}
