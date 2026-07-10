package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Proposal;
import org.apache.ibatis.annotations.Mapper;

/**
 * 提案マッパー
 */
@Mapper
public interface ProposalMapper extends BaseMapper<Proposal> {
}
