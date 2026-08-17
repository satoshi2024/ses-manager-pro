package com.ses.service.changerequest.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PageUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.engineer.EngineerSkillDetailDto;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Contract;
import com.ses.entity.DocumentLink;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerCareer;
import com.ses.entity.EngineerChangeRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.EngineerCareerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerChangeRequestMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.DocumentService;
import com.ses.service.EngineerSalesService;
import com.ses.service.EngineerSkillService;
import com.ses.service.NotificationService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalTargetAdapterRegistry;
import com.ses.service.changerequest.EngineerChangeRequestService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.skillsheet.SkillSheetGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * プロフィール/スキル変更申請サービス実装（T089 / design §1/§2/§6.3）。
 * payloadは request_type ごとのallowlistを通過した値のみを保存する（任意JSON→entity反映の禁止）。
 * masterへの反映は EngineerChangeRequestApprovalAdapter.applyApproved（最終承認時）でのみ行われる。
 * 承認前はEngineer masterを一切変更しない（R5）。
 */
@Service
@RequiredArgsConstructor
public class EngineerChangeRequestServiceImpl implements EngineerChangeRequestService {

    private static final Set<String> PROFILE_ALLOWED = Set.of(
            "fullName", "fullNameKana", "initialName", "gender", "birthDate", "nationality",
            "nearestStation", "prefecture", "railwayCompany", "expectedUnitPrice",
            "availableDate", "experienceYears", "japaneseLevel", "resumeSummary");

    private final EngineerChangeRequestMapper changeRequestMapper;
    private final EngineerMapper engineerMapper;
    private final EngineerCareerMapper engineerCareerMapper;
    private final EngineerSkillService engineerSkillService;
    private final ApprovalRequestMapper approvalRequestMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final ContractMapper contractMapper;
    private final CustomerMapper customerMapper;
    private final ProjectMapper projectMapper;
    private final SysUserMapper sysUserMapper;
    private final EngineerSalesService engineerSalesService;
    private final ApprovalTargetAdapterRegistry approvalTargetAdapterRegistry;
    private final ApprovalEngineService approvalEngineService;
    private final DocumentService documentService;
    private final NotificationService notificationService;
    private final OrganizationScopeService organizationScopeService;
    private final SkillSheetGenerator skillSheetGenerator;
    private final ObjectMapper objectMapper;

    // ----------------------------------------------------------------
    // 本人
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<ChangeRequestDto> pageOwn(Long engineerId, String status, long current, long size) {
        LambdaQueryWrapper<EngineerChangeRequest> query = new LambdaQueryWrapper<EngineerChangeRequest>()
                .eq(EngineerChangeRequest::getEngineerId, engineerId)
                .orderByDesc(EngineerChangeRequest::getId);
        if (status != null && !status.isBlank()) {
            query.eq(EngineerChangeRequest::getStatus, status);
        }
        Page<EngineerChangeRequest> page = changeRequestMapper.selectPage(PageUtils.safePage(current, size), query);
        return toDtoPage(page, false);
    }

    @Override
    @Transactional(readOnly = true)
    public ChangeRequestDto detailOwn(Long engineerId, Long id) {
        return toDto(requireOwned(engineerId, id), approvalOf(requireOwned(engineerId, id)), null);
    }

    @Override
    @Transactional
    public ChangeRequestDto createDraft(Long engineerId, String requestType, Map<String, Object> payload) {
        validatePayload(requestType, payload);
        String diffJson = buildDiff(requestType, engineerId, payload);
        EngineerChangeRequest draft = EngineerChangeRequest.builder()
                .engineerId(engineerId)
                .requestType(requestType)
                .payloadJson(writeJson(payload))
                .diffJson(diffJson)
                .status(STATUS_DRAFT)
                .version(0)
                .build();
        changeRequestMapper.insert(draft);
        return toDto(draft, null, null);
    }

