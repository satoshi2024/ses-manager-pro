package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ReportSectionAttempt;
import org.apache.ibatis.annotations.Mapper;

/** 管理レポートsection attempt監査Mapper。attemptは追記型である。 */
@Mapper
public interface ReportSectionAttemptMapper extends BaseMapper<ReportSectionAttempt> {
}
