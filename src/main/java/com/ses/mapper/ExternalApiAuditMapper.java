package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ExternalApiAudit;
import org.apache.ibatis.annotations.Mapper;

/** NF-05専用外部API監査mapper。 */
@Mapper
public interface ExternalApiAuditMapper extends BaseMapper<ExternalApiAudit> {
}
