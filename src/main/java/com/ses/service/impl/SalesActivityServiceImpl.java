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
import com.ses.service.security.CrmScopeService;
import com.ses.common.exception.BusinessException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

@Service
public class SalesActivityServiceImpl extends ServiceImpl<SalesActivityMapper, SalesActivity> implements SalesActivityService {
    private final CustomerMapper customerMapper;
    private final CustomerContactMapper customerContactMapper;
    private final OpportunityMapper opportunityMapper;
    private final DataScopeService dataScopeService;
    @Autowired(required = false)
    private CrmScopeService crmScopeService;

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
        assertCustomerScope(customerId);
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
        assertCustomerScope(customerId);
        assertCustomerExists(customerId);
        SalesActivity activity = new SalesActivity();
        activity.setCustomerId(customerId);
        applyRelations(activity, customerId, request.getContactId(), request.getOpportunityId());
        activity.setActivityType(request.getActivityType());
        activity.setActivityDate(request.getActivityDate());
        activity.setTitle(request.getTitle());
        activity.setContent(request.getContent());
        activity.setNextActionDate(request.getNextActionDate());
        activity.setAssigneeUserId(request.getAssigneeUserId());
        activity.setCompletedFlag(0);
        save(activity);
        return activity;
    }

    @Override
    @Transactional
    public SalesActivity update(Long customerId, Long activityId, SalesActivityUpdateRequest request) {
        assertCustomerScope(customerId);
        SalesActivity activity = getOwnedOrThrow(customerId, activityId);
        if (request.getVersion() == null || !java.util.Objects.equals(request.getVersion(), activity.getVersion())) {
            throw BusinessException.of(409, "error.salesActivity.concurrentModified");
        }
        activity.setActivityType(request.getActivityType());
        activity.setActivityDate(request.getActivityDate());
        activity.setTitle(request.getTitle());
        activity.setContent(request.getContent());
        activity.setNextActionDate(request.getNextActionDate());
        applyRelations(activity, customerId, request.getContactId(), request.getOpportunityId());
        activity.setAssigneeUserId(request.getAssigneeUserId());
        if (request.getCompletedFlag() != null) {
            if (request.getCompletedFlag() != 0 && request.getCompletedFlag() != 1) {
                throw BusinessException.of(400, "error.salesActivity.completedFlagInvalid");
            }
            activity.setCompletedFlag(request.getCompletedFlag());
        }
        UpdateWrapper<SalesActivity> update = new UpdateWrapper<SalesActivity>()
                .eq("id", activityId).eq("customer_id", customerId)
                .eq("version", activity.getVersion())
                .set("contact_id", activity.getContactId())
                .set("opportunity_id", activity.getOpportunityId())
                .set("activity_type", activity.getActivityType())
                .set("activity_date", activity.getActivityDate())
                .set("title", activity.getTitle())
                .set("content", activity.getContent())
                .set("next_action_date", activity.getNextActionDate())
                .set("assignee_user_id", activity.getAssigneeUserId());
        if (request.getCompletedFlag() != null) update.set("completed_flag", activity.getCompletedFlag());
        update.set("version", activity.getVersion() + 1);
        if (!update(update)) throw BusinessException.of(409, "error.salesActivity.concurrentModified");
        return getById(activityId);
    }

    @Override
    @Transactional
    public void complete(Long customerId, Long activityId, Integer version) {
        assertCustomerScope(customerId);
        SalesActivity activity = getOwnedOrThrow(customerId, activityId);
        if (version == null || !java.util.Objects.equals(version, activity.getVersion())) {
            throw BusinessException.of(409, "error.salesActivity.concurrentModified");
        }
        UpdateWrapper<SalesActivity> update = new UpdateWrapper<SalesActivity>()
                .eq("id", activityId).eq("customer_id", customerId).eq("version", version)
                .set("completed_flag", 1).set("version", version + 1);
        if (!update(update)) throw BusinessException.of(409, "error.salesActivity.concurrentModified");
    }

    @Override
    @Transactional
    public void delete(Long customerId, Long activityId, Integer version) {
        assertCustomerScope(customerId);
        SalesActivity activity = getOwnedOrThrow(customerId, activityId);
        if (version == null || !java.util.Objects.equals(version, activity.getVersion())) {
            throw BusinessException.of(409, "error.salesActivity.concurrentModified");
        }
        UpdateWrapper<SalesActivity> update = new UpdateWrapper<SalesActivity>()
                .eq("id", activityId).eq("customer_id", customerId).eq("version", version)
                .set("deleted_flag", 1).set("version", version + 1);
        if (!update(update)) throw BusinessException.of(409, "error.salesActivity.concurrentModified");
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

    private void assertCustomerScope(Long customerId) {
        if (crmScopeService != null && crmScopeService.canUseCrm()) {
            crmScopeService.assertAllowedCustomer(customerId, java.time.LocalDate.now());
        } else {
            dataScopeService.assertAllowedCustomer(customerId);
        }
    }
}
