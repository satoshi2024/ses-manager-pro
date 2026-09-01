package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.portal.PortalCsatCreateRequest;
import com.ses.dto.portal.PortalServiceCommentDto;
import com.ses.dto.portal.PortalServiceRequestDto;
import com.ses.dto.servicedesk.ServiceCommentCreateRequest;
import com.ses.dto.servicedesk.ServiceCommentDto;
import com.ses.dto.servicedesk.ServiceRequestCreateRequest;
import com.ses.dto.servicedesk.ServiceRequestDto;
import com.ses.dto.servicedesk.ServiceRequestStatusChangeRequest;
import com.ses.dto.servicedesk.ServiceRequestUpdateRequest;
import com.ses.dto.servicedesk.ServiceSlaClockDto;
import com.ses.entity.Contract;
import com.ses.entity.Customer;
import com.ses.entity.CustomerContact;
import com.ses.entity.CustomerCsat;
import com.ses.entity.Engineer;
import com.ses.entity.PortalUser;
import com.ses.entity.Project;
import com.ses.entity.ServiceComment;
import com.ses.entity.ServiceRequest;
import com.ses.entity.ServiceSlaClock;
import com.ses.entity.ServiceSlaPolicy;
import com.ses.entity.ServiceStateEvent;
import com.ses.entity.SysUser;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.CustomerCsatMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ServiceCommentMapper;
import com.ses.mapper.ServiceRequestMapper;
import com.ses.mapper.ServiceSlaClockMapper;
import com.ses.mapper.ServiceSlaPolicyMapper;
import com.ses.mapper.ServiceStateEventMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.DataScopeService;
import com.ses.service.servicedesk.ServiceRequestService;
import com.ses.service.servicedesk.ServiceSlaCalculator;
import com.ses.service.servicedesk.ServiceDeskExecutionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceRequestServiceImpl implements ServiceRequestService {

    private final ServiceRequestMapper serviceRequestMapper;
    private final ServiceSlaPolicyMapper slaPolicyMapper;
    private final ServiceSlaClockMapper slaClockMapper;
    private final ServiceCommentMapper commentMapper;
    private final ServiceStateEventMapper stateEventMapper;
    private final CustomerCsatMapper csatMapper;
    private final CustomerMapper customerMapper;
    private final CustomerContactMapper contactMapper;
    private final ContractMapper contractMapper;
    private final ProjectMapper projectMapper;
    private final EngineerMapper engineerMapper;
    private final SysUserMapper sysUserMapper;
    private final PortalUserMapper portalUserMapper;
    private final ServiceSlaCalculator slaCalculator;
    private final DataScopeService dataScopeService;
    private final Clock clock;

    private static final Set<String> VALID_CATEGORIES = Set.of(
            "CONTRACT", "BILLING", "ATTENDANCE", "QUALITY", "SYSTEM", "OTHER"
    );

    private static final Set<String> VALID_PRIORITIES = Set.of(
            "P0", "P1", "P2", "P3"
    );

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceRequest createRequest(ServiceRequestCreateRequest req, Long actorUserId, boolean isPortal, Long portalUserId) {
        return createRequest(req, isPortal, portalUserId,
                legacyContext(actorUserId, isPortal, portalUserId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceRequest createRequest(ServiceRequestCreateRequest req, boolean isPortal, Long portalUserId,
                                        ServiceDeskExecutionContext executionContext) {
        if (!VALID_CATEGORIES.contains(req.getCategory())) {
            throw BusinessException.of(400, "無効なカテゴリです: " + req.getCategory());
        }
        if (!VALID_PRIORITIES.contains(req.getPriority())) {
            throw BusinessException.of(400, "無効な優先度です: " + req.getPriority());
        }

        Customer customer = customerMapper.selectById(req.getCustomerId());
        if (customer == null || Integer.valueOf(1).equals(customer.getDeletedFlag())) {
            throw BusinessException.of(404, "指定された顧客が見つかりません");
        }

        if (!isPortal && dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(req.getCustomerId());
        }

        requireExecutionContext(executionContext);
        validateLinkedEntities(req.getCustomerId(), req.getContactId(), req.getContractId(),
                req.getProjectId(), req.getEngineerId());

        LocalDateTime now = executionContext.occurredAt().atZone(executionContext.zoneId()).toLocalDateTime();
        String requestNo = generateRequestNo(now);
        Long effectiveActorId = isPortal ? portalUserId : executionContext.actorId();

        ServiceRequest serviceRequest = ServiceRequest.builder()
                .requestNo(requestNo)
                .customerId(req.getCustomerId())
                .contactId(req.getContactId())
                .contractId(req.getContractId())
                .projectId(req.getProjectId())
                .engineerId(req.getEngineerId())
                .category(req.getCategory())
                .priority(req.getPriority())
                .channel(StringUtils.hasText(req.getChannel()) ? req.getChannel() : (isPortal ? "PORTAL" : "INTERNAL"))
                .subject(req.getSubject())
                .description(req.getDescription())
                .ownerUserId(req.getOwnerUserId())
                .status("RECEIVED")
                .reopenCount(0)
                .portalUserId(isPortal ? portalUserId : null)
                .createdBy(isPortal ? null : effectiveActorId)
                .version(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        serviceRequestMapper.insert(serviceRequest);

        // SLA ポリシー取得・初期SLA計時作成
        ServiceSlaPolicy policy = getActivePolicy(req.getPriority());
        if (policy != null) {
            LocalDateTime responseDeadline = slaCalculator.calculateDeadline(executionContext.occurredAt(),
                    policy.getResponseTimeHours(), policy, executionContext.organizationId(),
                    executionContext.legalEntityId(), executionContext.zoneId())
                    .atZone(executionContext.zoneId()).toLocalDateTime();
            LocalDateTime resolveDeadline = slaCalculator.calculateDeadline(executionContext.occurredAt(),
                    policy.getResolveTimeHours(), policy, executionContext.organizationId(),
                    executionContext.legalEntityId(), executionContext.zoneId())
                    .atZone(executionContext.zoneId()).toLocalDateTime();

            ServiceSlaClock slaClock = ServiceSlaClock.builder()
                    .serviceRequestId(serviceRequest.getId())
                    .roundNo(1)
                    .policyId(policy.getId())
                    .responseDeadline(responseDeadline)
                    .resolveDeadline(resolveDeadline)
                    .responseBreached(false)
                    .resolveBreached(false)
                    .totalPauseMinutes(0)
                    .status("RUNNING")
                    .version(0)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            slaClockMapper.insert(slaClock);
        }

        // 初期監査イベント記録
        ServiceStateEvent event = ServiceStateEvent.builder()
                .serviceRequestId(serviceRequest.getId())
                .roundNo(1)
                .fromStatus(null)
                .toStatus("RECEIVED")
                .reason("新規起票")
                .actorType(executionContext.actorType())
                .actorId(effectiveActorId != null ? effectiveActorId : 0L)
                .actorName(StringUtils.hasText(executionContext.actorName()) ? executionContext.actorName()
                        : (isPortal ? resolvePortalUserName(portalUserId) : resolveUserName(effectiveActorId)))
                .createdAt(now)
                .build();
        stateEventMapper.insert(event);

        return serviceRequest;
    }

    @Override
    @Transactional(readOnly = true)
    public ServiceRequestDto getInternalDetail(Long id) {
        ServiceRequest req = serviceRequestMapper.selectById(id);
        if (req == null) {
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(req.getCustomerId());
        }

        return convertToInternalDto(req);
    }

    @Override
    @Transactional(readOnly = true)
    public PortalServiceRequestDto getPortalDetail(Long id, Long customerId) {
        ServiceRequest req = serviceRequestMapper.selectById(id);
        if (req == null || !Objects.equals(req.getCustomerId(), customerId)) {
            // 他社または存在しない場合は404秘匿
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }

        return convertToPortalDto(req);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ServiceRequestDto> searchInternalRequests(int page, int size, String keyword, String status,
                                                          String priority, String category, Long customerId) {
        Page<ServiceRequest> mpPage = new Page<>(page, size);
        LambdaQueryWrapper<ServiceRequest> wrapper = new LambdaQueryWrapper<>();

        if (customerId != null) {
            if (dataScopeService.isScoped()) {
                dataScopeService.assertAllowedCustomer(customerId);
            }
            wrapper.eq(ServiceRequest::getCustomerId, customerId);
        } else if (dataScopeService.isScoped()) {
            Set<Long> allowed = dataScopeService.allowedCustomerIds();
            if (allowed == null || allowed.isEmpty()) {
                return new Page<>(page, size, 0);
            }
            wrapper.in(ServiceRequest::getCustomerId, allowed);
        }

        if (StringUtils.hasText(status)) {
            wrapper.eq(ServiceRequest::getStatus, status);
        }
        if (StringUtils.hasText(priority)) {
            wrapper.eq(ServiceRequest::getPriority, priority);
        }
        if (StringUtils.hasText(category)) {
            wrapper.eq(ServiceRequest::getCategory, category);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(ServiceRequest::getRequestNo, keyword)
                    .or().like(ServiceRequest::getSubject, keyword)
                    .or().like(ServiceRequest::getDescription, keyword));
        }

        wrapper.orderByDesc(ServiceRequest::getId);

        Page<ServiceRequest> result = serviceRequestMapper.selectPage(mpPage, wrapper);
        List<ServiceRequestDto> dtos = result.getRecords().stream()
                .map(this::convertToInternalDto)
                .collect(Collectors.toList());

        Page<ServiceRequestDto> dtoPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(dtos);
        return dtoPage;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PortalServiceRequestDto> searchPortalRequests(int page, int size, String keyword, String status, Long customerId) {
        if (customerId == null) {
            throw BusinessException.of(400, "顧客組織情報が必要です");
        }

        Page<ServiceRequest> mpPage = new Page<>(page, size);
        LambdaQueryWrapper<ServiceRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ServiceRequest::getCustomerId, customerId);

        if (StringUtils.hasText(status)) {
            wrapper.eq(ServiceRequest::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(ServiceRequest::getRequestNo, keyword)
                    .or().like(ServiceRequest::getSubject, keyword));
        }

        wrapper.orderByDesc(ServiceRequest::getId);

        Page<ServiceRequest> result = serviceRequestMapper.selectPage(mpPage, wrapper);
        List<PortalServiceRequestDto> dtos = result.getRecords().stream()
                .map(this::convertToPortalDto)
                .collect(Collectors.toList());

        Page<PortalServiceRequestDto> dtoPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(dtos);
        return dtoPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRequest(Long id, ServiceRequestUpdateRequest req) {
        ServiceRequest existing = serviceRequestMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }
        if (dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(existing.getCustomerId());
        }

        if (!VALID_CATEGORIES.contains(req.getCategory())) {
            throw BusinessException.of(400, "無効なカテゴリです: " + req.getCategory());
        }
        if (!VALID_PRIORITIES.contains(req.getPriority())) {
            throw BusinessException.of(400, "無効な優先度です: " + req.getPriority());
        }

        validateLinkedEntities(existing.getCustomerId(), req.getContactId(), req.getContractId(),
                req.getProjectId(), req.getEngineerId());

        int expectedVersion = req.getVersion() != null ? req.getVersion()
                : (existing.getVersion() != null ? existing.getVersion() : 0);
        int updated = serviceRequestMapper.update(null, new LambdaUpdateWrapper<ServiceRequest>()
                .eq(ServiceRequest::getId, id)
                .eq(ServiceRequest::getVersion, expectedVersion)
                .set(ServiceRequest::getContactId, req.getContactId())
                .set(ServiceRequest::getContractId, req.getContractId())
                .set(ServiceRequest::getProjectId, req.getProjectId())
                .set(ServiceRequest::getEngineerId, req.getEngineerId())
                .set(ServiceRequest::getCategory, req.getCategory())
                .set(ServiceRequest::getPriority, req.getPriority())
                .set(ServiceRequest::getSubject, req.getSubject())
                .set(ServiceRequest::getDescription, req.getDescription())
                .set(ServiceRequest::getOwnerUserId, req.getOwnerUserId())
                .set(ServiceRequest::getUpdatedBy, SecurityUtils.currentUserId())
                .set(ServiceRequest::getUpdatedAt, LocalDateTime.now(clock))
                .set(ServiceRequest::getVersion, expectedVersion + 1));
        if (updated != 1) {
            throw BusinessException.of(409, "サービスリクエストが更新済みです。再読込してください");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, ServiceRequestStatusChangeRequest req, Long actorId, String actorType, String actorName) {
        // 既存のservice直呼び出し互換。HTTP/明示context経路はversionを必須にする。
        if (req.getVersion() == null) {
            ServiceRequest current = serviceRequestMapper.selectById(id);
            if (current == null) {
                throw BusinessException.of(404, "指定されたリクエストが見つかりません");
            }
            req = ServiceRequestStatusChangeRequest.builder()
                    .toStatus(req.getToStatus())
                    .reason(req.getReason())
                    .version(current.getVersion() == null ? 0 : current.getVersion())
                    .organizationId(req.getOrganizationId())
                    .legalEntityId(req.getLegalEntityId())
                    .build();
        }
        changeStatus(id, req, legacyContext(actorId, "PORTAL_USER".equals(actorType), actorId,
                actorType, actorName, req.getOrganizationId(), req.getLegalEntityId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, ServiceRequestStatusChangeRequest req,
                             ServiceDeskExecutionContext executionContext) {
        requireExecutionContext(executionContext);
        if (req == null || req.getVersion() == null) {
            throw BusinessException.of(400, "サービスリクエストversionは必須です");
        }
        ServiceRequest existing = serviceRequestMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }
        if ("INTERNAL_USER".equals(executionContext.actorType()) && dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(existing.getCustomerId());
        }

        String fromStatus = existing.getStatus();
        String requestedStatus = req.getToStatus();
        if (!isAllowedTransition(fromStatus, requestedStatus)) {
            throw BusinessException.of(409, "許可されていないステータス遷移です: " + fromStatus + " -> " + requestedStatus);
        }
        // 同一状態への再送は成功扱いにせず、状態機械の辺として明示的に拒否する。
        // REOPENED は保存値が IN_PROGRESS になるため、正規化前に判定する必要がある。
        if (Objects.equals(fromStatus, requestedStatus)) {
            throw BusinessException.of(409, "同一ステータスへの遷移は許可されていません: " + fromStatus);
        }
        String toStatus = "REOPENED".equals(requestedStatus) ? "IN_PROGRESS" : requestedStatus;

        int expectedVersion = req.getVersion() != null ? req.getVersion()
                : (existing.getVersion() != null ? existing.getVersion() : 0);
        int currentVersion = existing.getVersion() != null ? existing.getVersion() : 0;
        if (expectedVersion != currentVersion) {
            throw BusinessException.of(409, "サービスリクエストが更新済みです。再読込してください");
        }

        LocalDateTime now = executionContext.occurredAt().atZone(executionContext.zoneId()).toLocalDateTime();
        int currentRound = existing.getReopenCount() == null ? 1 : (existing.getReopenCount() + 1);

        // SLA クロック取得
        ServiceSlaClock clockRow = slaClockMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, existing.getId())
                        .eq(ServiceSlaClock::getRoundNo, currentRound)
        );

        // WAITING_CUSTOMERを経由して解決・終了する場合も、停止区間を営業分だけ精算する。
        // 壁時計の経過時間を加算しないことがSLA停止の不変条件である。
        if (clockRow != null && "PAUSED".equals(clockRow.getStatus())
                && !"WAITING_CUSTOMER".equals(requestedStatus)) {
            resumePausedClock(clockRow, now, executionContext);
        }

        // ステータス別状態遷移処理
        switch (requestedStatus) {
            case "IN_PROGRESS":
                if (existing.getFirstResponseAt() == null) {
                    existing.setFirstResponseAt(now);
                }
                if (clockRow != null) {
                    if (clockRow.getFirstRespondedAt() == null) {
                        clockRow.setFirstRespondedAt(now);
                    if (clockRow.getResponseDeadline() != null && now.isAfter(clockRow.getResponseDeadline())) {
                            clockRow.setResponseBreached(true);
                        }
                    }
                }
                break;

            case "WAITING_CUSTOMER":
                if (clockRow != null && "RUNNING".equals(clockRow.getStatus())) {
                    clockRow.setStatus("PAUSED");
                    clockRow.setLastPausedAt(now);
                }
                break;

            case "RESOLVED":
                existing.setResolvedAt(now);
                if (clockRow != null) {
                    clockRow.setResolvedAt(now);
                    clockRow.setStatus("COMPLETED");
                    if (clockRow.getResolveDeadline() != null && now.isAfter(clockRow.getResolveDeadline())) {
                        clockRow.setResolveBreached(true);
                    }
                }
                break;

            case "CLOSED":
                existing.setClosedAt(now);
                if (clockRow != null && !"COMPLETED".equals(clockRow.getStatus())) {
                    clockRow.setStatus("COMPLETED");
                    if (clockRow.getResolvedAt() == null) {
                        clockRow.setResolvedAt(now);
                    }
                }
                break;

            case "REOPENED":
                // 再オープン処理
                if ("RESOLVED".equals(fromStatus) || "CLOSED".equals(fromStatus)) {
                    int newReopenCount = (existing.getReopenCount() != null ? existing.getReopenCount() : 0) + 1;
                    existing.setReopenCount(newReopenCount);
                    existing.setReopenedAt(now);
                    existing.setResolvedAt(null);
                    existing.setClosedAt(null);
                    toStatus = "IN_PROGRESS";

                    // 新規ラウンド SLA クロック作成
                    ServiceSlaPolicy policy = getActivePolicy(existing.getPriority());
                    if (policy != null) {
                        LocalDateTime responseDeadline = slaCalculator.calculateDeadline(executionContext.occurredAt(),
                                policy.getResponseTimeHours(), policy, executionContext.organizationId(),
                                executionContext.legalEntityId(), executionContext.zoneId())
                                .atZone(executionContext.zoneId()).toLocalDateTime();
                        LocalDateTime resolveDeadline = slaCalculator.calculateDeadline(executionContext.occurredAt(),
                                policy.getResolveTimeHours(), policy, executionContext.organizationId(),
                                executionContext.legalEntityId(), executionContext.zoneId())
                                .atZone(executionContext.zoneId()).toLocalDateTime();

                        ServiceSlaClock newClock = ServiceSlaClock.builder()
                                .serviceRequestId(existing.getId())
                                .roundNo(newReopenCount + 1)
                                .policyId(policy.getId())
                                .responseDeadline(responseDeadline)
                                .resolveDeadline(resolveDeadline)
                                .responseBreached(false)
                                .resolveBreached(false)
                                .totalPauseMinutes(0)
                                .status("RUNNING")
                                .version(0)
                                .createdAt(now)
                                .updatedAt(now)
                                .build();
                        slaClockMapper.insert(newClock);
                    }
                    // 旧ラウンドは履歴として不変。今回の状態変更では更新しない。
                    clockRow = null;
                }
                break;

            default:
                throw BusinessException.of(400, "無効なステータスです: " + toStatus);
        }

        existing.setStatus(toStatus);
        existing.setUpdatedBy("INTERNAL_USER".equals(executionContext.actorType()) ? executionContext.actorId() : null);
        existing.setUpdatedAt(now);
        LambdaUpdateWrapper<ServiceRequest> requestUpdate = new LambdaUpdateWrapper<ServiceRequest>()
                .eq(ServiceRequest::getId, existing.getId())
                .eq(ServiceRequest::getVersion, expectedVersion)
                .eq(ServiceRequest::getStatus, fromStatus)
                .set(ServiceRequest::getStatus, existing.getStatus())
                .set(ServiceRequest::getFirstResponseAt, existing.getFirstResponseAt())
                .set(ServiceRequest::getResolvedAt, existing.getResolvedAt())
                .set(ServiceRequest::getClosedAt, existing.getClosedAt())
                .set(ServiceRequest::getReopenedAt, existing.getReopenedAt())
                .set(ServiceRequest::getReopenCount, existing.getReopenCount())
                .set(ServiceRequest::getUpdatedBy, existing.getUpdatedBy())
                .set(ServiceRequest::getUpdatedAt, existing.getUpdatedAt())
                .set(ServiceRequest::getVersion, expectedVersion + 1);
        if (serviceRequestMapper.update(null, requestUpdate) != 1) {
            throw BusinessException.of(409, "サービスリクエストが更新済みです。状態イベントは記録されません");
        }
        existing.setVersion(expectedVersion + 1);

        if (clockRow != null && !"RECEIVED".equals(toStatus)) {
            clockRow.setUpdatedAt(now);
            int clockVersion = clockRow.getVersion() != null ? clockRow.getVersion() : 0;
            LambdaUpdateWrapper<ServiceSlaClock> clockUpdate = new LambdaUpdateWrapper<ServiceSlaClock>()
                    .eq(ServiceSlaClock::getId, clockRow.getId())
                    .eq(ServiceSlaClock::getVersion, clockVersion)
                    .set(ServiceSlaClock::getFirstRespondedAt, clockRow.getFirstRespondedAt())
                    .set(ServiceSlaClock::getResponseBreached, clockRow.getResponseBreached())
                    .set(ServiceSlaClock::getResolvedAt, clockRow.getResolvedAt())
                    .set(ServiceSlaClock::getResolveBreached, clockRow.getResolveBreached())
                    .set(ServiceSlaClock::getTotalPauseMinutes, clockRow.getTotalPauseMinutes())
                    .set(ServiceSlaClock::getLastPausedAt, clockRow.getLastPausedAt())
                    .set(ServiceSlaClock::getResolveDeadline, clockRow.getResolveDeadline())
                    .set(ServiceSlaClock::getStatus, clockRow.getStatus())
                    .set(ServiceSlaClock::getUpdatedAt, clockRow.getUpdatedAt())
                    .set(ServiceSlaClock::getVersion, clockVersion + 1);
            if (slaClockMapper.update(null, clockUpdate) != 1) {
                throw BusinessException.of(409, "SLAクロックが更新済みです。状態イベントは記録されません");
            }
            clockRow.setVersion(clockVersion + 1);
        }

        Long effectiveActorId = executionContext.actorId();
        String effectiveActorName = executionContext.actorName();

        // 状態変更監査イベント記録（追記専用）
        ServiceStateEvent event = ServiceStateEvent.builder()
                .serviceRequestId(existing.getId())
                .roundNo(currentRound)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .reason(req.getReason())
                .actorType(executionContext.actorType())
                .actorId(effectiveActorId != null ? effectiveActorId : 0L)
                .actorName(effectiveActorName)
                .createdAt(now)
                .build();
        stateEventMapper.insert(event);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceCommentDto addComment(Long id, ServiceCommentCreateRequest req, Long actorId,
                                       String authorType, String authorName, boolean isPortal) {
        return addComment(id, req, isPortal,
                legacyContext(actorId, isPortal, actorId, authorType, authorName, null, null));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceCommentDto addComment(Long id, ServiceCommentCreateRequest req, boolean isPortal,
                                        ServiceDeskExecutionContext executionContext) {
        requireExecutionContext(executionContext);
        ServiceRequest existing = serviceRequestMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }
        if (!isPortal && "INTERNAL_USER".equals(executionContext.actorType()) && dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(existing.getCustomerId());
        }

        String visibility = isPortal ? "PORTAL_VISIBLE" : (StringUtils.hasText(req.getVisibility()) ? req.getVisibility() : "PORTAL_VISIBLE");
        if (isPortal && !"PORTAL_VISIBLE".equals(visibility)) {
            visibility = "PORTAL_VISIBLE";
        }

        LocalDateTime now = executionContext.occurredAt().atZone(executionContext.zoneId()).toLocalDateTime();
        Long effectiveAuthorId = executionContext.actorId();
        String effectiveAuthorName = executionContext.actorName();

        ServiceComment comment = ServiceComment.builder()
                .serviceRequestId(existing.getId())
                .authorType(executionContext.actorType())
                .authorId(effectiveAuthorId != null ? effectiveAuthorId : 0L)
                .authorName(effectiveAuthorName)
                .visibility(visibility)
                .commentText(req.getCommentText())
                .createdAt(now)
                .updatedAt(now)
                .build();

        commentMapper.insert(comment);

        // 内部担当者が顧客公開コメントを投稿した際、未初回応答であれば初回応答日時を記録
        if (!isPortal && "PORTAL_VISIBLE".equals(visibility) && existing.getFirstResponseAt() == null) {
            existing.setFirstResponseAt(now);
            int requestVersion = existing.getVersion() == null ? 0 : existing.getVersion();
            int requestUpdated = serviceRequestMapper.update(null, new LambdaUpdateWrapper<ServiceRequest>()
                    .eq(ServiceRequest::getId, existing.getId())
                    .eq(ServiceRequest::getVersion, requestVersion)
                    .set(ServiceRequest::getFirstResponseAt, existing.getFirstResponseAt())
                    .set(ServiceRequest::getUpdatedAt, now)
                    .set(ServiceRequest::getVersion, requestVersion + 1));
            if (requestUpdated != 1) {
                throw BusinessException.of(409, "サービスリクエストが更新済みです。コメントは記録されません");
            }
            existing.setVersion(requestVersion + 1);

            int currentRound = existing.getReopenCount() == null ? 1 : (existing.getReopenCount() + 1);
            ServiceSlaClock clockRow = slaClockMapper.selectOne(
                    new LambdaQueryWrapper<ServiceSlaClock>()
                            .eq(ServiceSlaClock::getServiceRequestId, existing.getId())
                            .eq(ServiceSlaClock::getRoundNo, currentRound)
            );
            if (clockRow != null && clockRow.getFirstRespondedAt() == null) {
                clockRow.setFirstRespondedAt(now);
                if (clockRow.getResponseDeadline() != null && now.isAfter(clockRow.getResponseDeadline())) {
                    clockRow.setResponseBreached(true);
                }
                clockRow.setUpdatedAt(now);
                int clockVersion = clockRow.getVersion() == null ? 0 : clockRow.getVersion();
                int clockUpdated = slaClockMapper.update(null, new LambdaUpdateWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getId, clockRow.getId())
                        .eq(ServiceSlaClock::getVersion, clockVersion)
                        .set(ServiceSlaClock::getFirstRespondedAt, clockRow.getFirstRespondedAt())
                        .set(ServiceSlaClock::getResponseBreached, clockRow.getResponseBreached())
                        .set(ServiceSlaClock::getUpdatedAt, clockRow.getUpdatedAt())
                        .set(ServiceSlaClock::getVersion, clockVersion + 1));
                if (clockUpdated != 1) {
                    throw BusinessException.of(409, "SLAクロックが更新済みです。コメントは記録されません");
                }
            }
        }

        // ポータル顧客からの返信時、WAITING_CUSTOMER であれば自動的に IN_PROGRESS に復帰
        if (isPortal && "WAITING_CUSTOMER".equals(existing.getStatus())) {
            changeStatus(existing.getId(),
                    ServiceRequestStatusChangeRequest.builder()
                            .toStatus("IN_PROGRESS")
                            .reason("顧客返信により自動再開")
                            .version(existing.getVersion() == null ? 0 : existing.getVersion())
                            .organizationId(executionContext.organizationId())
                            .legalEntityId(executionContext.legalEntityId())
                            .build(),
                    executionContext);
        }

        return ServiceCommentDto.builder()
                .id(comment.getId())
                .serviceRequestId(comment.getServiceRequestId())
                .authorType(comment.getAuthorType())
                .authorId(comment.getAuthorId())
                .authorName(comment.getAuthorName())
                .visibility(comment.getVisibility())
                .commentText(comment.getCommentText())
                .createdAt(comment.getCreatedAt())
                .updatedAt(comment.getUpdatedAt())
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitCsat(Long id, PortalCsatCreateRequest req, Long customerId, Long portalUserId) {
        ServiceRequest existing = serviceRequestMapper.selectById(id);
        if (existing == null || !Objects.equals(existing.getCustomerId(), customerId)) {
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }

        if (!"RESOLVED".equals(existing.getStatus()) && !"CLOSED".equals(existing.getStatus())) {
            throw BusinessException.of(400, "CSAT回答は解決または完了済みのリクエストのみ可能です");
        }

        CustomerCsat existingCsat = csatMapper.selectOne(
                new LambdaQueryWrapper<CustomerCsat>().eq(CustomerCsat::getServiceRequestId, id)
        );
        if (existingCsat != null) {
            throw BusinessException.of(409, "このリクエストに対するCSAT回答は既に提出済みです");
        }

        CustomerCsat csat = CustomerCsat.builder()
                .serviceRequestId(existing.getId())
                .customerId(customerId)
                .portalUserId(portalUserId)
                .score(req.getScore())
                .feedbackComment(req.getFeedbackComment())
                .answeredAt(LocalDateTime.now(clock))
                .build();

        csatMapper.insert(csat);
    }

    private ServiceSlaPolicy getActivePolicy(String priority) {
        return slaPolicyMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaPolicy>()
                        .eq(ServiceSlaPolicy::getPriority, priority)
                        .eq(ServiceSlaPolicy::getStatus, "ACTIVE")
                        .orderByDesc(ServiceSlaPolicy::getId)
                        .last("LIMIT 1")
        );
    }

    private ServiceDeskExecutionContext legacyContext(Long actorId, boolean portal, Long portalUserId) {
        String effectiveActorType = portal ? "PORTAL_USER" : "INTERNAL_USER";
        Long effectiveActorId = portal ? portalUserId : actorId;
        String actorName = portal ? resolvePortalUserName(effectiveActorId) : resolveUserName(effectiveActorId);
        return legacyContext(effectiveActorId, portal, effectiveActorId, effectiveActorType, actorName, null, null);
    }

    private ServiceDeskExecutionContext legacyContext(Long actorId, boolean portal, Long ignored,
                                                      String actorType, String actorName,
                                                      Long organizationId, Long legalEntityId) {
        ZoneId zone = com.ses.service.accounting.AccountingTenantContextHolder.getZoneId();
        return new ServiceDeskExecutionContext(
                com.ses.service.accounting.AccountingTenantContextHolder.getCurrentTenantId(),
                zone, Instant.now(clock), organizationId, legalEntityId, actorId,
                actorType, StringUtils.hasText(actorName) ? actorName : "内部ユーザー",
                portal ? "PORTAL_REQUEST" : "INTERNAL_REQUEST");
    }

    private void requireExecutionContext(ServiceDeskExecutionContext context) {
        if (context == null) {
            throw BusinessException.of(400, "サービスデスクの実行コンテキストが必要です");
        }
    }

    private boolean isAllowedTransition(String fromStatus, String requestedStatus) {
        return switch (fromStatus) {
            case "RECEIVED" -> Set.of("IN_PROGRESS", "WAITING_CUSTOMER", "CLOSED").contains(requestedStatus);
            case "IN_PROGRESS" -> Set.of("WAITING_CUSTOMER", "RESOLVED", "CLOSED").contains(requestedStatus);
            case "WAITING_CUSTOMER" -> Set.of("IN_PROGRESS", "RESOLVED", "CLOSED").contains(requestedStatus);
            case "RESOLVED" -> Set.of("CLOSED", "REOPENED").contains(requestedStatus);
            case "CLOSED" -> "REOPENED".equals(requestedStatus);
            default -> false;
        };
    }

    private void resumePausedClock(ServiceSlaClock clockRow, LocalDateTime now,
                                   ServiceDeskExecutionContext executionContext) {
        if (clockRow.getLastPausedAt() == null) {
            clockRow.setStatus("RUNNING");
            return;
        }
        ServiceSlaPolicy policy = slaPolicyMapper.selectById(clockRow.getPolicyId());
        int pausedMinutes = slaCalculator.businessMinutesBetween(clockRow.getLastPausedAt(), now,
                policy, executionContext.organizationId(), executionContext.legalEntityId(), executionContext.zoneId());
        int total = (clockRow.getTotalPauseMinutes() != null ? clockRow.getTotalPauseMinutes() : 0) + pausedMinutes;
        clockRow.setTotalPauseMinutes(total);
        clockRow.setLastPausedAt(null);
        clockRow.setStatus("RUNNING");

        if (policy != null && clockRow.getResolveDeadline() != null) {
            LocalDateTime extended = slaCalculator.calculateExtendedDeadline(
                    clockRow.getResolveDeadline(), pausedMinutes, policy,
                    executionContext.organizationId(), executionContext.legalEntityId(), executionContext.zoneId());
            clockRow.setResolveDeadline(extended);
        }
    }

    /** 問い合わせに紐づく4種の業務対象が同一顧客に属することをservice境界で保証する。 */
    private void validateLinkedEntities(Long customerId, Long contactId, Long contractId,
                                        Long projectId, Long engineerId) {
        if (contactId != null) {
            CustomerContact contact = contactMapper.selectById(contactId);
            if (contact == null || !Objects.equals(customerId, contact.getCustomerId())) {
                throw BusinessException.of(400, "指定された顧客担当者は顧客と一致しません");
            }
        }
        Contract contract = null;
        if (contractId != null) {
            contract = contractMapper.selectById(contractId);
            if (contract == null || !Objects.equals(customerId, contract.getCustomerId())) {
                throw BusinessException.of(400, "指定された契約は顧客と一致しません");
            }
        }
        if (projectId != null) {
            Project project = projectMapper.selectById(projectId);
            if (project == null || !Objects.equals(customerId, project.getCustomerId())) {
                throw BusinessException.of(400, "指定された案件は顧客と一致しません");
            }
            if (contract != null && !Objects.equals(projectId, contract.getProjectId())) {
                throw BusinessException.of(400, "指定された案件は契約と一致しません");
            }
        }
        if (engineerId != null) {
            Engineer engineer = engineerMapper.selectById(engineerId);
            if (engineer == null) {
                throw BusinessException.of(400, "指定された要員が見つかりません");
            }
            if (contract != null && !Objects.equals(engineerId, contract.getEngineerId())) {
                throw BusinessException.of(400, "指定された要員は契約と一致しません");
            }
            if (contract == null && contractMapper.selectCount(new LambdaQueryWrapper<Contract>()
                    .eq(Contract::getCustomerId, customerId)
                    .eq(Contract::getEngineerId, engineerId)) == 0) {
                throw BusinessException.of(400, "指定された要員は顧客の契約に紐付いていません");
            }
        }
    }

    private String generateRequestNo(LocalDateTime now) {
        String prefix = "REQ-" + now.format(DateTimeFormatter.ofPattern("yyyyMM")) + "-";
        List<ServiceRequest> latest = serviceRequestMapper.selectList(
                new LambdaQueryWrapper<ServiceRequest>()
                        .likeRight(ServiceRequest::getRequestNo, prefix)
                        .orderByDesc(ServiceRequest::getRequestNo)
                        .last("LIMIT 1")
        );

        int seq = 1;
        if (!latest.isEmpty() && latest.get(0).getRequestNo() != null) {
            String lastNo = latest.get(0).getRequestNo();
            try {
                String seqStr = lastNo.substring(prefix.length());
                seq = Integer.parseInt(seqStr) + 1;
            } catch (Exception ignored) {
            }
        }
        return String.format("%s%04d", prefix, seq);
    }

    private ServiceRequestDto convertToInternalDto(ServiceRequest req) {
        int currentRound = req.getReopenCount() == null ? 1 : (req.getReopenCount() + 1);

        ServiceSlaClock clockRow = slaClockMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, req.getId())
                        .eq(ServiceSlaClock::getRoundNo, currentRound)
        );

        ServiceSlaClockDto clockDto = null;
        if (clockRow != null) {
            ServiceSlaPolicy policy = slaPolicyMapper.selectById(clockRow.getPolicyId());
            clockDto = ServiceSlaClockDto.builder()
                    .id(clockRow.getId())
                    .serviceRequestId(clockRow.getServiceRequestId())
                    .roundNo(clockRow.getRoundNo())
                    .policyId(clockRow.getPolicyId())
                    .policyName(policy != null ? policy.getName() : null)
                    .responseDeadline(clockRow.getResponseDeadline())
                    .resolveDeadline(clockRow.getResolveDeadline())
                    .firstRespondedAt(clockRow.getFirstRespondedAt())
                    .responseBreached(clockRow.getResponseBreached())
                    .resolvedAt(clockRow.getResolvedAt())
                    .resolveBreached(clockRow.getResolveBreached())
                    .totalPauseMinutes(clockRow.getTotalPauseMinutes())
                    .lastPausedAt(clockRow.getLastPausedAt())
                    .status(clockRow.getStatus())
                    .build();
        }

        List<ServiceComment> comments = commentMapper.selectList(
                new LambdaQueryWrapper<ServiceComment>()
                        .eq(ServiceComment::getServiceRequestId, req.getId())
                        .orderByAsc(ServiceComment::getCreatedAt)
        );

        List<ServiceCommentDto> commentDtos = comments.stream()
                .map(c -> ServiceCommentDto.builder()
                        .id(c.getId())
                        .serviceRequestId(c.getServiceRequestId())
                        .authorType(c.getAuthorType())
                        .authorId(c.getAuthorId())
                        .authorName(c.getAuthorName())
                        .visibility(c.getVisibility())
                        .commentText(c.getCommentText())
                        .createdAt(c.getCreatedAt())
                        .updatedAt(c.getUpdatedAt())
                        .build())
                .collect(Collectors.toList());

        CustomerCsat csat = csatMapper.selectOne(
                new LambdaQueryWrapper<CustomerCsat>().eq(CustomerCsat::getServiceRequestId, req.getId())
        );

        Customer customer = customerMapper.selectById(req.getCustomerId());
        CustomerContact contact = req.getContactId() != null ? contactMapper.selectById(req.getContactId()) : null;
        Contract contract = req.getContractId() != null ? contractMapper.selectById(req.getContractId()) : null;
        Project project = req.getProjectId() != null ? projectMapper.selectById(req.getProjectId()) : null;
        Engineer engineer = req.getEngineerId() != null ? engineerMapper.selectById(req.getEngineerId()) : null;
        SysUser ownerUser = req.getOwnerUserId() != null ? sysUserMapper.selectById(req.getOwnerUserId()) : null;
        SysUser creatorUser = req.getCreatedBy() != null ? sysUserMapper.selectById(req.getCreatedBy()) : null;

        return ServiceRequestDto.builder()
                .id(req.getId())
                .requestNo(req.getRequestNo())
                .customerId(req.getCustomerId())
                .customerName(customer != null ? customer.getCompanyName() : null)
                .contactId(req.getContactId())
                .contactName(contact != null ? contact.getName() : null)
                .contractId(req.getContractId())
                .contractNo(contract != null ? contract.getContractNo() : null)
                .projectId(req.getProjectId())
                .projectName(project != null ? project.getProjectName() : null)
                .engineerId(req.getEngineerId())
                .engineerName(engineer != null ? engineer.getFullName() : null)
                .category(req.getCategory())
                .priority(req.getPriority())
                .channel(req.getChannel())
                .subject(req.getSubject())
                .description(req.getDescription())
                .ownerUserId(req.getOwnerUserId())
                .ownerUserName(ownerUser != null ? ownerUser.getRealName() : null)
                .status(req.getStatus())
                .firstResponseAt(req.getFirstResponseAt())
                .resolvedAt(req.getResolvedAt())
                .closedAt(req.getClosedAt())
                .reopenedAt(req.getReopenedAt())
                .reopenCount(req.getReopenCount())
                .portalUserId(req.getPortalUserId())
                .createdBy(req.getCreatedBy())
                .createdByName(creatorUser != null ? creatorUser.getRealName() : null)
                .version(req.getVersion())
                .createdAt(req.getCreatedAt())
                .updatedAt(req.getUpdatedAt())
                .slaClock(clockDto)
                .comments(commentDtos)
                .csatScore(csat != null ? csat.getScore() : null)
                .csatComment(csat != null ? csat.getFeedbackComment() : null)
                .build();
    }

    private PortalServiceRequestDto convertToPortalDto(ServiceRequest req) {
        List<ServiceComment> comments = commentMapper.selectList(
                new LambdaQueryWrapper<ServiceComment>()
                        .eq(ServiceComment::getServiceRequestId, req.getId())
                        .eq(ServiceComment::getVisibility, "PORTAL_VISIBLE")
                        .orderByAsc(ServiceComment::getCreatedAt)
        );

        List<PortalServiceCommentDto> commentDtos = comments.stream()
                .map(c -> PortalServiceCommentDto.builder()
                        .id(c.getId())
                        .serviceRequestId(c.getServiceRequestId())
                        .authorType(c.getAuthorType())
                        .authorName("INTERNAL_USER".equals(c.getAuthorType()) ? "サポート担当" : c.getAuthorName())
                        .commentText(c.getCommentText())
                        .createdAt(c.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        CustomerCsat csat = csatMapper.selectOne(
                new LambdaQueryWrapper<CustomerCsat>().eq(CustomerCsat::getServiceRequestId, req.getId())
        );

        boolean isClosedOrResolved = "RESOLVED".equals(req.getStatus()) || "CLOSED".equals(req.getStatus());
        boolean answerable = isClosedOrResolved && csat == null;

        return PortalServiceRequestDto.builder()
                .id(req.getId())
                .requestNo(req.getRequestNo())
                .category(req.getCategory())
                .priority(req.getPriority())
                .subject(req.getSubject())
                .description(req.getDescription())
                .status(req.getStatus())
                .firstResponseAt(req.getFirstResponseAt())
                .resolvedAt(req.getResolvedAt())
                .closedAt(req.getClosedAt())
                .reopenCount(req.getReopenCount())
                .createdAt(req.getCreatedAt())
                .comments(commentDtos)
                .csatScore(csat != null ? csat.getScore() : null)
                .csatAnswerable(answerable)
                .build();
    }

    private String resolveUserName(Long userId) {
        if (userId == null) return "SYSTEM";
        SysUser u = sysUserMapper.selectById(userId);
        return u != null && u.getRealName() != null ? u.getRealName() : "ユーザー#" + userId;
    }

    private String resolvePortalUserName(Long portalUserId) {
        if (portalUserId == null) return "ポータル利用者";
        PortalUser pu = portalUserMapper.selectById(portalUserId);
        return pu != null && pu.getDisplayName() != null ? pu.getDisplayName() : "ポータル利用者";
    }
}
