package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.DigitalInvoiceEvent;
import com.ses.mapper.DigitalInvoiceEventMapper;
import com.ses.service.DigitalInvoiceEventService;
import org.springframework.stereotype.Service;

@Service
public class DigitalInvoiceEventServiceImpl extends ServiceImpl<DigitalInvoiceEventMapper, DigitalInvoiceEvent> implements DigitalInvoiceEventService {
}
