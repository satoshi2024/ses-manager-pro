package com.ses.mapper.privacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.privacy.RetentionPolicy;
import org.apache.ibatis.annotations.Mapper;

/** NF07 retention policy（m_retention_policy）。第1期の application は読み取りだけに使う。 */
@Mapper
public interface RetentionPolicyMapper extends BaseMapper<RetentionPolicy> {
}
