package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.BpAvailability;
import org.apache.ibatis.annotations.Mapper;

/**
 * 外部要員在庫マッパー
 */
@Mapper
public interface BpAvailabilityMapper extends BaseMapper<BpAvailability> {
}
