package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ProposalHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 提案履歴マッパー
 */
@Mapper
public interface ProposalHistoryMapper extends BaseMapper<ProposalHistory> {
}
