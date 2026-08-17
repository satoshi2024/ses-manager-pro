package com.ses.service.oneonone.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.PageUtils;
import com.ses.common.util.SecurityUtils;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.OneOnOneRequest;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OneOnOneRequestMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.DocumentService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import com.ses.service.oneonone.OneOnOneRequestService;
import com.ses.service.security.AuthorizationService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 1on1サービス実装（T092 / design §5/§6.2/§6.3）。
 * 母集団: 要員=本人のみ。HR/管理者=全件（private note可視）。マネージャー=組織scope配下（private不可視）。
 * 営業=担当要員（private不可視）。
 * private_noteはHRおよび明示designationされた管理者のみ閲覧・更新可能（非HR/一般管理者はnullマスク）。
 */
@Service
@RequiredArgsConstructor
public class OneOnOneRequestServiceImpl implements OneOnOneRequestService {

    private static final int MAX_CANDIDATES = 5;
    private static final int MAX_NOTE_LENGTH = 2000;
    private static final Set<String> COUNTERPART_ROLES = Set.of("営業", "マネージャー", "HR");

    private final OneOnOneRequestMapper oneOnOneMapper;
    private final EngineerMapper engineerMapper;
    private final SysUserMapper sysUserMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final EngineerAccountLinkService accountLinkService;
    private final EngineerSalesService engineerSalesService;
    private final OrganizationScopeService organizationScopeService;
    private final AuthorizationService authorizationService;
    private final DocumentService documentService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ----------------------------------------------------------------
    // 本人
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<OneOnOneDto> pageOwn(Long engineerId, String status, long current, long size) {
        LambdaQueryWrapper<OneOnOneRequest> query = new LambdaQueryWrapper<OneOnOneRequest>()
                .eq(OneOnOneRequest::getEngineerId, engineerId)
                .orderByDesc(OneOnOneRequest::getId);
        if (status != null && !status.isBlank()) {
            query.eq(OneOnOneRequest::getStatus, status);
        }
        Page<OneOnOneRequest> page = oneOnOneMapper.selectPage(PageUtils.safePage(current, size), query);
        return toDtoPage(page, false);
    }

    @Override
    @Transactional(readOnly = true)
    public OneOnOneDto detailOwn(Long engineerId, Long id) {
        OneOnOneRequest request = requireOwned(engineerId, id);
        return toDto(request, false);
    }

    @Override
    @Transactional
    public OneOnOneDto create(Long engineerId, Long counterpartUserId, List<LocalDate> candidateDates) {
        if (counterpartUserId == null) {
            throw BusinessException.of(400, "error.oneOnOne.counterpartRequired");
        }
        SysUser counterpart = sysUserMapper.selectById(counterpartUserId);
        if (counterpart == null) {
            throw BusinessException.of(404, "error.user.notFound");
        }
        if (!COUNTERPART_ROLES.contains(counterpart.getRole())) {
            throw BusinessException.of(400, "error.oneOnOne.invalidCounterpartRole");
        }
        if (counterpart.getStatus() == null || counterpart.getStatus() != 1) {
            throw BusinessException.of(400, "error.user.inactive");
        }
        assertCounterpartRelationship(engineerId, counterpart);
        if (candidateDates == null || candidateDates.isEmpty() || candidateDates.size() > MAX_CANDIDATES) {
            throw BusinessException.of(400, "error.oneOnOne.invalidDates");
        }
        List<LocalDate> normalized = candidateDates.stream().sorted().distinct().toList();
        LocalDate today = LocalDate.now(clock);
        if (normalized.size() != candidateDates.size() || normalized.stream().anyMatch(d -> d.isBefore(today))) {
            throw BusinessException.of(400, "error.oneOnOne.invalidDates");
        }
        OneOnOneRequest request = OneOnOneRequest.builder()
                .engineerId(engineerId)
                .counterpartUserId(counterpartUserId)
                .candidateDatesJson(writeJson(normalized))
                .status(STATUS_REQUESTED)
                .build();
        oneOnOneMapper.insert(request);
        return toDto(request, false);
    }

