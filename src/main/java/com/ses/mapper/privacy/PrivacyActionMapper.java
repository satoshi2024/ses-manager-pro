package com.ses.mapper.privacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.privacy.PrivacyAction;
import org.apache.ibatis.annotations.Mapper;

/** DSAR の検索・草案・手動対応の記録（t_privacy_action）。A2a は MANUAL_ACTION の追記だけを行う。 */
@Mapper
public interface PrivacyActionMapper extends BaseMapper<PrivacyAction> {
}
