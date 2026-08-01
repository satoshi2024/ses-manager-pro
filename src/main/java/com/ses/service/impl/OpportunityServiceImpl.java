package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.crm.OpportunityConversionDto;
import com.ses.entity.Opportunity;
import com.ses.entity.Project;
import com.ses.entity.Quotation;
import com.ses.mapper.OpportunityMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.service.OpportunityService;
import com.ses.service.QuotationService;
import com.ses.service.security.DataScopeService;
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
        if (dataScopeService.isScoped()) {
            Set<Long> allowedCustomerIds = dataScopeService.allowedCustomerIds();
            if (allowedCustomerIds == null || allowedCustomerIds.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            query.in("customer_id", allowedCustomerIds);
        }
        return list(query);
    }

    private Opportunity loadVisibleForUpdate(Long id) {
        Opportunity current = baseMapper.selectByIdForUpdate(id);
        if (current == null) {
            throw BusinessException.of(404, "error.opportunity.notFound");
        }
        dataScopeService.assertAllowedCustomer(current.getCustomerId());
        return current;
    }

    private void assertExpectedVersion(Opportunity current, Integer expectedVersion) {
        if (expectedVersion != null && !expectedVersion.equals(current.getVersion())) {
            throw BusinessException.of(409, "error.opportunity.versionConflict");
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
