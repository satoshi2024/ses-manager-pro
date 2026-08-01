package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.crm.LeadConversionDto;
import com.ses.dto.crm.LeadSaveRequest;
import com.ses.entity.Customer;
import com.ses.entity.CustomerContact;
import com.ses.entity.Lead;
import com.ses.entity.Opportunity;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.LeadMapper;
import com.ses.mapper.OpportunityMapper;
import com.ses.service.LeadService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;

/** リードサービス実装。未割当leadは営業全員へ公開する。 */
@Service
@RequiredArgsConstructor
public class LeadServiceImpl implements LeadService {
    private static final String STATUS_NEW = "未対応";
    private static final String STATUS_IN_PROGRESS = "対応中";
    private static final String STATUS_CONVERTED = "転換済";
    private static final String STATUS_DISCARDED = "破棄";

    private final LeadMapper leadMapper;
    private final CustomerMapper customerMapper;
    private final CustomerContactMapper customerContactMapper;
    private final OpportunityMapper opportunityMapper;
    private final DataScopeService dataScopeService;

    @Override
    public List<Lead> list(String status, String companyName) {
        QueryWrapper<Lead> query = new QueryWrapper<>();
        if (StringUtils.hasText(status)) query.eq("status", status);
        if (StringUtils.hasText(companyName)) query.like("company_name", companyName.trim());
        applyOwnerScope(query);
        return leadMapper.selectList(query.orderByDesc("id"));
    }

