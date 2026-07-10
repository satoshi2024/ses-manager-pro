package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.SkillTag;
import com.ses.mapper.SkillTagMapper;
import com.ses.service.SkillTagService;
import org.springframework.stereotype.Service;

/**
 * スキルタグサービス実装クラス
 */
@Service
public class SkillTagServiceImpl extends ServiceImpl<SkillTagMapper, SkillTag> implements SkillTagService {
}
