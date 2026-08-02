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
import com.ses.service.security.CrmScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.text.Normalizer;

/** リードサービス実装。未割当leadは営業全員へ公開する。 */
@Service
@RequiredArgsConstructor
public class LeadServiceImpl implements LeadService {
    private static final String STATUS_NEW = "未対応";
    private static final String STATUS_IN_PROGRESS = "対応中";
    private static final String STATUS_CONVERTED = "転換済";
    private static final String STATUS_DISCARDED = "破棄";
    private static final java.util.Map<String, java.util.Set<String>> STATUS_TRANSITIONS = java.util.Map.of(
            STATUS_NEW, java.util.Set.of(STATUS_IN_PROGRESS, STATUS_DISCARDED),
            STATUS_IN_PROGRESS, java.util.Set.of(STATUS_CONVERTED, STATUS_DISCARDED),
            STATUS_CONVERTED, java.util.Set.of(), STATUS_DISCARDED, java.util.Set.of());

    private final LeadMapper leadMapper;
    private final CustomerMapper customerMapper;
    private final CustomerContactMapper customerContactMapper;
    private final OpportunityMapper opportunityMapper;
    private final DataScopeService dataScopeService;
    @Autowired(required = false)
    private CrmScopeService crmScopeService;

    @Override
    public List<Lead> list(String status, String companyName) {
        QueryWrapper<Lead> query = new QueryWrapper<>();
        if (StringUtils.hasText(status)) query.eq("status", status);
        if (StringUtils.hasText(companyName)) query.like("company_name", companyName.trim());
        applyCrmScope(query);
        return leadMapper.selectList(query.orderByDesc("id").last("LIMIT 200"));
    }

    @Override
    public com.baomidou.mybatisplus.extension.plugins.pagination.Page<Lead> page(String status, String companyName, long current, long size) {
        QueryWrapper<Lead> query = new QueryWrapper<>();
        if (StringUtils.hasText(status)) query.eq("status", status);
        if (StringUtils.hasText(companyName)) query.like("company_name", companyName.trim());
        applyCrmScope(query);
        return leadMapper.selectPage(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(current <= 0 ? 1 : current, Math.min(Math.max(size, 1), 1000)), query.orderByDesc("id"));
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
        assertKnownStatus(lead.getStatus());
        if (STATUS_CONVERTED.equals(lead.getStatus()) || STATUS_DISCARDED.equals(lead.getStatus())) {
            throw BusinessException.of(400, "error.crm.leadInitialStatusInvalid");
        }
        assertOwnerScope(lead.getOwnerUserId());
        applySearchKeys(lead);
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
        if (request.getStatus() != null && !java.util.Objects.equals(current.getStatus(), request.getStatus())) {
            assertStatusTransition(current.getStatus(), request.getStatus());
        }
        apply(current, request);
        applySearchKeys(current);
        assertOwnerScope(current.getOwnerUserId());
        UpdateWrapper<Lead> update = new UpdateWrapper<Lead>()
                .eq("id", id).eq("version", current.getVersion())
                .set("company_name", current.getCompanyName())
                .set("company_name_normalized", current.getCompanyNameNormalized())
                .set("contact_name", current.getContactName())
                .set("contact_email", current.getContactEmail())
                .set("contact_email_normalized", current.getContactEmailNormalized())
                .set("contact_phone", current.getContactPhone())
                .set("contact_phone_normalized", current.getContactPhoneNormalized())
                .set("source", current.getSource())
                .set("source_cost", current.getSourceCost())
                .set("owner_user_id", current.getOwnerUserId())
                .set("status", current.getStatus())
                .set("version", current.getVersion() + 1);
        if (leadMapper.update(null, update) != 1) throw BusinessException.of(409, "error.crm.leadVersionConflict");
        return leadMapper.selectById(id);
    }

