package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.crm.OpportunityConversionDto;
import com.ses.dto.crm.OpportunityListDto;
import com.ses.dto.crm.OpportunitySaveRequest;
import com.ses.entity.Customer;
import com.ses.mapper.CustomerMapper;
import com.ses.entity.Opportunity;
import com.ses.entity.Project;
import com.ses.entity.Quotation;
import com.ses.mapper.OpportunityMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.service.OpportunityService;
import com.ses.service.QuotationService;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.CrmScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.List;

/** 商機サービス実装。 */
@Service
@RequiredArgsConstructor
public class OpportunityServiceImpl extends ServiceImpl<OpportunityMapper, Opportunity>
        implements OpportunityService {

    private static final String STAGE_PROSPECT = "見込";
    private static final String STAGE_LOST = "失注";
    private static final String STAGE_WON = "受注";

    /** フロントの表示順と一致させる状態遷移の唯一の権威。 */
    private static final Map<String, Set<String>> ALLOWED_TRANSITIONS = Map.of(
            "見込", Set.of("要件確認"),
            "要件確認", Set.of("提案準備", "失注"),
            "提案準備", Set.of("見積提出", "失注"),
            "見積提出", Set.of("交渉", "失注"),
            "交渉", Set.of("受注", "失注"),
            "受注", Set.of(),
            "失注", Set.of());

    private final ProjectMapper projectMapper;
    private final QuotationMapper quotationMapper;
    private final QuotationService quotationService;
    private final DataScopeService dataScopeService;
    private final CustomerMapper customerMapper;
    @Autowired(required = false)
    private CrmScopeService crmScopeService;

    /**
     * 汎用CRUD経路から状態機械を迂回させない。stage変更はchangeStageだけが許可し、
     * 受注/失注後の商機は活動追記以外の更新対象にしない。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(Opportunity entity) {
        if (entity == null || entity.getId() == null) {
            throw BusinessException.of("error.opportunity.notFound");
        }
        Opportunity current = loadVisibleForUpdate(entity.getId());
        if (STAGE_WON.equals(current.getStage()) || STAGE_LOST.equals(current.getStage())) {
            throw BusinessException.of(400, "error.opportunity.terminalUpdate");
        }
        if (entity.getStage() != null && !Objects.equals(current.getStage(), entity.getStage())) {
            throw BusinessException.of(400, "error.opportunity.stageUpdateRequiresTransition");
        }
        assertExpectedVersion(current, entity.getVersion());
        if (!super.updateById(entity)) {
            throw BusinessException.of(409, "error.opportunity.versionConflict");
        }
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Opportunity changeStage(Long id, String newStage, String lostReason, Integer expectedVersion) {
        Opportunity current = loadVisibleForUpdate(id);
        assertExpectedVersion(current, expectedVersion);

        if (!ALLOWED_TRANSITIONS.getOrDefault(current.getStage(), Set.of()).contains(newStage)) {
            throw BusinessException.of("error.opportunity.statusTransitionInvalid", current.getStage(), newStage);
        }
        if (STAGE_LOST.equals(newStage) && !StringUtils.hasText(lostReason)) {
            throw BusinessException.of("error.opportunity.lostReasonRequired");
        }

        current.setStage(newStage);
        current.setStageChangedAt(java.time.LocalDateTime.now());
        if (STAGE_LOST.equals(newStage)) {
            current.setLostReason(lostReason.trim());
        }
        if (STAGE_WON.equals(newStage)) {
            // stage更新と変換を同一transactionに置く。変換失敗時は受注遷移もrollbackする。
            current.setLostReason(null);
        }

        if (!casUpdate(current)) {
            throw BusinessException.of(409, "error.opportunity.versionConflict");
        }
        if (STAGE_WON.equals(newStage)) {
            convertLocked(current);
        }
        return current;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public OpportunityConversionDto convertToProjectAndQuotation(Long id) {
        Opportunity current = loadVisibleForUpdate(id);
        if (!STAGE_WON.equals(current.getStage())) {
            throw BusinessException.of(409, "error.opportunity.conversionRequiresWon");
        }
        return convertLocked(current);
    }

    @Override
    public List<Opportunity> listForecastCandidates() {
        QueryWrapper<Opportunity> query = new QueryWrapper<Opportunity>()
                .isNull("converted_quotation_id")
                .notIn("stage", STAGE_WON, STAGE_LOST)
                .orderByAsc("expected_start_month")
                .orderByAsc("id");
        if (crmScopeService != null && !crmScopeService.hasFullAccess()) {
            Set<Long> allowedCustomerIds = crmScopeService.allowedCustomerIds(LocalDate.now());
            if (allowedCustomerIds == null || allowedCustomerIds.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            query.in("customer_id", allowedCustomerIds);
        } else if (crmScopeService == null && dataScopeService.isScoped()) {
            Set<Long> allowedCustomerIds = dataScopeService.allowedCustomerIds();
            if (allowedCustomerIds == null || allowedCustomerIds.isEmpty()) return java.util.Collections.emptyList();
            query.in("customer_id", allowedCustomerIds);
        }
        return list(query);
    }

    @Override
    public List<OpportunityListDto> listForScreen(String stage, Long ownerUserId) {
        QueryWrapper<Opportunity> query = new QueryWrapper<>();
        if (StringUtils.hasText(stage)) query.eq("stage", stage);
        if (ownerUserId != null) query.eq("owner_user_id", ownerUserId);
        if (crmScopeService != null && !crmScopeService.hasFullAccess()) {
            Set<Long> allowedCustomerIds = crmScopeService.allowedCustomerIds(LocalDate.now());
            if (allowedCustomerIds == null || allowedCustomerIds.isEmpty()) return java.util.Collections.emptyList();
            query.in("customer_id", allowedCustomerIds);
        } else if (crmScopeService == null && dataScopeService.isScoped()) {
            Set<Long> allowedCustomerIds = dataScopeService.allowedCustomerIds();
            if (allowedCustomerIds == null || allowedCustomerIds.isEmpty()) return java.util.Collections.emptyList();
            query.in("customer_id", allowedCustomerIds);
        }
        query.orderByAsc("stage").orderByDesc("id").last("LIMIT 200");
        List<Opportunity> rows = list(query);
        Set<Long> customerIds = rows.stream().map(Opportunity::getCustomerId)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        Map<Long, Customer> customers = customerIds.isEmpty() ? Map.of() : customerMapper.selectBatchIds(customerIds)
                .stream().collect(java.util.stream.Collectors.toMap(Customer::getId, c -> c, (a, b) -> a));
        return rows.stream().map(o -> {
            Customer customer = customers.get(o.getCustomerId());
            return OpportunityListDto.from(o, customer == null ? null : customer.getCompanyName());
        }).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Opportunity createBasic(OpportunitySaveRequest request) {
        assertCustomerScope(request.getCustomerId());
        validateProbability(request);
        Opportunity opportunity = new Opportunity();
        applyBasic(opportunity, request);
        opportunity.setStage(STAGE_PROSPECT);
        if (opportunity.getRequiredCount() == null) opportunity.setRequiredCount(1);
        if (opportunity.getProbability() == null) opportunity.setProbability(20);
        opportunity.setVersion(1);
        save(opportunity);
        return getById(opportunity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Opportunity updateBasic(Long id, OpportunitySaveRequest request) {
        Opportunity current = loadVisibleForUpdate(id);
        if (STAGE_WON.equals(current.getStage()) || STAGE_LOST.equals(current.getStage())) {
            throw BusinessException.of(400, "error.opportunity.terminalUpdate");
        }
        if (request.getVersion() == null || !Objects.equals(current.getVersion(), request.getVersion())) {
            throw BusinessException.of(409, "error.opportunity.versionConflict");
        }
        validateProbability(request);
        if (request.getCustomerId() != null && !Objects.equals(current.getCustomerId(), request.getCustomerId())) {
            assertCustomerScope(request.getCustomerId());
        }
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Opportunity> update =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<Opportunity>()
                .eq("id", id).eq("version", current.getVersion())
                .set("customer_id", request.getCustomerId())
                .set("title", request.getTitle())
                .set("expected_start_month", request.getExpectedStartMonth())
                .set("duration_months", request.getDurationMonths())
                .set("required_count", request.getRequiredCount())
                .set("unit_price", request.getUnitPrice())
                .set("expected_amount", request.getExpectedAmount())
                .set("probability", request.getProbability())
                .set("probability_override_reason", request.getProbabilityOverrideReason())
                .set("owner_user_id", request.getOwnerUserId())
                .set("next_action_date", request.getNextActionDate())
                .set("competitor", request.getCompetitor())
                .set("version", current.getVersion() + 1);
        if (baseMapper.update(null, update) != 1) {
            throw BusinessException.of(409, "error.opportunity.versionConflict");
        }
        return getById(id);
    }

    private void applyBasic(Opportunity target, OpportunitySaveRequest request) {
        target.setCustomerId(request.getCustomerId());
        target.setTitle(request.getTitle());
        target.setExpectedStartMonth(request.getExpectedStartMonth());
        target.setDurationMonths(request.getDurationMonths());
        target.setRequiredCount(request.getRequiredCount());
        target.setUnitPrice(request.getUnitPrice());
        target.setExpectedAmount(request.getExpectedAmount());
        target.setProbability(request.getProbability());
        target.setProbabilityOverrideReason(request.getProbabilityOverrideReason());
        target.setOwnerUserId(request.getOwnerUserId());
        target.setNextActionDate(request.getNextActionDate());
        target.setCompetitor(request.getCompetitor());
    }

    private Opportunity loadVisibleForUpdate(Long id) {
        Opportunity current = baseMapper.selectByIdForUpdate(id);
        if (current == null) {
            throw BusinessException.of(404, "error.opportunity.notFound");
        }
        assertCustomerScope(current.getCustomerId());
        return current;
    }

    private void assertExpectedVersion(Opportunity current, Integer expectedVersion) {
        if (expectedVersion == null || !expectedVersion.equals(current.getVersion())) {
            throw BusinessException.of(409, "error.opportunity.versionConflict");
        }
    }

    private void assertCustomerScope(Long customerId) {
        if (crmScopeService != null) crmScopeService.assertAllowedCustomer(customerId, LocalDate.now());
        else dataScopeService.assertAllowedCustomer(customerId);
    }

    private void validateProbability(OpportunitySaveRequest request) {
        if (request.getProbability() != null && (request.getProbability() < 0 || request.getProbability() > 100)) {
            throw BusinessException.of(400, "error.opportunity.probabilityInvalid");
        }
        if (request.getProbability() != null && request.getProbability() != 20
                && !StringUtils.hasText(request.getProbabilityOverrideReason())) {
            throw BusinessException.of(400, "error.opportunity.probabilityOverrideReasonRequired");
        }
    }

    private OpportunityConversionDto convertLocked(Opportunity opportunity) {
        Long opportunityId = opportunity.getId();
        Project project = projectMapper.selectBySourceOpportunityIdIncludingDeleted(opportunityId);
        if (project != null && Integer.valueOf(1).equals(project.getDeletedFlag())) {
            throw BusinessException.of(409, "error.opportunity.conversion.sourceDeleted");
        }
        if (project == null) {
            project = createProject(opportunity);
        }

        Quotation quotation = quotationMapper.selectBySourceOpportunityIdIncludingDeleted(opportunityId);
        if (quotation != null && Integer.valueOf(1).equals(quotation.getDeletedFlag())) {
            throw BusinessException.of(409, "error.opportunity.conversion.sourceDeleted");
        }
        if (quotation == null) {
            quotation = createQuotation(opportunity, project.getId());
        }

        if (!project.getId().equals(opportunity.getConvertedProjectId())
                || !quotation.getId().equals(opportunity.getConvertedQuotationId())) {
            Opportunity update = new Opportunity();
            update.setId(opportunityId);
            update.setConvertedProjectId(project.getId());
            update.setConvertedQuotationId(quotation.getId());
            update.setVersion(opportunity.getVersion());
            if (!casUpdate(update)) {
                throw BusinessException.of(409, "error.opportunity.versionConflict");
            }
            opportunity.setConvertedProjectId(project.getId());
            opportunity.setConvertedQuotationId(quotation.getId());
            opportunity.setVersion(update.getVersion());
        }
        return new OpportunityConversionDto(opportunityId, project.getId(), quotation.getId());
    }

    /** 内部の状態遷移・変換だけが利用するversion CAS更新。 */
    private boolean casUpdate(Opportunity entity) {
        return super.updateById(entity);
    }

    private Project createProject(Opportunity opportunity) {
        Project project = new Project();
        project.setProjectName(opportunity.getTitle());
        project.setCustomerId(opportunity.getCustomerId());
        project.setRequiredCount(opportunity.getRequiredCount());
        project.setUnitPriceMin(opportunity.getUnitPrice());
        project.setUnitPriceMax(opportunity.getUnitPrice());
        project.setStartDate(parseStartDate(opportunity.getExpectedStartMonth()));
        if (project.getStartDate() != null && opportunity.getDurationMonths() != null
                && opportunity.getDurationMonths() > 0) {
            project.setEndDate(project.getStartDate().plusMonths(opportunity.getDurationMonths()).minusDays(1));
        }
        // t_project.status の既存DDL enumに合わせる。商機の「受注」は案件の初期statusを意味しない。
        project.setStatus("募集中");
        project.setSourceOpportunityId(opportunity.getId());
        try {
            projectMapper.insert(project);
        } catch (DuplicateKeyException e) {
            // source_opportunity_id UNIQUEは行ロックと併用する二重防御。既存行を再取得して冪等化する。
            Project existing = projectMapper.selectBySourceOpportunityIdIncludingDeleted(opportunity.getId());
            if (existing == null) {
                throw e;
            }
            return existing;
        }
        return project;
    }

    private Quotation createQuotation(Opportunity opportunity, Long projectId) {
        if (opportunity.getUnitPrice() == null) {
            throw BusinessException.of("error.opportunity.conversion.unitPriceRequired");
        }
        Quotation quotation = new Quotation();
        quotation.setQuotationNo(quotationService.generateQuotationNo(LocalDate.now()));
        quotation.setCustomerId(opportunity.getCustomerId());
        quotation.setProjectId(projectId);
        quotation.setTitle(opportunity.getTitle());
        quotation.setUnitPrice(opportunity.getUnitPrice());
        quotation.setStatus("下書き");
        quotation.setSourceOpportunityId(opportunity.getId());
        try {
            quotationMapper.insert(quotation);
        } catch (DuplicateKeyException e) {
            Quotation existing = quotationMapper.selectBySourceOpportunityIdIncludingDeleted(opportunity.getId());
            if (existing == null) {
                throw e;
            }
            return existing;
        }
        return quotation;
    }

    private LocalDate parseStartDate(String expectedStartMonth) {
        if (!StringUtils.hasText(expectedStartMonth)) {
            return null;
        }
        try {
            return YearMonth.parse(expectedStartMonth).atDay(1);
        } catch (DateTimeParseException e) {
            throw BusinessException.of("error.opportunity.expectedStartMonthInvalid");
        }
    }
}
