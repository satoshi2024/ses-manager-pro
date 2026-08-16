package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.PortalAccessLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * ポータル操作監査ログマッパー（t_portal_access_log）。
 */
@Mapper
public interface PortalAccessLogMapper extends BaseMapper<PortalAccessLog> {
}