    @Override
    @Transactional
    public ChangeRequestDto submit(Long engineerId, Long id) {
        EngineerChangeRequest request = requireOwned(engineerId, id);
        if (!STATUS_DRAFT.equals(request.getStatus())) {
            throw BusinessException.of(400, "error.changeRequest.invalidTransition",
                    request.getStatus(), STATUS_APPLIED);
        }
        ApprovalRequest approval = approvalTargetAdapterRegistry.request(
                request.getRequestType(), "CHANGE_REQUEST", id,
                Map.of("action", "submit"), SecurityUtils.currentUserId());
        int version = value(request.getVersion());
        int updated = changeRequestMapper.update(null, new UpdateWrapper<EngineerChangeRequest>()
                .eq("id", id).eq("status", STATUS_DRAFT).eq("version", version)
                .set("status", STATUS_APPLIED)
                .set("approval_request_id", approval.getId())
                .set("version", version + 1)
                .set("updated_at", LocalDateTime.now()));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return toDto(requireOwned(engineerId, id), approval, null);
    }

    @Override
    @Transactional
    public ChangeRequestDto withdraw(Long engineerId, Long id) {
        EngineerChangeRequest request = requireOwned(engineerId, id);
        if (!STATUS_APPLIED.equals(request.getStatus()) || request.getApprovalRequestId() == null) {
            throw BusinessException.of(400, "error.changeRequest.invalidTransition",
                    request.getStatus(), STATUS_WITHDRAWN);
        }
        approvalEngineService.withdraw(request.getApprovalRequestId(), SecurityUtils.currentUserId());
        int version = value(request.getVersion());
        int updated = changeRequestMapper.update(null, new UpdateWrapper<EngineerChangeRequest>()
                .eq("id", id).eq("status", STATUS_APPLIED).eq("version", version)
                .set("status", STATUS_WITHDRAWN)
                .set("version", version + 1)
                .set("updated_at", LocalDateTime.now()));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return toDto(requireOwned(engineerId, id),
                approvalRequestMapper.selectById(request.getApprovalRequestId()), null);
    }

    @Override
    @Transactional
    public ChangeRequestDto resubmit(Long engineerId, Long id) {
        EngineerChangeRequest request = requireOwned(engineerId, id);
        if (!STATUS_APPLIED.equals(request.getStatus()) || request.getApprovalRequestId() == null) {
            throw BusinessException.of(400, "error.changeRequest.invalidTransition",
                    request.getStatus(), STATUS_APPLIED);
        }
        ApprovalRequest approval = approvalRequestMapper.selectById(request.getApprovalRequestId());
        if (approval == null
                || (!"returned".equals(approval.getStatus()) && !"conflict".equals(approval.getStatus()))) {
            throw BusinessException.of(400, "error.changeRequest.notReturned");
        }
        approvalEngineService.resubmit(approval.getId(), SecurityUtils.currentUserId(), null, null, null);
        return toDto(requireOwned(engineerId, id),
                approvalRequestMapper.selectById(request.getApprovalRequestId()), null);
    }

    // ----------------------------------------------------------------
    // 管理
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<ChangeRequestDto> pageManagement(String engineerName, String requestType, String status,
                                                 long current, long size) {
        Set<Long> scopeIds = managementScopeEngineerIds();
        if (scopeIds != null && scopeIds.isEmpty()) {
            return new Page<>(current <= 0 ? 1 : current, size <= 0 ? PageUtils.DEFAULT_PAGE_SIZE : size);
        }
        LambdaQueryWrapper<EngineerChangeRequest> query = new LambdaQueryWrapper<EngineerChangeRequest>()
                .orderByDesc(EngineerChangeRequest::getId);
        if (scopeIds != null) {
            query.in(EngineerChangeRequest::getEngineerId, scopeIds);
        }
        if (requestType != null && !requestType.isBlank()) {
            query.eq(EngineerChangeRequest::getRequestType, requestType);
        }
        if (status != null && !status.isBlank()) {
            query.eq(EngineerChangeRequest::getStatus, status);
        }
        if (engineerName != null && !engineerName.isBlank()) {
            Set<Long> nameIds = engineerMapper.selectList(new LambdaQueryWrapper<Engineer>()
                            .like(Engineer::getFullName, engineerName.trim()))
                    .stream().map(Engineer::getId).collect(Collectors.toSet());
            if (nameIds.isEmpty()) {
                return new Page<>(current <= 0 ? 1 : current, size <= 0 ? PageUtils.DEFAULT_PAGE_SIZE : size);
            }
            if (scopeIds != null) {
                nameIds.retainAll(scopeIds);
                if (nameIds.isEmpty()) {
                    return new Page<>(current <= 0 ? 1 : current, size <= 0 ? PageUtils.DEFAULT_PAGE_SIZE : size);
                }
            }
            query.in(EngineerChangeRequest::getEngineerId, nameIds);
        }
        Page<EngineerChangeRequest> page = changeRequestMapper.selectPage(PageUtils.safePage(current, size), query);
        return toDtoPage(page, true);
    }

