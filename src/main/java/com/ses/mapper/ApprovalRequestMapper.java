package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ApprovalRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ApprovalRequestMapper extends BaseMapper<ApprovalRequest> {

    /** 最終承認transactionの順序（request行ロック→target version再検証→…）を守るための行ロック取得。 */
    @Select("SELECT * FROM t_approval_request WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    ApprovalRequest selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_approval_request WHERE idempotency_key = #{idempotencyKey} AND deleted_flag = 0")
    ApprovalRequest selectByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);
}
