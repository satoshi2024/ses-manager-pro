package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.common.OptionDto;
import com.ses.dto.compliance.ContractComplianceProfileDetailDto;
import com.ses.dto.compliance.ContractComplianceProfileSaveDto;
import com.ses.entity.ComplianceFinding;
import com.ses.entity.Contract;
import com.ses.entity.ContractComplianceProfile;
import com.ses.entity.Workplace;
import com.ses.mapper.ComplianceFindingMapper;
import com.ses.mapper.ContractComplianceProfileMapper;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.WorkplaceMapper;
import com.ses.service.ContractComplianceProfileService;
import com.ses.service.ContractService;
import com.ses.service.MenuCacheService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * T063 A1: 契約compliance profileの取得・保存（design §5.3のfield mask適用）。
 *  - 管理者/HR: 全field（P0_FULL）
 *  - マネージャー: 待遇・保険・苦情詳細・雇用安定措置・抵触日例外はmask（P1_MASK）。
 *    保存時はsensitive fieldを「省略=現値維持・異なる値=reject」で保護する（画面maskによる誤消去を防ぐ）。
 *  - 営業: 契約遂行に必要な限定fieldのみ（P2_LIMITED）。書き込み不可。
 * maskはexport/PDF（T064）と同一のallow-listを共有する（design §5.3・field-mapping §2.1）。
 * findingsはcompliance menu権限（canViewCompliance）がある場合のみ返す。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContractComplianceProfileServiceImpl implements ContractComplianceProfileService {

    /** マネージャー（P1_MASK）でmaskするsensitive field（待遇・保険・苦情詳細・雇用安定措置・抵触日例外）。 */
    static final List<String> SENSITIVE_FIELDS = List.of(
            "dispatchFeeAmount", "dispatchFeeBasis", "dispatchFeeCurrency",
            "benefitsDetail", "benefitsProvidedFlag",
            "treatmentScheme",
            "socialInsuranceProcedureIncompleteReason",
            "healthInsuranceStatus", "healthInsuranceMissingReason", "healthInsuranceExpectedDate",
            "pensionInsuranceStatus", "pensionInsuranceMissingReason", "pensionInsuranceExpectedDate",
            "employmentInsuranceStatus", "employmentInsuranceMissingReason", "employmentInsuranceExpectedDate",
            "sourceComplaintContactDepartment", "sourceComplaintContactTitle",
            "sourceComplaintContactName", "sourceComplaintContactPhone",
            "clientComplaintContactDepartment", "clientComplaintContactTitle",
            "clientComplaintContactName", "clientComplaintContactPhone",
            "employmentStabilityPreference",
            "limitationExemptionType", "limitationExemptionDetail", "limitationExemptionBasis",
            "limitationExemptionFrom", "limitationExemptionTo");

    /** 営業（P2_LIMITED）が見られる限定field（契約遂行に必要な業務項目）。 */
    static final List<String> P2_ALLOWED_FIELDS = List.of(
            "contractTypeDetail", "workplaceId",
            "workDescription", "statutoryJobFlag", "statutoryJobReference",
            "responsibilityLevel", "responsibilityDetail",
            "commandPersonContactId", "commandPersonDepartment", "commandPersonTitle",
            "commandPersonName", "commandPersonPhone",
            "clientResponsibleContactId", "clientResponsibleDepartment", "clientResponsibleTitle",
            "clientResponsibleName", "clientResponsiblePhone",
            "dispatchResponsibleUserId", "dispatchResponsibleDepartment", "dispatchResponsibleTitle",
            "dispatchResponsibleName", "dispatchResponsiblePhone",
            "workStartMinute", "workEndMinute", "workSpanNextDayFlag",
            "breakStartMinute", "breakEndMinute",
            "workDayCode", "holidayCalendarCode",
            "agreementReferenceId",
            "overtimeDailyLimit", "overtimeMonthlyLimit", "overtimeYearlyLimit",
            "overtimePeriodFrom", "overtimePeriodTo",
            "workplaceLimitationDate", "organizationLimitationDate",
            "safetyResponsibilityDetail", "safetyRuleReference",
            "dispatchHeadcount", "agreementTargetFlag",
            "instructionRoute", "subcontractAllowed", "acceptanceMethod",
            "dispatchPeriodStart", "dispatchPeriodEnd");

    /** T066 gateまでのserver管理field（保存DTOに含めず、画面の編集対象にしない）。 */
    static final List<String> SERVER_MANAGED_FIELDS = List.of(
            "retentionDueDate", "legalHoldFlag");

    private final ContractService contractService;
    private final ContractComplianceProfileMapper profileMapper;
    private final ComplianceFindingMapper findingMapper;
    private final WorkplaceMapper workplaceMapper;
    private final CustomerMapper customerMapper;
    private final EngineerMapper engineerMapper;
    private final ProjectMapper projectMapper;
    private final SysUserMapper sysUserMapper;
    private final CustomerContactMapper customerContactMapper;
    private final DataScopeService dataScopeService;
    private final ObjectProvider<MenuCacheService> menuCacheServiceProvider;
    private final ObjectMapper objectMapper;

    @Override
    public ContractComplianceProfileDetailDto detail(Long contractId) {
        Contract contract = requireVisibleContract(contractId);
        String role = SecurityUtils.currentRole();
        ContractComplianceProfile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<ContractComplianceProfile>()
                        .eq(ContractComplianceProfile::getContractId, contractId));
        ContractComplianceProfile display = profile == null ? null : copy(profile);
        if (display != null) {
            applyMask(display, role);
        }
        ContractComplianceProfileDetailDto dto = new ContractComplianceProfileDetailDto();
        dto.setContractId(contractId);
        dto.setContractType(contract.getContractType());
        dto.setContractNo(contract.getContractNo());
        com.ses.entity.Engineer engineer = contract.getEngineerId() == null ? null
                : engineerMapper.selectById(contract.getEngineerId());
        dto.setEngineerName(engineer == null ? null : engineer.getFullName());
        com.ses.entity.Customer customer = contract.getCustomerId() == null ? null
                : customerMapper.selectById(contract.getCustomerId());
        dto.setCustomerName(customer == null ? null : customer.getCompanyName());
        com.ses.entity.Project project = contract.getProjectId() == null ? null
                : projectMapper.selectById(contract.getProjectId());
        dto.setProjectName(project == null ? null : project.getProjectName());
        dto.setProfile(display);
        dto.setProfileExists(profile != null);
        dto.setFindings(canViewCompliance(role)
                ? findingMapper.selectList(new LambdaQueryWrapper<ComplianceFinding>()
                        .eq(ComplianceFinding::getContractId, contractId)
                        .orderByDesc(ComplianceFinding::getDetectedAt))
                : List.of());
        dto.setWorkplaces(workplaceOptions(contract.getCustomerId()));
        dto.setMaskLevel(maskLevel(role));
        dto.setCanEdit(!"営業".equals(role));
        return dto;
    }

    @Override
    @Transactional
    public ContractComplianceProfileDetailDto save(Long contractId, String rawBody) {
        Contract contract = requireVisibleContract(contractId);
        String role = SecurityUtils.currentRole();
        if ("営業".equals(role)) {
            throw BusinessException.of(403, "contract.compliance.writeDenied");
        }
        JsonNode root = parse(rawBody);
        List<String> editable = editableFields();
        List<String> missing = new ArrayList<>();
        for (String field : editable) {
            if (!root.has(field)) {
                missing.add(field);
            }
        }
        if (isMaskedRole(role)) {
            // masked roleはsensitive fieldを省略できる（省略=現値維持）。省略キーの存在チェックはしない。
            missing.removeAll(SENSITIVE_FIELDS);
        }
        if (!missing.isEmpty()) {
            throw BusinessException.of(400, "contract.compliance.missingFields", String.join(",", missing));
        }
        ContractComplianceProfileSaveDto dto;
        try {
            dto = objectMapper.convertValue(root, ContractComplianceProfileSaveDto.class);
        } catch (IllegalArgumentException e) {
            throw BusinessException.of(400, "contract.compliance.invalidBody");
        }
        validate(dto, contract);
        ContractComplianceProfile existing = profileMapper.selectOne(
                new LambdaQueryWrapper<ContractComplianceProfile>()
                        .eq(ContractComplianceProfile::getContractId, contractId));
        if (isMaskedRole(role)) {
            guardSensitiveUnchanged(root, existing);
        }
        if (existing == null) {
            ContractComplianceProfile entity = new ContractComplianceProfile();
            BeanUtils.copyProperties(dto, entity);
            entity.setContractId(contractId);
            entity.setTenantId("default");
            profileMapper.insert(entity);
        } else {
            if (dto.getVersion() == null) {
                throw BusinessException.of(400, "contract.compliance.versionRequired");
            }
            if (!dto.getVersion().equals(existing.getVersion())) {
                throw BusinessException.of(409, "contract.compliance.versionConflict");
            }
            // masked roleはsensitive fieldをpayloadへ含めないため、DTOのnullで現値を上書きしない。
            // guardSensitiveUnchangedが変更をrejectしたうえで、現値をrestoreして保存する（画面maskによる誤消去防止）。
            Map<String, Object> preservedSensitive = isMaskedRole(role)
                    ? preserveSensitive(existing) : Map.of();
            BeanUtils.copyProperties(dto, existing);
            restoreSensitive(existing, preservedSensitive);
            existing.setContractId(contractId);
            existing.setTenantId("default");
            int rows = profileMapper.updateById(existing);
            if (rows == 0) {
                throw BusinessException.of(409, "contract.compliance.versionConflict");
            }
        }
        return detail(contractId);
    }

    // ===== 参照・権限 =====

    private Contract requireVisibleContract(Long contractId) {
        Contract contract = contractService.getById(contractId);
        if (contract == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        dataScopeService.assertAllowedContract(contractId);
        return contract;
    }

    /** compliance menu権限を再チェックする（MonthlyClosingServiceImpl.canViewComplianceと同じ方式・fail-closed）。 */
    private boolean canViewCompliance(String role) {
        if ("管理者".equals(role)) {
            return true;
        }
        try {
            MenuCacheService menuCacheService = menuCacheServiceProvider.getIfAvailable();
            return menuCacheService != null && menuCacheService.getMenuKeysByRole(role).contains("compliance");
        } catch (Exception e) {
            log.warn("compliance menu権限の確認に失敗したためfindingsを非表示にします（role={}）", role, e);
            return false;
        }
    }

    private List<OptionDto> workplaceOptions(Long customerId) {
        if (customerId == null) {
            return List.of();
        }
        return workplaceMapper.selectList(new LambdaQueryWrapper<Workplace>()
                        .eq(Workplace::getCustomerId, customerId))
                .stream()
                .map(w -> new OptionDto(w.getId(), w.getName()
                        + (w.getOrganizationUnit() != null ? "（" + w.getOrganizationUnit() + "）" : "")))
                .toList();
    }

    // ===== mask =====

    private String maskLevel(String role) {
        if ("営業".equals(role)) {
            return "LIMITED";
        }
        if ("マネージャー".equals(role)) {
            return "MASK";
        }
        return "FULL";
    }

    private boolean isMaskedRole(String role) {
        return "マネージャー".equals(role);
    }

    private void applyMask(ContractComplianceProfile profile, String role) {
        if ("マネージャー".equals(role)) {
            maskFields(profile, SENSITIVE_FIELDS);
            maskFields(profile, SERVER_MANAGED_FIELDS);
        } else if ("営業".equals(role)) {
            Set<String> allowed = new LinkedHashSet<>(P2_ALLOWED_FIELDS);
            for (String field : editableFields()) {
                if (!allowed.contains(field)) {
                    maskField(profile, field);
                }
            }
            maskFields(profile, SERVER_MANAGED_FIELDS);
        }
    }

    private void maskFields(ContractComplianceProfile profile, List<String> fields) {
        fields.forEach(field -> maskField(profile, field));
    }

    private void maskField(ContractComplianceProfile profile, String fieldName) {
        try {
            Field field = ContractComplianceProfile.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(profile, null);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("mask対象fieldがentityに存在しません: " + fieldName, e);
        }
    }

    // ===== 保存validation =====

    private JsonNode parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) {
            throw BusinessException.of(400, "contract.compliance.invalidBody");
        }
        try {
            return objectMapper.readTree(rawBody);
        } catch (Exception e) {
            throw BusinessException.of(400, "contract.compliance.invalidBody");
        }
    }

    private void validate(ContractComplianceProfileSaveDto dto, Contract contract) {
        requireMinutes("contract.compliance.invalidWorkMinutes", dto.getWorkStartMinute());
        requireMinutes("contract.compliance.invalidWorkMinutes", dto.getWorkEndMinute());
        requireMinutes("contract.compliance.invalidBreakMinutes", dto.getBreakStartMinute());
        requireMinutes("contract.compliance.invalidBreakMinutes", dto.getBreakEndMinute());
        requireNonNegative("contract.compliance.invalidOvertime", dto.getOvertimeDailyLimit());
        requireNonNegative("contract.compliance.invalidOvertime", dto.getOvertimeMonthlyLimit());
        requireNonNegative("contract.compliance.invalidOvertime", dto.getOvertimeYearlyLimit());
        requireNonNegative("contract.compliance.invalidHeadcount", dto.getDispatchHeadcount());
        if (dto.getDispatchFeeAmount() != null && dto.getDispatchFeeAmount().signum() < 0) {
            throw BusinessException.of(400, "contract.compliance.invalidFee");
        }
        requireDateOrder("contract.compliance.invalidOvertimePeriod", dto.getOvertimePeriodFrom(), dto.getOvertimePeriodTo());
        requireDateOrder("contract.compliance.invalidExemptionPeriod", dto.getLimitationExemptionFrom(), dto.getLimitationExemptionTo());
        requireDateOrder("contract.compliance.invalidDispatchPeriod", dto.getDispatchPeriodStart(), dto.getDispatchPeriodEnd());
        requireFlag("contract.compliance.invalidFlag", dto.getStatutoryJobFlag());
        requireFlag("contract.compliance.invalidFlag", dto.getWorkSpanNextDayFlag());
        requireFlag("contract.compliance.invalidFlag", dto.getBenefitsProvidedFlag());
        requireFlag("contract.compliance.invalidFlag", dto.getAgreementTargetFlag());
        requireFlag("contract.compliance.invalidFlag", dto.getSubcontractAllowed());
        requireFlag("contract.compliance.invalidFlag", dto.getLegalHoldFlag());
        if (dto.getWorkplaceId() != null) {
            Workplace workplace = workplaceMapper.selectById(dto.getWorkplaceId());
            if (workplace == null) {
                throw BusinessException.of(400, "contract.compliance.invalidWorkplace");
            }
            if (contract.getCustomerId() != null && workplace.getCustomerId() != null
                    && !workplace.getCustomerId().equals(contract.getCustomerId())) {
                throw BusinessException.of(400, "contract.compliance.workplaceCustomerMismatch");
            }
        }
        if (dto.getCommandPersonContactId() != null && customerContactMapper.selectById(dto.getCommandPersonContactId()) == null) {
            throw BusinessException.of(400, "contract.compliance.invalidContact");
        }
        if (dto.getClientResponsibleContactId() != null && customerContactMapper.selectById(dto.getClientResponsibleContactId()) == null) {
            throw BusinessException.of(400, "contract.compliance.invalidContact");
        }
        if (dto.getDispatchResponsibleUserId() != null && sysUserMapper.selectById(dto.getDispatchResponsibleUserId()) == null) {
            throw BusinessException.of(400, "contract.compliance.invalidUser");
        }
    }

    private void requireMinutes(String key, Integer value) {
        if (value != null && (value < 0 || value > 1439)) {
            throw BusinessException.of(400, key);
        }
    }

    private void requireNonNegative(String key, Integer value) {
        if (value != null && value < 0) {
            throw BusinessException.of(400, key);
        }
    }

    private void requireDateOrder(String key, LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw BusinessException.of(400, key);
        }
    }

    private void requireFlag(String key, Integer value) {
        if (value != null && value != 0 && value != 1) {
            throw BusinessException.of(400, key);
        }
    }

    /** masked roleがsensitive fieldを変更できないことを検証する（省略=現値維持、同値=OK、異なる値=reject）。 */
    private void guardSensitiveUnchanged(JsonNode root, ContractComplianceProfile existing) {
        for (String field : SENSITIVE_FIELDS) {
            if (!root.has(field)) {
                continue;
            }
            JsonNode node = root.get(field);
            Object current = existing == null ? null : readField(existing, field);
            String currentText = current == null ? null : String.valueOf(current);
            String payloadText = node.isNull() ? null : node.asText();
            if (!Objects.equals(currentText, payloadText)) {
                throw BusinessException.of(403, "contract.compliance.sensitiveForbidden");
            }
        }
    }

    private Object readField(ContractComplianceProfile profile, String fieldName) {
        try {
            Field field = ContractComplianceProfile.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(profile);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("fieldがentityに存在しません: " + fieldName, e);
        }
    }

    private void writeField(ContractComplianceProfile profile, String fieldName, Object value) {
        try {
            Field field = ContractComplianceProfile.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(profile, value);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("fieldがentityに存在しません: " + fieldName, e);
        }
    }

    private Map<String, Object> preserveSensitive(ContractComplianceProfile profile) {
        Map<String, Object> values = new java.util.HashMap<>();
        SENSITIVE_FIELDS.forEach(field -> values.put(field, readField(profile, field)));
        return values;
    }

    private void restoreSensitive(ContractComplianceProfile profile, Map<String, Object> values) {
        values.forEach((field, value) -> writeField(profile, field, value));
    }

    // ===== 共通 =====

    private ContractComplianceProfile copy(ContractComplianceProfile source) {
        ContractComplianceProfile target = new ContractComplianceProfile();
        BeanUtils.copyProperties(source, target);
        return target;
    }

    /** 保存DTOの全編集可能field名（version以外）。SaveDtoのfield定義と同期する。 */
    private List<String> editableFields() {
        return Arrays.stream(ContractComplianceProfileSaveDto.class.getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .map(Field::getName)
                .filter(name -> !"version".equals(name))
                .toList();
    }
}
