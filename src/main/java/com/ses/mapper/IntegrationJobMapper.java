package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.IntegrationJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface IntegrationJobMapper extends BaseMapper<IntegrationJob> {

    /**
     * PENDING または RETRYABLE な job を RUNNING に claim する条件付き CAS 更新。
     * 二重実行防止 (platform-invariants §3.2, design §6.3)。
     */
    @Update("UPDATE t_integration_job " +
            "SET status = 'RUNNING', attempt_count = attempt_count + 1, updated_at = #{now} " +
            "WHERE id = #{id} AND status IN ('PENDING', 'RETRYABLE') AND deleted_flag = 0")
    int claimJob(@Param("id") Long id, @Param("now") LocalDateTime now);
}
