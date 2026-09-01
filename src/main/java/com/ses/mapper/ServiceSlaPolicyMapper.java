package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceSlaPolicy;
import org.apache.ibatis.annotations.Mapper;

/**
 * SLAポリシーマッパー
 */
@Mapper
public interface ServiceSlaPolicyMapper extends BaseMapper<ServiceSlaPolicy> {
}