    @Override
    public Lead getVisible(Long id) {
        Lead lead = leadMapper.selectById(id);
        if (lead == null || !isVisible(lead)) throw BusinessException.of(404, "error.crm.leadNotFound");
        return lead;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Lead create(LeadSaveRequest request) {
        Lead lead = new Lead();
        apply(lead, request);
        if (!StringUtils.hasText(lead.getStatus())) lead.setStatus(STATUS_NEW);
        if (leadMapper.insert(lead) != 1) throw BusinessException.of("error.crm.leadSaveFailed");
        return leadMapper.selectById(lead.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Lead update(Long id, LeadSaveRequest request) {
        Lead current = leadMapper.selectByIdForUpdate(id);
        if (current == null || !isVisible(current)) throw BusinessException.of(404, "error.crm.leadNotFound");
        if (STATUS_CONVERTED.equals(current.getStatus()) || STATUS_DISCARDED.equals(current.getStatus())) {
            throw BusinessException.of(400, "error.crm.leadTerminalUpdate");
        }
        assertVersion(current, request.getVersion());
        apply(current, request);
        if (!StringUtils.hasText(current.getStatus())) current.setStatus(STATUS_IN_PROGRESS);
        UpdateWrapper<Lead> update = new UpdateWrapper<Lead>()
                .eq("id", id).eq("version", current.getVersion())
                .set("company_name", current.getCompanyName())
                .set("contact_name", current.getContactName())
                .set("contact_email", current.getContactEmail())
                .set("contact_phone", current.getContactPhone())
                .set("source", current.getSource())
                .set("owner_user_id", current.getOwnerUserId())
                .set("status", current.getStatus())
                .set("version", current.getVersion() + 1);
        if (leadMapper.update(null, update) != 1) throw BusinessException.of(409, "error.crm.leadVersionConflict");
        return leadMapper.selectById(id);
    }

    @Override
    public List<Lead> duplicateCandidates(String companyName, String contactEmail, String contactPhone, Long excludeId) {
        QueryWrapper<Lead> query = new QueryWrapper<>();
        boolean hasCompany = StringUtils.hasText(companyName);
        boolean hasEmail = StringUtils.hasText(contactEmail);
        boolean hasPhone = StringUtils.hasText(contactPhone);
        if (hasCompany || hasEmail || hasPhone) {
            query.and(w -> {
                if (hasCompany) w.eq("company_name", companyName.trim());
                if (hasEmail) w.or().eq("contact_email", contactEmail.trim());
                if (hasPhone) w.or().eq("contact_phone", contactPhone.trim());
            });
        } else {
            query.eq("id", -1L);
        }
        if (excludeId != null) query.ne("id", excludeId);
        applyOwnerScope(query);
        query.last("LIMIT 20");
        return leadMapper.selectList(query.orderByDesc("id"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public LeadConversionDto convert(Long id, Integer expectedVersion) {
        Lead lead = leadMapper.selectByIdForUpdate(id);
        if (lead == null || !isVisible(lead)) throw BusinessException.of(404, "error.crm.leadNotFound");
        if (STATUS_CONVERTED.equals(lead.getStatus())
                && lead.getConvertedCustomerId() != null && lead.getConvertedOpportunityId() != null) {
            return new LeadConversionDto(id, lead.getConvertedCustomerId(), lead.getConvertedOpportunityId());
        }
        if (STATUS_DISCARDED.equals(lead.getStatus())) throw BusinessException.of(400, "error.crm.leadDiscarded");
        assertVersion(lead, expectedVersion);

        Customer customer = new Customer();
        customer.setCompanyName(lead.getCompanyName());
        customerMapper.insert(customer);

        if (StringUtils.hasText(lead.getContactName()) || StringUtils.hasText(lead.getContactEmail())
                || StringUtils.hasText(lead.getContactPhone())) {
            CustomerContact contact = new CustomerContact();
            contact.setCustomerId(customer.getId());
            contact.setName(StringUtils.hasText(lead.getContactName()) ? lead.getContactName() : lead.getCompanyName());
            contact.setEmail(lead.getContactEmail());
            contact.setPhone(lead.getContactPhone());
            contact.setPrimaryFlag(1);
            contact.setValidFrom(LocalDate.now());
            contact.setStatus("有効");
            contact.setVersion(1);
            customerContactMapper.insert(contact);
        }

        Opportunity opportunity = new Opportunity();
        opportunity.setCustomerId(customer.getId());
        opportunity.setTitle(lead.getCompanyName() + " 商談");
        opportunity.setStage("見込");
        opportunity.setRequiredCount(1);
        opportunity.setProbability(20);
        opportunity.setOwnerUserId(lead.getOwnerUserId());
        opportunity.setVersion(1);
        opportunityMapper.insert(opportunity);

        UpdateWrapper<Lead> update = new UpdateWrapper<Lead>()
                .eq("id", id).eq("version", lead.getVersion())
                .set("status", STATUS_CONVERTED)
                .set("converted_customer_id", customer.getId())
                .set("converted_opportunity_id", opportunity.getId())
                .set("version", lead.getVersion() + 1);
        if (leadMapper.update(null, update) != 1) throw BusinessException.of(409, "error.crm.leadVersionConflict");
        return new LeadConversionDto(id, customer.getId(), opportunity.getId());
    }

    private void apply(Lead lead, LeadSaveRequest request) {
        lead.setCompanyName(request.getCompanyName());
        lead.setContactName(request.getContactName());
        lead.setContactEmail(request.getContactEmail());
        lead.setContactPhone(request.getContactPhone());
        lead.setSource(request.getSource());
        lead.setOwnerUserId(request.getOwnerUserId());
        if (request.getStatus() != null) lead.setStatus(request.getStatus());
    }

    private void assertVersion(Lead current, Integer expected) {
        if (expected != null && !expected.equals(current.getVersion())) {
            throw BusinessException.of(409, "error.crm.leadVersionConflict");
        }
    }

    private boolean isVisible(Lead lead) {
        if (!dataScopeService.isSalesDataScoped()) return true;
        Long userId = SecurityUtils.currentUserId();
        return lead.getOwnerUserId() == null || (userId != null && userId.equals(lead.getOwnerUserId()));
    }

    private void applyOwnerScope(QueryWrapper<Lead> query) {
        if (!dataScopeService.isSalesDataScoped()) return;
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) query.isNull("owner_user_id");
        else query.and(w -> w.eq("owner_user_id", userId).or().isNull("owner_user_id"));
    }
}
