package com.ses.mapper.privacy;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.privacy.PiiDataElement;
import org.apache.ibatis.annotations.Mapper;

/** NF07 PII data element catalog（m_pii_data_element）。第1期の application は読み取りだけに使う。 */
@Mapper
public interface PiiDataElementMapper extends BaseMapper<PiiDataElement> {
}