    private void assertCounterpartRelationship(Long engineerId, SysUser counterpart) {
        String role = counterpart.getRole();
        if ("HR".equals(role)) {
            return; // HRは相談窓口として常に選択可
        }
        if ("営業".equals(role)) {
            boolean isAssignedSales = engineerSalesService.list(new LambdaQueryWrapper<com.ses.entity.EngineerSales>()
                    .eq(com.ses.entity.EngineerSales::getEngineerId, engineerId)
                    .eq(com.ses.entity.EngineerSales::getSalesUserId, counterpart.getId())
                    .isNull(com.ses.entity.EngineerSales::getReleasedAt))
                    .size() > 0;
            if (!isAssignedSales) {
                throw BusinessException.of(400, "error.oneOnOne.notAssignedSales");
            }
            return;
        }
        if ("マネージャー".equals(role)) {
            LocalDate asOf = LocalDate.now(clock);
            Engineer engineer = engineerMapper.selectById(engineerId);
            Long userId = linkedUserId(engineerId);
            boolean directManaged = false;
            if (userId != null) {
                directManaged = userOrganizationMapper.selectActiveByManagerUserId(counterpart.getId(), asOf)
                        .stream().anyMatch(uo -> uo.getUserId().equals(userId));
            }
            boolean orgManaged = false;
            if (!directManaged && engineer != null && engineer.getOrganizationId() != null) {
                Long managerOrgId = userOrganizationMapper.selectPrimaryOrganizationId(counterpart.getId(), asOf);
                orgManaged = (managerOrgId != null && managerOrgId.equals(engineer.getOrganizationId()));
            }
            if (!directManaged && !orgManaged) {
                throw BusinessException.of(400, "error.oneOnOne.notAssignedManager");
            }
            return;
        }
        throw BusinessException.of(400, "error.oneOnOne.invalidCounterpart");
    }

    @Override
    @Transactional
    public OneOnOneDto cancelOwn(Long engineerId, Long id) {
        OneOnOneRequest request = requireOwned(engineerId, id);
        if (!STATUS_REQUESTED.equals(request.getStatus())) {
            throw BusinessException.of(400, "error.oneOnOne.invalidTransition",
                    request.getStatus(), STATUS_CANCELLED);
        }
        casStatus(request, STATUS_CANCELLED);
        return toDto(requireOwned(engineerId, id), false);
    }

