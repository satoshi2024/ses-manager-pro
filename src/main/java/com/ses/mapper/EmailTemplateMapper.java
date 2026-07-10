package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.EmailTemplate;
import org.apache.ibatis.annotations.Mapper;

/**
 * メールテンプレートマッパー
 */
@Mapper
public interface EmailTemplateMapper extends BaseMapper<EmailTemplate> {
}
