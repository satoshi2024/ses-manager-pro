package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.BpAvailability;
import com.ses.entity.Engineer;

public interface BpAvailabilityService extends IService<BpAvailability> {
    Engineer promoteToEngineer(Long id);
}