    // ----------------------------------------------------------------
    // 管理
    // ----------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Page<OneOnOneDto> pageManagement(String engineerName, String status, long current, long size) {
        Scope scope = managementScope();
        if (scope.engineerIds() != null && scope.engineerIds().isEmpty()) {
            return new Page<>(current <= 0 ? 1 : current, size <= 0 ? PageUtils.DEFAULT_PAGE_SIZE : size);
        }
        LambdaQueryWrapper<OneOnOneRequest> query = new LambdaQueryWrapper<OneOnOneRequest>()
                .orderByDesc(OneOnOneRequest::getId);
        if (scope.engineerIds() != null) {
            query.in(OneOnOneRequest::getEngineerId, scope.engineerIds());
        }
        if (status != null && !status.isBlank()) {
            query.eq(OneOnOneRequest::getStatus, status);
        }
        if (engineerName != null && !engineerName.isBlank()) {
            Set<Long> nameIds = engineerMapper.selectList(new LambdaQueryWrapper<Engineer>()
                            .like(Engineer::getFullName, engineerName.trim()))
                    .stream().map(Engineer::getId).collect(Collectors.toSet());
            if (nameIds.isEmpty()) {
                return new Page<>(current <= 0 ? 1 : current, size <= 0 ? PageUtils.DEFAULT_PAGE_SIZE : size);
            }
            if (scope.engineerIds() != null) {
                nameIds.retainAll(scope.engineerIds());
                if (nameIds.isEmpty()) {
                    return new Page<>(current <= 0 ? 1 : current, size <= 0 ? PageUtils.DEFAULT_PAGE_SIZE : size);
                }
            }
            query.in(OneOnOneRequest::getEngineerId, nameIds);
        }
        Page<OneOnOneRequest> page = oneOnOneMapper.selectPage(PageUtils.safePage(current, size), query);
        return toDtoPage(page, scope.withPrivateNote());
    }

    @Override
    @Transactional(readOnly = true)
    public OneOnOneDto detailManagement(Long id) {
        OneOnOneRequest request = require(id);
        Scope scope = managementScope();
        assertScope(request, scope);
        return toDto(request, scope.withPrivateNote());
    }

    @Override
    @Transactional
    public OneOnOneDto schedule(Long id, LocalDate scheduledAt) {
        OneOnOneRequest request = require(id);
        assertManagementAction(request);
        if (scheduledAt == null || scheduledAt.isBefore(LocalDate.now(clock))) {
            throw BusinessException.of(400, "error.oneOnOne.invalidScheduledDate");
        }
        int updated = oneOnOneMapper.update(null, new UpdateWrapper<OneOnOneRequest>()
                .eq("id", id).eq("status", STATUS_REQUESTED)
                .set("status", STATUS_SCHEDULED)
                .set("scheduled_at", scheduledAt)
                .set("updated_at", LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return toDto(require(id), managementScope().withPrivateNote());
    }

    @Override
    @Transactional
    public OneOnOneDto complete(Long id, String employeeVisibleNote) {
        OneOnOneRequest request = require(id);
        assertManagementAction(request);
        if (employeeVisibleNote != null && employeeVisibleNote.length() > MAX_NOTE_LENGTH) {
            throw BusinessException.of(400, "error.oneOnOne.noteTooLong");
        }
        if (!STATUS_SCHEDULED.equals(request.getStatus())) {
            throw BusinessException.of(400, "error.oneOnOne.invalidTransition",
                    request.getStatus(), STATUS_DONE);
        }
        int updated = oneOnOneMapper.update(null, new UpdateWrapper<OneOnOneRequest>()
                .eq("id", id).eq("status", STATUS_SCHEDULED)
                .set("status", STATUS_DONE)
                .set("employee_visible_note", employeeVisibleNote == null ? null : employeeVisibleNote.trim())
                .set("updated_at", LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
        return toDto(require(id), managementScope().withPrivateNote());
    }

    @Override
    @Transactional
    public OneOnOneDto cancel(Long id, String reason) {
        OneOnOneRequest request = require(id);
        assertManagementAction(request);
        if (STATUS_DONE.equals(request.getStatus())) {
            throw BusinessException.of(400, "error.oneOnOne.invalidTransition",
                    request.getStatus(), STATUS_CANCELLED);
        }
        casStatus(request, STATUS_CANCELLED);
        return toDto(require(id), managementScope().withPrivateNote());
    }

    @Override
    @Transactional
    public OneOnOneDto savePrivateNote(Long id, String note) {
        OneOnOneRequest request = require(id);
        if (!canAccessPrivateNote()) {
            throw BusinessException.of(403, "error.accessDenied");
        }
        if (note == null || note.trim().isEmpty()) {
            throw BusinessException.of(400, "error.oneOnOne.noteRequired");
        }
        if (note.length() > MAX_NOTE_LENGTH) {
            throw BusinessException.of(400, "error.oneOnOne.noteTooLong");
        }
        // confidential相談は文書台帳(PRIVATE_NOTE)へ保存し、通常DTOへはprivate_note_refのみを渡す（design §6.2）。
        String businessKey = "ONE_ON_ONE_PRIVATE:" + id;
        Long documentId = request.getPrivateNoteRef() == null
                ? null : Long.valueOf(request.getPrivateNoteRef());
        if (documentId == null) {
            com.ses.entity.Document document = documentService.registerGenerated(
                    com.ses.dto.document.DocumentRegisterRequest.builder()
                            .documentType("PRIVATE_NOTE")
                            .title("1on1相談メモ #" + id)
                            .sourceType("GENERATED")
                            .direction("INTERNAL")
                            .counterpartyType("INTERNAL")
                            .transactionDate(LocalDate.now(clock))
                            .businessKey(businessKey)
                            .versionDiscriminator("v1")
                            .originalName("private-note-" + id + ".txt")
                            .contentType("text/plain;charset=UTF-8")
                            .build(),
                    new java.io.ByteArrayInputStream(note.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            documentId = document.getId();
        } else {
            documentService.addVersion(documentId,
                    com.ses.dto.document.DocumentRegisterRequest.builder()
                            .documentType("PRIVATE_NOTE")
                            .title("1on1相談メモ #" + id)
                            .sourceType("GENERATED")
                            .direction("INTERNAL")
                            .counterpartyType("INTERNAL")
                            .transactionDate(LocalDate.now(clock))
                            .businessKey(businessKey)
                            .versionDiscriminator("v" + (System.currentTimeMillis() % 1_000_000))
                            .originalName("private-note-" + id + ".txt")
                            .contentType("text/plain;charset=UTF-8")
                            .changeReason("1on1相談メモ更新")
                            .build(),
                    new java.io.ByteArrayInputStream(note.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        oneOnOneMapper.update(null, new UpdateWrapper<OneOnOneRequest>()
                .eq("id", id)
                .set("private_note_ref", String.valueOf(documentId))
                .set("updated_at", LocalDateTime.now(clock)));
        return toDto(require(id), true);
    }

    // ----------------------------------------------------------------
    // 内部
    // ----------------------------------------------------------------

    private boolean canAccessPrivateNote() {
        String role = SecurityUtils.currentRole();
        if ("HR".equals(role)) {
            return true;
        }
        if ("管理者".equals(role)) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            return authorizationService == null || authorizationService.isAllowed(auth, "one-on-one.confidential");
        }
        return false;
    }

    /** 管理画面の母集団（design §6.2）: HR/管理者=全件+private可視、マネージャー=組織scope、営業=担当要員。 */
    private Scope managementScope() {
        String role = SecurityUtils.currentRole();
        switch (role == null ? "" : role) {
            case "HR" -> {
                return new Scope(null, true);
            }
            case "管理者" -> {
                return new Scope(null, canAccessPrivateNote());
            }
            case "マネージャー" -> {
                if (organizationScopeService.hasFullAccess()) {
                    return new Scope(null, false);
                }
                Set<Long> allowed = organizationScopeService.allowedEngineerIds(LocalDate.now(clock));
                return new Scope(allowed == null ? Set.of() : new HashSet<>(allowed), false);
            }
            case "営業" -> {
                Set<Long> own = engineerSalesService.list(new LambdaQueryWrapper<com.ses.entity.EngineerSales>()
                                .eq(com.ses.entity.EngineerSales::getSalesUserId, SecurityUtils.currentUserId())
                                .isNull(com.ses.entity.EngineerSales::getReleasedAt))
                        .stream().map(com.ses.entity.EngineerSales::getEngineerId).collect(Collectors.toSet());
                return new Scope(own.isEmpty() ? Set.of() : own, false);
            }
            default -> throw BusinessException.of(403, "error.accessDenied");
        }
    }

    private void assertManagementAction(OneOnOneRequest request) {
        Scope scope = managementScope();
        assertScope(request, scope);
        String role = SecurityUtils.currentRole();
        // 日程確定/実施済/取消は担当営業・上長（counterpart）またはHR/管理者/マネージャー
        if ("営業".equals(role)) {
            if (!Objects.equals(request.getCounterpartUserId(), SecurityUtils.currentUserId())) {
                throw BusinessException.of(403, "error.oneOnOne.notCounterpart");
            }
        }
    }

    private void assertScope(OneOnOneRequest request, Scope scope) {
        if (scope.engineerIds() == null) {
            return;
        }
        if (request.getEngineerId() == null || !scope.engineerIds().contains(request.getEngineerId())) {
            throw BusinessException.of(404, "error.organization.scope.notFound");
        }
    }

    private void casStatus(OneOnOneRequest request, String target) {
        int updated = oneOnOneMapper.update(null, new UpdateWrapper<OneOnOneRequest>()
                .eq("id", request.getId()).eq("status", request.getStatus())
                .set("status", target)
                .set("updated_at", LocalDateTime.now(clock)));
        if (updated != 1) {
            throw BusinessException.of(409, "error.common.optimisticLock");
        }
    }

    private OneOnOneRequest requireOwned(Long engineerId, Long id) {
        if (id == null || engineerId == null) {
            throw BusinessException.of(404, "error.oneOnOne.notFound");
        }
        OneOnOneRequest request = oneOnOneMapper.selectOne(new LambdaQueryWrapper<OneOnOneRequest>()
                .eq(OneOnOneRequest::getId, id)
                .eq(OneOnOneRequest::getEngineerId, engineerId));
        if (request == null) {
            throw BusinessException.of(404, "error.oneOnOne.notFound");
        }
        return request;
    }

    private OneOnOneRequest require(Long id) {
        OneOnOneRequest request = id == null ? null : oneOnOneMapper.selectById(id);
        if (request == null) {
            throw BusinessException.of(404, "error.oneOnOne.notFound");
        }
        return request;
    }

    private Page<OneOnOneDto> toDtoPage(Page<OneOnOneRequest> page, boolean withPrivateNote) {
        Set<Long> engineerIds = page.getRecords().stream().map(OneOnOneRequest::getEngineerId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> userIds = page.getRecords().stream().map(OneOnOneRequest::getCounterpartUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> engineerNames = engineerIds.isEmpty() ? Map.of()
                : engineerMapper.selectBatchIds(engineerIds).stream()
                .collect(Collectors.toMap(Engineer::getId,
                        e -> e.getFullName() == null ? "" : e.getFullName()));
        Map<Long, String> userNames = userIds.isEmpty() ? Map.of()
                : sysUserMapper.selectBatchIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId,
                        u -> u.getRealName() == null ? "" : u.getRealName()));
        List<OneOnOneDto> dtos = page.getRecords().stream()
                .map(r -> toDto(r, withPrivateNote, engineerNames.get(r.getEngineerId()),
                        userNames.get(r.getCounterpartUserId())))
                .toList();
        Page<OneOnOneDto> result = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        result.setRecords(dtos);
        return result;
    }

    private OneOnOneDto toDto(OneOnOneRequest request, boolean withPrivateNote) {
        return toDto(request, withPrivateNote,
                engineerNameOf(request.getEngineerId()), userNameOf(request.getCounterpartUserId()));
    }

    private OneOnOneDto toDto(OneOnOneRequest request, boolean withPrivateNote, String engineerName, String counterpartName) {
        List<LocalDate> dates = readDates(request.getCandidateDatesJson());
        return new OneOnOneDto(request.getId(), request.getEngineerId(), engineerName,
                request.getCounterpartUserId(), counterpartName, dates, request.getScheduledAt(),
                request.getStatus(), request.getEmployeeVisibleNote(),
                withPrivateNote ? request.getPrivateNoteRef() : null, request.getCreatedAt());
    }

    private String engineerNameOf(Long engineerId) {
        if (engineerId == null) {
            return null;
        }
        Engineer engineer = engineerMapper.selectById(engineerId);
        return engineer == null ? null : engineer.getFullName();
    }

    private String userNameOf(Long userId) {
        if (userId == null) {
            return null;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user == null ? null : user.getRealName();
    }

    private List<LocalDate> readDates(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("1on1 JSONのシリアライズに失敗しました", e);
        }
    }

    private Long linkedUserId(Long engineerId) {
        EngineerAccountLink link = accountLinkService.findByEngineerId(engineerId);
        return link == null ? null : link.getSysUserId();
    }

    private record Scope(Set<Long> engineerIds, boolean withPrivateNote) {
    }
}
