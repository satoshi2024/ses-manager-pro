package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ApiRetentionHold;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** NF-05 legal hold mapper。 */
@Mapper
public interface ApiRetentionHoldMapper extends BaseMapper<ApiRetentionHold> {
    @Select("SELECT * FROM t_api_retention_hold WHERE record_kind = #{recordKind} "
            + "AND record_id = #{recordId} FOR UPDATE")
    ApiRetentionHold selectForUpdate(@Param("recordKind") String recordKind, @Param("recordId") Long recordId);

    @Update("UPDATE t_api_retention_hold SET status = 'ACTIVE', hold_generation = hold_generation + 1, "
            + "reason_code = #{reasonCode}, released_at = NULL, version = version + 1, updated_at = #{updatedAt} "
            + "WHERE id = #{id} AND version = #{version} AND status = 'RELEASED'")
    int reacquire(@Param("id") Long id, @Param("version") Integer version,
                  @Param("reasonCode") String reasonCode, @Param("updatedAt") java.time.LocalDateTime updatedAt);

    @Update("UPDATE t_api_retention_hold SET status = 'RELEASED', released_at = #{releasedAt}, "
            + "version = version + 1, updated_at = #{releasedAt} WHERE id = #{id} AND version = #{version} "
            + "AND status = 'ACTIVE'")
    int release(@Param("id") Long id, @Param("version") Integer version,
                @Param("releasedAt") java.time.LocalDateTime releasedAt);
}
