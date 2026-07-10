package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.Engineer;
import com.ses.mapper.EngineerMapper;
import com.ses.service.EngineerService;
import org.springframework.stereotype.Service;

/**
 * エンジニアサービス実装クラス
 */
@Service
public class EngineerServiceImpl extends ServiceImpl<EngineerMapper, Engineer> implements EngineerService {
}
