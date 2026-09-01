package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.CustomerCsat;
import org.apache.ibatis.annotations.Mapper;

/**
 * 顧客満足度調査回答マッパー
 */
@Mapper
public interface CustomerCsatMapper extends BaseMapper<CustomerCsat> {
}
