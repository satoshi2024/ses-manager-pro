package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.EntityProtectUtil;
import com.ses.dto.organization.BudgetSaveRequest;
import com.ses.entity.ManagementBudget;
import com.ses.entity.CostCenter;
import com.ses.entity.OrganizationUnit;
import com.ses.mapper.ManagementBudgetMapper;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.service.ManagementBudgetService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

/** 予算の一意性・金額・楽観ロックを扱うサービス。 */
@Service
@RequiredArgsConstructor
public class ManagementBudgetServiceImpl extends ServiceImpl<ManagementBudgetMapper, ManagementBudget>
        implements ManagementBudgetService {

    /** CSV一括取込の最大行数（ヘッダー除く）。 */
    private static final int MAX_CSV_ROWS = 200;

    private final OrganizationScopeService organizationScopeService;
    private final jakarta.validation.Validator validator;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OrganizationUnitMapper organizationUnitMapper;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CostCenterMapper costCenterMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ManagementBudget upsert(ManagementBudget budget, Integer expectedVersion) {
        validate(budget);
        ManagementBudget existing = getOne(new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getOrganizationId, budget.getOrganizationId())
                .eq(budget.getCostCenterId() != null, ManagementBudget::getCostCenterId, budget.getCostCenterId())
                .isNull(budget.getCostCenterId() == null, ManagementBudget::getCostCenterId)
                .eq(ManagementBudget::getBudgetMonth, budget.getBudgetMonth()));
        if (existing == null) {
            if (expectedVersion != null) {
                throw BusinessException.of("error.organization.budget.conflict");
            }
            budget.setVersion(0);
            try {
                save(budget);
            } catch (DuplicateKeyException e) {
                throw BusinessException.of("error.organization.budget.conflict");
            }
            return budget;
        }
        if (expectedVersion == null || !expectedVersion.equals(existing.getVersion())) {
            throw BusinessException.of("error.organization.budget.conflict");
        }
        budget.setId(existing.getId());
        // OptimisticLockerInnerInterceptor が version を検査し、成功時に +1 する。
        budget.setVersion(existing.getVersion());
        if (baseMapper.updateById(budget) != 1) {
            throw BusinessException.of("error.organization.budget.conflict");
        }
        return budget;
    }

    @Override
    public List<ManagementBudget> listByMonth(LocalDate budgetMonth) {
        return list(new LambdaQueryWrapper<ManagementBudget>()
                .eq(ManagementBudget::getBudgetMonth, budgetMonth)
                .orderByAsc(ManagementBudget::getOrganizationId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importFromCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.of("error.organization.budget.csvInvalid");
        }
        try {
            String csv = new String(file.getBytes(), StandardCharsets.UTF_8).replace("\uFEFF", "");
            String[] lines = csv.split("\\R");
            // 一括操作の上限は全spec共通で200件（shared-standards §3）。
            // 1行ごとにSELECT+UPDATEが走るため、上限なしだと1トランザクションで接続を占有し続ける。
            if (lines.length > MAX_CSV_ROWS + 1) {
                throw BusinessException.of("error.organization.budget.csvTooManyRows");
            }
            int imported = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isBlank() || (i == 0 && line.toLowerCase(Locale.ROOT).startsWith("organizationid,"))) {
                    continue;
                }
                String[] columns = line.split(",", -1);
                if (columns.length != 8) {
                    throw BusinessException.of("error.organization.budget.csvInvalid");
                }
                BudgetSaveRequest request = new BudgetSaveRequest(
                        Long.valueOf(columns[0].trim()), nullableLong(columns[1]), LocalDate.parse(columns[2].trim()),
                        new BigDecimal(columns[3].trim()), new BigDecimal(columns[4].trim()),
                        Integer.valueOf(columns[5].trim()), Integer.valueOf(columns[6].trim()), nullableInteger(columns[7]));
                validateRow(request);
                organizationScopeService.assertAllowedOrganization(request.organizationId());
                ManagementBudget budget = ManagementBudget.builder()
                        .organizationId(request.organizationId()).costCenterId(request.costCenterId())
                        .budgetMonth(request.budgetMonth()).revenue(request.revenue()).grossProfit(request.grossProfit())
                        .utilizationCount(request.utilizationCount()).hireCount(request.hireCount())
                        .version(request.version() == null ? 0 : request.version()).build();
                EntityProtectUtil.protectForCreate(budget);
                upsert(budget, request.version());
                imported++;
            }
            return imported;
        } catch (BusinessException e) {
            throw e;
        } catch (IllegalArgumentException | IOException e) {
            throw BusinessException.of("error.organization.budget.csvInvalid");
        }
    }

    /** JSON経路の{@code @Valid}と同じ制約（必須・負数禁止）をCSVの1行へ適用する。 */
    private void validateRow(BudgetSaveRequest request) {
        if (!validator.validate(request).isEmpty()) {
            throw BusinessException.of("error.organization.budget.csvInvalid");
        }
    }

    private Long nullableLong(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value.trim());
    }

    private Integer nullableInteger(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
    }

    private void validate(ManagementBudget budget) {
        if (budget == null || budget.getOrganizationId() == null || budget.getBudgetMonth() == null
                || budget.getRevenue() == null || budget.getGrossProfit() == null
                || budget.getUtilizationCount() == null || budget.getHireCount() == null
                || budget.getRevenue().signum() < 0 || budget.getGrossProfit().signum() < 0
                || budget.getUtilizationCount() < 0 || budget.getHireCount() < 0) {
            throw BusinessException.of("error.organization.budget.invalid");
        }
        if (!budget.getBudgetMonth().equals(budget.getBudgetMonth().withDayOfMonth(1))) {
            throw BusinessException.of("error.organization.budget.month");
        }
        if (organizationUnitMapper != null && costCenterMapper != null) {
            LocalDate month = budget.getBudgetMonth();
            OrganizationUnit organization = organizationUnitMapper.selectById(budget.getOrganizationId());
            if (!isActive(organization, month)) {
                throw BusinessException.of("error.organization.budget.organizationInvalid");
            }
            if (budget.getCostCenterId() != null) {
                CostCenter center = costCenterMapper.selectById(budget.getCostCenterId());
                if (!isActive(center, month)
                        || !budget.getOrganizationId().equals(center.getOrganizationId())
                        || !java.util.Objects.equals(organization.getLegalEntityId(), center.getLegalEntityId())) {
                    throw BusinessException.of("error.organization.budget.costCenterMismatch");
                }
            }
        }
    }

    private boolean isActive(OrganizationUnit organization, LocalDate date) {
        return organization != null && "有効".equals(organization.getStatus())
                && !organization.getValidFrom().isAfter(date)
                && (organization.getValidTo() == null || !organization.getValidTo().isBefore(date));
    }

    private boolean isActive(CostCenter center, LocalDate date) {
        return center != null && "有効".equals(center.getStatus())
                && !center.getValidFrom().isAfter(date)
                && (center.getValidTo() == null || !center.getValidTo().isBefore(date));
    }
}
