package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ExternalApiAudit;
import org.apache.ibatis.annotations.Mapper;

/** NF-05専用外部API監査mapper。 */
@Mapper
public interface ExternalApiAuditMapper extends BaseMapper<ExternalApiAudit> {
    @org.apache.ibatis.annotations.Select("SELECT * FROM t_external_api_audit WHERE id = #{id} FOR UPDATE")
    ExternalApiAudit selectForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);

    @org.apache.ibatis.annotations.Delete("DELETE FROM t_external_api_audit WHERE id = #{id} AND retention_expires_at <= #{now}")
    int deleteExpired(@org.apache.ibatis.annotations.Param("id") Long id,
                      @org.apache.ibatis.annotations.Param("now") java.time.LocalDateTime now);
}
