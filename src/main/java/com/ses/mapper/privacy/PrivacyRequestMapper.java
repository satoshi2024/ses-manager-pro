package com.ses.mapper.privacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.privacy.PrivacyRequest;
import org.apache.ibatis.annotations.Mapper;

/**
 * NF07 DSAR case（t_privacy_request）。状態遷移は {@code PrivacyRequestService} が row_version と現状態を条件にした
 * UpdateWrapper（CAS）で行い、{@code updateById} は使わない。
 */
@Mapper
public interface PrivacyRequestMapper extends BaseMapper<PrivacyRequest> {
}
