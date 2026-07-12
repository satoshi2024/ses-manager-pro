package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.SalesActivity;
import com.ses.mapper.SalesActivityMapper;
import com.ses.service.SalesActivityService;
import org.springframework.stereotype.Service;

@Service
public class SalesActivityServiceImpl extends ServiceImpl<SalesActivityMapper, SalesActivity> implements SalesActivityService {
}