    @Override
    public List<Lead> duplicateCandidates(String companyName, String contactEmail, String contactPhone, Long excludeId) {
        QueryWrapper<Lead> query = new QueryWrapper<>();
        String companyKey = normalizeSearchKey(companyName, true);
        String emailKey = normalizeSearchKey(contactEmail, false);
        String phoneKey = normalizeSearchKey(contactPhone, false);
        if (companyKey == null && emailKey == null && phoneKey == null) return List.of();
        query.and(w -> {
            boolean hasPrevious = false;
            if (companyKey != null) {
                w.eq("company_name_normalized", companyKey);
                hasPrevious = true;
            }
            if (emailKey != null) {
                if (hasPrevious) w.or();
                w.eq("contact_email_normalized", emailKey);
                hasPrevious = true;
            }
            if (phoneKey != null) {
                if (hasPrevious) w.or();
                w.eq("contact_phone_normalized", phoneKey);
            }
        });
        if (excludeId != null) query.ne("id", excludeId);
        applyCrmScope(query);
        return leadMapper.selectList(query.orderByDesc("id").last("LIMIT 20"));
    }

    private String normalizeSearchKey(String value, boolean company) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(java.util.Locale.ROOT);
        return company
                ? normalized.replaceAll("\\s+", "")
                : normalized.replaceAll("[^a-z0-9+@.]", "");
    }

    private void applySearchKeys(Lead lead) {
        lead.setCompanyNameNormalized(normalizeSearchKey(lead.getCompanyName(), true));
        lead.setContactEmailNormalized(normalizeSearchKey(lead.getContactEmail(), false));
        lead.setContactPhoneNormalized(normalizeSearchKey(lead.getContactPhone(), false));
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

    private void assertOwnerScope(Long ownerUserId) {
        if (crmScopeService != null && !crmScopeService.isOwnerAllowed(ownerUserId, LocalDate.now())) {
            throw BusinessException.of(404, "error.crm.ownerNotFound");
        }
    }

    private void apply(Lead lead, LeadSaveRequest request) {
        lead.setCompanyName(request.getCompanyName());
        lead.setContactName(request.getContactName());
        lead.setContactEmail(request.getContactEmail());
        lead.setContactPhone(request.getContactPhone());
        lead.setSource(request.getSource());
        lead.setSourceCost(request.getSourceCost());
        lead.setOwnerUserId(request.getOwnerUserId());
        if (request.getStatus() != null) lead.setStatus(request.getStatus());
    }

    private void assertVersion(Lead current, Integer expected) {
        if (expected == null || !expected.equals(current.getVersion())) {
            throw BusinessException.of(409, "error.crm.leadVersionConflict");
        }
    }

    private void assertKnownStatus(String status) {
        if (!STATUS_TRANSITIONS.containsKey(status)) {
            throw BusinessException.of(400, "error.crm.leadStatusInvalid");
        }
    }

    private void assertStatusTransition(String current, String next) {
        assertKnownStatus(next);
        if (!STATUS_TRANSITIONS.getOrDefault(current, java.util.Set.of()).contains(next)) {
            throw BusinessException.of(400, "error.crm.leadStatusTransitionInvalid", current, next);
        }
    }

    private boolean isVisible(Lead lead) {
        if (crmScopeService != null) {
            return crmScopeService.isLeadVisible(lead.getOwnerUserId(), lead.getConvertedCustomerId(), LocalDate.now());
        }
        if (!dataScopeService.isSalesDataScoped()) return true;
        Long userId = SecurityUtils.currentUserId();
        return lead.getOwnerUserId() == null || (userId != null && userId.equals(lead.getOwnerUserId()));
    }

    private void applyCrmScope(QueryWrapper<Lead> query) {
        if (crmScopeService != null) {
            crmScopeService.applyLeadScope(query, LocalDate.now());
            return;
        }
        if (!dataScopeService.isSalesDataScoped()) return;
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) query.isNull("owner_user_id");
        else query.and(w -> w.eq("owner_user_id", userId).or().isNull("owner_user_id"));
    }
}
