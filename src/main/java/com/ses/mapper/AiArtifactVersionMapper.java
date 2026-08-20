package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AiArtifactVersion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface AiArtifactVersionMapper extends BaseMapper<AiArtifactVersion> {

    @Update("UPDATE m_ai_artifact_version SET status = 'RETIRED', retired_at = #{retiredAt}, "
            + "status_version = status_version + 1, updated_at = #{retiredAt} "
            + "WHERE id = #{id} AND status = 'ACTIVE' AND status_version = #{statusVersion} "
            + "AND deleted_flag = 0")
    int casRetireActive(@Param("id") Long id,
                        @Param("statusVersion") int statusVersion,
                        @Param("retiredAt") LocalDateTime retiredAt);

    @Update("UPDATE m_ai_artifact_version SET status = 'ACTIVE', activated_at = #{activatedAt}, "
            + "retired_at = NULL, status_version = status_version + 1, updated_at = #{activatedAt} "
            + "WHERE id = #{id} AND status = 'SHADOW' AND status_version = #{statusVersion} "
            + "AND deleted_flag = 0")
    int casActivateShadow(@Param("id") Long id,
                          @Param("statusVersion") int statusVersion,
                          @Param("activatedAt") LocalDateTime activatedAt);
}
