package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.SalesActivity;
import com.ses.dto.salesactivity.SalesActivityCreateRequest;
import com.ses.dto.salesactivity.SalesActivityUpdateRequest;
import com.ses.mapper.SalesActivityMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.OpportunityMapper;
import com.ses.entity.CustomerContact;
import com.ses.entity.Opportunity;
import com.ses.service.SalesActivityService;
import com.ses.service.security.DataScopeService;
import com.ses.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

@Service
public class SalesActivityServiceImpl extends ServiceImpl<SalesActivityMapper, SalesActivity> implements SalesActivityService {
    private final CustomerMapper customerMapper;
    private final CustomerContactMapper customerContactMapper;
    private final OpportunityMapper opportunityMapper;
    private final DataScopeService dataScopeService;

    public SalesActivityServiceImpl(CustomerMapper customerMapper,
                                    CustomerContactMapper customerContactMapper,
                                    OpportunityMapper opportunityMapper,
                                    DataScopeService dataScopeService) {
        this.customerMapper = customerMapper;
        this.customerContactMapper = customerContactMapper;
        this.opportunityMapper = opportunityMapper;
        this.dataScopeService = dataScopeService;
    }

    @Override
    public void assertCustomerExists(Long customerId) {
        dataScopeService.assertAllowedCustomer(customerId);
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
        dataScopeService.assertAllowedCustomer(customerId);
        assertCustomerExists(customerId);
        SalesActivity activity = new SalesActivity();
        activity.setCustomerId(customerId);
        applyRelations(activity, customerId, request.getContactId(), request.getOpportunityId());
        activity.setActivityType(request.getActivityType());
        applyRelations(activity, customerId, request.getContactId(), request.getOpportunityId());
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
        dataScopeService.assertAllowedCustomer(customerId);
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
        dataScopeService.assertAllowedCustomer(customerId);
        SalesActivity activity = getOwnedOrThrow(customerId, activityId);
        activity.setCompletedFlag(1);
        updateById(activity);
    }

    @Override
    @Transactional
    public void delete(Long customerId, Long activityId) {
        dataScopeService.assertAllowedCustomer(customerId);
        getOwnedOrThrow(customerId, activityId);
        removeById(activityId);
    }

    private void applyRelations(SalesActivity activity, Long customerId, Long contactId, Long opportunityId) {
        if (contactId != null) {
            CustomerContact contact = customerContactMapper.selectOne(new LambdaQueryWrapper<CustomerContact>()
                    .eq(CustomerContact::getId, contactId)
                    .eq(CustomerContact::getCustomerId, customerId));
            if (contact == null) throw BusinessException.of("error.crm.contactNotFound");
        }
        if (opportunityId != null) {
            Opportunity opportunity = opportunityMapper.selectOne(new LambdaQueryWrapper<Opportunity>()
                    .eq(Opportunity::getId, opportunityId)
                    .eq(Opportunity::getCustomerId, customerId));
            if (opportunity == null) throw BusinessException.of("error.crm.opportunityNotFound");
        }
        activity.setContactId(contactId);
        activity.setOpportunityId(opportunityId);
    }
}
