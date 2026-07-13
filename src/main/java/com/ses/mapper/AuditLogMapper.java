package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * API操作監査ログマッパー。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