    @Override
    @Transactional(readOnly = true)
    public ChangeRequestDto detailManagement(Long id) {
        EngineerChangeRequest request = require(id);
        Long engineerId = request.getEngineerId();
        Set<Long> scopeIds = managementScopeEngineerIds();
        if (scopeIds != null && (engineerId == null || !scopeIds.contains(engineerId))) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
        return toDto(request, approvalOf(request), engineerNameOf(engineerId));
    }

    // ----------------------------------------------------------------
    // my profile / skill sheet
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public MyProfileView myProfile(Long engineerId) {
        Engineer engineer = engineerOrThrow(engineerId);
        List<EngineerSkillDetailDto> skills = engineerSkillService.listDetail(engineerId);
        List<EngineerCareer> careers = sortedCareers(engineerId);
        Long salesUserId = engineerSalesService.findPrimarySalesUserId(engineerId);
        String salesName = null;
        if (salesUserId != null) {
            SysUser sales = sysUserMapper.selectById(salesUserId);
            salesName = sales == null ? null : sales.getRealName();
        }
        List<PublicContract> contracts = currentPublicContracts(engineerId);
        long pending = changeRequestMapper.selectCount(new LambdaQueryWrapper<EngineerChangeRequest>()
                .eq(EngineerChangeRequest::getEngineerId, engineerId)
                .eq(EngineerChangeRequest::getStatus, STATUS_APPLIED));
        return new MyProfileView(engineerId, engineer.getFullName(), engineer.getFullNameKana(), engineer.getInitialName(),
                engineer.getGender(), engineer.getBirthDate(), engineer.getNationality(), engineer.getNearestStation(),
                engineer.getPrefecture(), engineer.getRailwayCompany(), engineer.getEmploymentType(), engineer.getStatus(),
                engineer.getExpectedUnitPrice(), engineer.getAvailableDate(), engineer.getExperienceYears(),
                engineer.getJapaneseLevel(), engineer.getResumeSummary(), skills, careers, salesName, salesUserId,
                contracts, pending);
    }

    @Override
    @Transactional(readOnly = true)
    public SkillSheetPreview skillSheetPreview(Long engineerId) {
        Engineer engineer = engineerOrThrow(engineerId);
        List<SkillSheetSkillRow> skills = engineerSkillService.listDetail(engineerId).stream()
                .map(s -> new SkillSheetSkillRow(nullToEmpty(s.getSkillName()), nullToEmpty(s.getCategory()),
                        nullToEmpty(s.getProficiency()), s.getExperienceYears()))
                .toList();
        List<SkillSheetCareerRow> careers = sortedCareers(engineerId).stream()
                .map(c -> new SkillSheetCareerRow(c.getPeriodFrom(), c.getPeriodTo(), c.getProjectName(),
                        c.getClientIndustry(), c.getRole(), c.getDescription(), c.getTechStack(), c.getTeamSize()))
                .toList();
        String fingerprint = fingerprint(engineer, skills, careers);
        DocumentLink confirmed = latestConfirmedLink(engineerId);
        LocalDateTime confirmedAt = confirmed == null ? null : confirmed.getSkillSheetConfirmedAt();
        String confirmedVersion = confirmed == null ? null : confirmed.getSkillSheetConfirmedVersion();
        return new SkillSheetPreview(engineer.getFullName(), engineer.getNearestStation(), engineer.getPrefecture(),
                engineer.getRailwayCompany(), engineer.getAvailableDate(), engineer.getJapaneseLevel(),
                engineer.getResumeSummary(), skills, careers, fingerprint, confirmedAt, confirmedVersion,
                fingerprint.equals(confirmedVersion));
    }

