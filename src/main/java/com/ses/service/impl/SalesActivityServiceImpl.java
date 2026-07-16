package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.SalesActivity;
import com.ses.dto.salesactivity.SalesActivityCreateRequest;
import com.ses.dto.salesactivity.SalesActivityUpdateRequest;
import com.ses.mapper.SalesActivityMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.service.SalesActivityService;
import com.ses.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

@Service
public class SalesActivityServiceImpl extends ServiceImpl<SalesActivityMapper, SalesActivity> implements SalesActivityService {
    private final CustomerMapper customerMapper;

    public SalesActivityServiceImpl(CustomerMapper customerMapper) {
        this.customerMapper = customerMapper;
    }

    @Override
    public void assertCustomerExists(Long customerId) {
        if (customerMapper.selectById(customerId) == null) {
            throw BusinessException.of(404, "error.customer.notFound");
        }
    }

    @Override
    public SalesActivity getOwnedOrThrow(Long customerId, Long activityId) {
        assertCustomerExists(customerId);
        SalesActivity activity = getOne(new LambdaQueryWrapper<SalesActivity>()
                .eq(SalesActivity::getId, activityId)
                .eq(SalesActivity::getCustomerId, customerId));
        if (activity == null) {
            throw BusinessException.of(404, "error.salesActivity.notFound");
        }
        return activity;
    }

    @Override
    @Transactional
    public SalesActivity create(Long customerId, SalesActivityCreateRequest request) {
        assertCustomerExists(customerId);
        SalesActivity activity = new SalesActivity();
        activity.setCustomerId(customerId);
        activity.setActivityType(request.getActivityType());
        activity.setActivityDate(request.getActivityDate());
        activity.setTitle(request.getTitle());
        activity.setContent(request.getContent());
        activity.setNextActionDate(request.getNextActionDate());
        activity.setCompletedFlag(0);
        save(activity);
        return activity;
    }

    @Override
    @Transactional
    public SalesActivity update(Long customerId, Long activityId, SalesActivityUpdateRequest request) {
        SalesActivity activity = getOwnedOrThrow(customerId, activityId);
        activity.setActivityType(request.getActivityType());
        activity.setActivityDate(request.getActivityDate());
        activity.setTitle(request.getTitle());
        activity.setContent(request.getContent());
        activity.setNextActionDate(request.getNextActionDate());
        if (request.getCompletedFlag() != null) {
            if (request.getCompletedFlag() != 0 && request.getCompletedFlag() != 1) {
                throw BusinessException.of(400, "error.salesActivity.completedFlagInvalid");
            }
            activity.setCompletedFlag(request.getCompletedFlag());
        }
        updateById(activity);
        return activity;
    }

    @Override
    @Transactional
    public void complete(Long customerId, Long activityId) {
        SalesActivity activity = getOwnedOrThrow(customerId, activityId);
        activity.setCompletedFlag(1);
        updateById(activity);
    }

    @Override
    @Transactional
    public void delete(Long customerId, Long activityId) {
        getOwnedOrThrow(customerId, activityId);
        removeById(activityId);
    }
}
