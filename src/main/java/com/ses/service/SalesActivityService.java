package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.SalesActivity;
import com.ses.dto.salesactivity.SalesActivityCreateRequest;
import com.ses.dto.salesactivity.SalesActivityUpdateRequest;

public interface SalesActivityService extends IService<SalesActivity> {
    void assertCustomerExists(Long customerId);
    SalesActivity getOwnedOrThrow(Long customerId, Long activityId);
    SalesActivity create(Long customerId, SalesActivityCreateRequest request);
    SalesActivity update(Long customerId, Long activityId, SalesActivityUpdateRequest request);
    void complete(Long customerId, Long activityId);
    void delete(Long customerId, Long activityId);
}