    @Override
    @Transactional
    public SkillSheetConfirmResult confirmSkillSheet(Long engineerId, String fingerprint) {
        SkillSheetPreview preview = skillSheetPreview(engineerId);
        if (fingerprint == null || !fingerprint.equals(preview.fingerprint())) {
            throw BusinessException.of(409, "error.changeRequest.skillSheetStale");
        }
        byte[] pdf = skillSheetGenerator.generatePdf(engineerId);
        String businessKey = "SKILL_SHEET:" + engineerId;
        com.ses.entity.Document document = documentService.registerGenerated(
                com.ses.dto.document.DocumentRegisterRequest.builder()
                        .documentType("SKILL_SHEET")
                        .title("スキルシート " + preview.engineerName())
                        .sourceType("GENERATED")
                        .direction("INTERNAL")
                        .counterpartyType("INTERNAL")
                        .transactionDate(LocalDate.now())
                        .businessKey(businessKey)
                        .versionDiscriminator(fingerprint)
                        .originalName("skill-sheet-" + engineerId + ".pdf")
                        .contentType("application/pdf")
                        .build(), new java.io.ByteArrayInputStream(pdf));
        documentService.link(document.getId(), "ENGINEER", engineerId);
        // 確認時点をt_document_linkへ記録する（design §6.1: 確認ごとに更新）。
        DocumentLink link = documentLinkMapper.selectOne(new LambdaQueryWrapper<DocumentLink>()
                .eq(DocumentLink::getDocumentId, document.getId())
                .eq(DocumentLink::getTargetType, "ENGINEER")
                .eq(DocumentLink::getTargetId, engineerId)
                .orderByDesc(DocumentLink::getId)
                .last("LIMIT 1"));
        if (link != null) {
            link.setSkillSheetConfirmedAt(LocalDateTime.now());
            link.setSkillSheetConfirmedVersion(fingerprint);
            documentLinkMapper.updateById(link);
        }
        return new SkillSheetConfirmResult(fingerprint, LocalDateTime.now());
    }

    @Override
    @Transactional(readOnly = true)
    public long pendingChangeRequestCount(Long engineerId) {
        return changeRequestMapper.selectCount(new LambdaQueryWrapper<EngineerChangeRequest>()
                .eq(EngineerChangeRequest::getEngineerId, engineerId)
                .in(EngineerChangeRequest::getStatus, List.of(STATUS_APPLIED, STATUS_APPROVED)));
    }

    // ----------------------------------------------------------------
    // allowlist検証（design §6.3）。allowlist外のkeyはリクエストを拒否する。
    // ----------------------------------------------------------------

