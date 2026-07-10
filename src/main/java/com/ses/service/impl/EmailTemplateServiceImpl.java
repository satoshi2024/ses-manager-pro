package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.EmailTemplate;
import com.ses.mapper.EmailTemplateMapper;
import com.ses.service.EmailTemplateService;
import org.springframework.stereotype.Service;

/**
 * メールテンプレートサービス実装
 */
@Service
public class EmailTemplateServiceImpl extends ServiceImpl<EmailTemplateMapper, EmailTemplate> implements EmailTemplateService {
}
