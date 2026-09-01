package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceSlaClock;
import org.apache.ibatis.annotations.Mapper;

/**
 * SLA計時・ラウンド履歴マッパー
 */
@Mapper
public interface ServiceSlaClockMapper extends BaseMapper<ServiceSlaClock> {
}
