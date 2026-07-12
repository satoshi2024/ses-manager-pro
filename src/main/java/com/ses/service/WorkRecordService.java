package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.dto.WorkRecordGridDto;
import com.ses.entity.WorkRecord;

import java.math.BigDecimal;
import java.util.List;

public interface WorkRecordService extends IService<WorkRecord> {
    List<WorkRecordGridDto> monthlyGrid(String workMonth);
    WorkRecord saveHours(Long contractId, String workMonth, BigDecimal actualHours, String remarks);
    void confirmMonth(String workMonth);
    void reopenMonth(String workMonth);
}
