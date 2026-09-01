package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * サービスリクエストマッパー
 */
@Mapper
public interface ServiceRequestMapper extends BaseMapper<ServiceRequest> {
}
