package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ExternalAccountReference;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ExternalAccountReferenceMapper extends BaseMapper<ExternalAccountReference> {

    @Select("SELECT * FROM t_external_account_reference " +
            "WHERE assignee_type = #{assigneeType} AND assignee_id = #{assigneeId} " +
            "  AND status != 'REVOKED' AND deleted_flag = 0")
    List<ExternalAccountReference> selectActiveByAssignee(@Param("assigneeType") String assigneeType,
                                                          @Param("assigneeId") Long assigneeId);

    @Update("UPDATE t_external_account_reference SET status = 'REVOKED', revoke_confirmed_at = #{confirmedAt}, " +
            "revoke_confirmed_by = #{confirmedBy}, version = version + 1 " +
            "WHERE id = #{id} AND status != 'REVOKED' AND version = #{expectedVersion} AND deleted_flag = 0")
    int confirmRevokeWithCas(@Param("id") Long id,
                             @Param("confirmedAt") LocalDateTime confirmedAt,
                             @Param("confirmedBy") Long confirmedBy,
                             @Param("expectedVersion") Integer expectedVersion);
}
