package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.CandidateActivity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 候補者ステージ変更履歴マッパー
 */
@Mapper
public interface CandidateActivityMapper extends BaseMapper<CandidateActivity> {
}
