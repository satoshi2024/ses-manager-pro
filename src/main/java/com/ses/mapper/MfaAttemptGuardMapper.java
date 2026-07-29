package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.MfaAttemptGuard;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MfaAttemptGuardMapper extends BaseMapper<MfaAttemptGuard> {

    @Select("SELECT * FROM t_mfa_attempt_guard WHERE tenant_id = #{tenantId} AND user_id = #{userId} "
            + "AND session_key_hash = #{sessionKeyHash} AND source_hash = #{sourceHash} "
            + "AND deleted_flag = 0 LIMIT 1 FOR UPDATE")
    MfaAttemptGuard selectForUpdate(@Param("tenantId") String tenantId,
                                    @Param("userId") Long userId,
                                    @Param("sessionKeyHash") String sessionKeyHash,
                                    @Param("sourceHash") String sourceHash);
}
