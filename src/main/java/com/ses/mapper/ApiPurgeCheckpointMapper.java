package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ApiPurgeCheckpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** NF-05 purge checkpoint mapper。 */
@Mapper
public interface ApiPurgeCheckpointMapper extends BaseMapper<ApiPurgeCheckpoint> {
    @Select("SELECT * FROM t_api_purge_checkpoint WHERE record_kind = #{recordKind} "
            + "AND retention_class = #{retentionClass} FOR UPDATE")
    ApiPurgeCheckpoint selectForUpdate(@Param("recordKind") String recordKind,
                                       @Param("retentionClass") String retentionClass);

    @Update("UPDATE t_api_purge_checkpoint SET run_status = 'RUNNING', started_at = #{startedAt}, "
            + "completed_at = NULL, version = version + 1, updated_at = #{startedAt} "
            + "WHERE id = #{id} AND version = #{version}")
    int startBatch(@Param("id") Long id, @Param("version") Integer version,
                   @Param("startedAt") java.time.LocalDateTime startedAt);

    @Update("UPDATE t_api_purge_checkpoint SET last_expires_at = #{lastExpiresAt}, "
            + "last_record_id = #{lastRecordId}, run_status = 'COMPLETE', completed_at = #{completedAt}, "
            + "version = version + 1, updated_at = #{completedAt} WHERE id = #{id} AND version = #{version}")
    int completeBatch(@Param("id") Long id, @Param("version") Integer version,
                      @Param("lastExpiresAt") java.time.LocalDateTime lastExpiresAt,
                      @Param("lastRecordId") Long lastRecordId,
                      @Param("completedAt") java.time.LocalDateTime completedAt);

    @Update("UPDATE t_api_purge_checkpoint SET restore_epoch = #{restoreEpoch}, last_expires_at = NULL, "
            + "last_record_id = NULL, run_status = 'READY', started_at = NULL, completed_at = NULL, "
            + "version = version + 1, updated_at = #{updatedAt} WHERE id = #{id} AND version = #{version}")
    int resetAfterRestore(@Param("id") Long id, @Param("version") Integer version,
                          @Param("restoreEpoch") Long restoreEpoch,
                          @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
