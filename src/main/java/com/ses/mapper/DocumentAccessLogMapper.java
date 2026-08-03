package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.DocumentAccessLog;
import org.apache.ibatis.annotations.Mapper;

/** 文書アクセス監査ログMapper（append-only）。 */
@Mapper
public interface DocumentAccessLogMapper extends BaseMapper<DocumentAccessLog> {}
