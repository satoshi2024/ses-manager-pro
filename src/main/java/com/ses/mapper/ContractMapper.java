package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Contract;
import org.apache.ibatis.annotations.Mapper;

/**
 * 契約マッパー
 */
@Mapper
public interface ContractMapper extends BaseMapper<Contract> {
}