    void validatePayload(String requestType, Map<String, Object> payload) {
        if (payload == null) {
            throw BusinessException.of(400, "error.changeRequest.payloadRequired");
        }
        switch (requestType == null ? "" : requestType) {
            case TYPE_PROFILE -> {
                Set<String> unknown = payload.keySet().stream()
                        .filter(k -> !PROFILE_ALLOWED.contains(k)).collect(Collectors.toSet());
                if (!unknown.isEmpty()) {
                    throw BusinessException.of(400, "error.changeRequest.unknownField", String.join(", ", unknown));
                }
                if (payload.isEmpty()) {
                    throw BusinessException.of(400, "error.changeRequest.emptyPayload");
                }
                Object exp = payload.get("expectedUnitPrice");
                if (exp != null) {
                    BigDecimal v;
                    try {
                        v = new BigDecimal(String.valueOf(exp));
                    } catch (NumberFormatException e) {
                        throw BusinessException.of(400, "error.changeRequest.invalidExpectedUnitPrice");
                    }
                    if (v.compareTo(BigDecimal.ZERO) < 0) {
                        throw BusinessException.of(400, "error.changeRequest.invalidExpectedUnitPrice");
                    }
                }
            }
            case TYPE_SKILL -> {
                Object skills = payload.get("skills");
                if (!(skills instanceof List<?> list) || list.isEmpty()) {
                    throw BusinessException.of(400, "error.changeRequest.skillsRequired");
                }
                if (list.size() > 200) {
                    throw BusinessException.of(400, "error.changeRequest.tooManySkills");
                }
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) {
                        throw BusinessException.of(400, "error.changeRequest.invalidSkill");
                    }
                    Set<Object> allowed = Set.of("skillId", "proficiency", "experienceYears");
                    if (!m.keySet().stream().allMatch(allowed::contains) || m.get("skillId") == null) {
                        throw BusinessException.of(400, "error.changeRequest.invalidSkill");
                    }
                }
            }
            case TYPE_CAREER -> {
                Object careers = payload.get("careers");
                if (!(careers instanceof List<?> list) || list.isEmpty()) {
                    throw BusinessException.of(400, "error.changeRequest.careersRequired");
                }
                if (list.size() > 200) {
                    throw BusinessException.of(400, "error.changeRequest.tooManyCareers");
                }
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> m)) {
                        throw BusinessException.of(400, "error.changeRequest.invalidCareer");
                    }
                    Set<Object> allowed = Set.of("periodFrom", "periodTo", "projectName", "clientIndustry", "role",
                            "description", "techStack", "teamSize");
                    if (!m.keySet().stream().allMatch(allowed::contains) || m.get("projectName") == null) {
                        throw BusinessException.of(400, "error.changeRequest.invalidCareer");
                    }
                }
            }
            default -> throw BusinessException.of(400, "error.changeRequest.invalidType");
        }
    }

    private String buildDiff(String requestType, Long engineerId, Map<String, Object> payload) {
        Map<String, Object> diff = new LinkedHashMap<>();
        switch (requestType) {
            case TYPE_PROFILE -> {
                Engineer engineer = engineerOrThrow(engineerId);
                for (Map.Entry<String, Object> e : payload.entrySet()) {
                    Object before = fieldValue(engineer, e.getKey());
                    Object after = e.getValue();
                    if (!Objects.equals(before, after)) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("before", before);
                        row.put("after", after);
                        diff.put(e.getKey(), row);
                    }
                }
            }
            case TYPE_SKILL -> diff.put("skills", payload.get("skills"));
            case TYPE_CAREER -> diff.put("careers", payload.get("careers"));
            default -> {
            }
        }
        return writeJson(diff);
    }

    private Object fieldValue(Engineer engineer, String field) {
        try {
            var method = Engineer.class.getMethod("get" + Character.toUpperCase(field.charAt(0)) + field.substring(1));
            return method.invoke(engineer);
        } catch (Exception e) {
            return null;
        }
    }

    private List<EngineerCareer> sortedCareers(Long engineerId) {
        return engineerCareerMapper.selectList(new LambdaQueryWrapper<EngineerCareer>()
                .eq(EngineerCareer::getEngineerId, engineerId)
                .orderByDesc(EngineerCareer::getPeriodFrom));
    }

    private List<PublicContract> currentPublicContracts(Long engineerId) {
        List<Contract> contracts = contractMapper.selectList(new LambdaQueryWrapper<Contract>()
                .eq(Contract::getEngineerId, engineerId)
                .eq(Contract::getStatus, "稼動中")
                .orderByAsc(Contract::getStartDate));
        return contracts.stream().map(c -> {
            String customerName = null;
            if (c.getCustomerId() != null) {
                var customer = customerMapper.selectById(c.getCustomerId());
                customerName = customer == null ? null : customer.getCompanyName();
            }
            String projectName = null;
            if (c.getProjectId() != null) {
                var project = projectMapper.selectById(c.getProjectId());
                projectName = project == null ? null : project.getProjectName();
            }
            return new PublicContract(c.getContractNo(), projectName, customerName, c.getStartDate(), c.getEndDate(),
                    c.getContractType(), c.getStatus(), c.getJobDescription(), c.getWorkLocation());
        }).toList();
    }

    private DocumentLink latestConfirmedLink(Long engineerId) {
        return documentLinkMapper.selectList(new LambdaQueryWrapper<DocumentLink>()
                        .eq(DocumentLink::getTargetType, "ENGINEER")
                        .eq(DocumentLink::getTargetId, engineerId)
                        .isNotNull(DocumentLink::getSkillSheetConfirmedAt)
                        .orderByDesc(DocumentLink::getSkillSheetConfirmedAt)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private String fingerprint(Engineer engineer, List<SkillSheetSkillRow> skills, List<SkillSheetCareerRow> careers) {
        StringBuilder sb = new StringBuilder();
        sb.append(engineer.getId()).append('|').append(engineer.getUpdatedAt());
        sb.append("|skills=").append(writeJson(skills.stream()
                .sorted(java.util.Comparator.comparing(s -> String.valueOf(s.experienceYears())))
                .toList()));
        sb.append("|careers=").append(writeJson(careers.stream()
                .sorted(java.util.Comparator.comparing(SkillSheetCareerRow::projectName,
                        java.util.Comparator.nullsLast(String::compareTo)))
                .toList()));
        return sha256(sb.toString());
    }

    private String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private EngineerChangeRequest requireOwned(Long engineerId, Long id) {
        EngineerChangeRequest request = require(id);
        if (!Objects.equals(engineerId, request.getEngineerId())) {
            throw BusinessException.of(403, "error.my.notOwner");
        }
        return request;
    }

    private EngineerChangeRequest require(Long id) {
        EngineerChangeRequest request = id == null ? null : changeRequestMapper.selectById(id);
        if (request == null) {
            throw BusinessException.of(404, "error.changeRequest.notFound");
        }
        return request;
    }

    private Engineer engineerOrThrow(Long engineerId) {
        Engineer engineer = engineerId == null ? null : engineerMapper.selectById(engineerId);
        if (engineer == null) {
            throw BusinessException.of(404, "error.engineer.notFound");
        }
        return engineer;
    }

    private ApprovalRequest approvalOf(EngineerChangeRequest request) {
        if (request.getApprovalRequestId() == null) {
            return null;
        }
        return approvalRequestMapper.selectById(request.getApprovalRequestId());
    }

    /** 管理画面の母集団（design §6.2）: HR/管理者=全件(null)、マネージャー=組織scope配下。 */
    private Set<Long> managementScopeEngineerIds() {
        String role = SecurityUtils.currentRole();
        if (!Set.of("HR", "管理者", "マネージャー").contains(role)) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        if ("HR".equals(role) || "管理者".equals(role) || organizationScopeService.hasFullAccess()) {
            return null;
        }
        Set<Long> allowed = organizationScopeService.allowedEngineerIds(LocalDate.now());
        return allowed == null ? Set.of() : new HashSet<>(allowed);
    }

    private Page<ChangeRequestDto> toDtoPage(Page<EngineerChangeRequest> page, boolean withEngineerName) {
        Set<Long> approvalIds = page.getRecords().stream()
                .map(EngineerChangeRequest::getApprovalRequestId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, ApprovalRequest> approvals = approvalIds.isEmpty() ? Map.of()
                : approvalRequestMapper.selectBatchIds(approvalIds).stream()
                .collect(Collectors.toMap(ApprovalRequest::getId, Function.identity()));
        Map<Long, String> names = withEngineerName
                ? engineerNamesOf(page.getRecords().stream()
                        .map(EngineerChangeRequest::getEngineerId).collect(Collectors.toSet()))
                : Map.of();
        List<ChangeRequestDto> dtos = page.getRecords().stream()
                .map(r -> toDto(r, approvals.get(r.getApprovalRequestId()), names.get(r.getEngineerId())))
                .toList();
        Page<ChangeRequestDto> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(dtos);
        return result;
    }

    private Map<Long, String> engineerNamesOf(Set<Long> engineerIds) {
        if (engineerIds.isEmpty()) {
            return Map.of();
        }
        return engineerMapper.selectBatchIds(engineerIds).stream()
                .collect(Collectors.toMap(Engineer::getId,
                        e -> e.getFullName() == null ? "" : e.getFullName()));
    }

    private String engineerNameOf(Long engineerId) {
        if (engineerId == null) {
            return null;
        }
        Engineer engineer = engineerMapper.selectById(engineerId);
        return engineer == null ? null : engineer.getFullName();
    }

    private ChangeRequestDto toDto(EngineerChangeRequest request, ApprovalRequest approval, String engineerName) {
        String approvalStatus = approval == null ? null : approval.getStatus();
        boolean unappliedApproved = STATUS_APPROVED.equals(request.getStatus())
                && request.getAppliedAt() == null;
        return new ChangeRequestDto(request.getId(), request.getRequestType(), request.getStatus(),
                request.getPayloadJson(), request.getDiffJson(), request.getApprovalRequestId(), approvalStatus,
                request.getAppliedAt(), unappliedApproved, request.getCreatedAt(), engineerName);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("変更申請JSONのシリアライズに失敗しました", e);
        }
    }

    private int value(Integer version) {
        return version == null ? 0 : version;
    }
}
