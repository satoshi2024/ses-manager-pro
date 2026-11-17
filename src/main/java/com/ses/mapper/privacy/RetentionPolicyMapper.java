package com.ses.mapper.privacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.privacy.RetentionPolicy;
import org.apache.ibatis.annotations.Mapper;

/** NF07 retention policy（m_retention_policy）。書き込みは NF07-REG の PrivacyPolicyRegistrationService だけが行う。 */
@Mapper
public interface RetentionPolicyMapper extends BaseMapper<RetentionPolicy> {
}
