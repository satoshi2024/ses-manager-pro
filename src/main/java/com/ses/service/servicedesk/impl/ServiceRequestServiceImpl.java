package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
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

        LocalDateTime now = LocalDateTime.now(clock);
        String requestNo = generateRequestNo(now);
        Long effectiveActorId = isPortal ? portalUserId : (actorUserId != null ? actorUserId : SecurityUtils.currentUserId());

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
            LocalDateTime responseDeadline = slaCalculator.calculateDeadline(now, policy.getResponseTimeHours(), policy, null, null, null);
            LocalDateTime resolveDeadline = slaCalculator.calculateDeadline(now, policy.getResolveTimeHours(), policy, null, null, null);

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
                .actorType(isPortal ? "PORTAL_USER" : "INTERNAL_USER")
                .actorId(effectiveActorId != null ? effectiveActorId : 0L)
                .actorName(isPortal ? resolvePortalUserName(portalUserId) : resolveUserName(effectiveActorId))
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

        existing.setContactId(req.getContactId());
        existing.setContractId(req.getContractId());
        existing.setProjectId(req.getProjectId());
        existing.setEngineerId(req.getEngineerId());
        existing.setCategory(req.getCategory());
        existing.setPriority(req.getPriority());
        existing.setSubject(req.getSubject());
        existing.setDescription(req.getDescription());
        existing.setOwnerUserId(req.getOwnerUserId());
        existing.setUpdatedBy(SecurityUtils.currentUserId());
        existing.setUpdatedAt(LocalDateTime.now(clock));

        serviceRequestMapper.updateById(existing);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, ServiceRequestStatusChangeRequest req, Long actorId, String actorType, String actorName) {
        ServiceRequest existing = serviceRequestMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }
        if ("INTERNAL_USER".equals(actorType) && dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(existing.getCustomerId());
        }

        String fromStatus = existing.getStatus();
        String toStatus = req.getToStatus();
        if (fromStatus.equals(toStatus)) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int currentRound = existing.getReopenCount() == null ? 1 : (existing.getReopenCount() + 1);

        // SLA クロック取得
        ServiceSlaClock clockRow = slaClockMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, existing.getId())
                        .eq(ServiceSlaClock::getRoundNo, currentRound)
        );

        // ステータス別状態遷移処理
        switch (toStatus) {
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
                    if ("PAUSED".equals(clockRow.getStatus()) && clockRow.getLastPausedAt() != null) {
                        int pausedMinutes = (int) ChronoUnit.MINUTES.between(clockRow.getLastPausedAt(), now);
                        int total = (clockRow.getTotalPauseMinutes() != null ? clockRow.getTotalPauseMinutes() : 0) + pausedMinutes;
                        clockRow.setTotalPauseMinutes(total);
                        clockRow.setLastPausedAt(null);
                        clockRow.setStatus("RUNNING");

                        ServiceSlaPolicy policy = slaPolicyMapper.selectById(clockRow.getPolicyId());
                        if (policy != null && clockRow.getResolveDeadline() != null) {
                            LocalDateTime extended = slaCalculator.calculateExtendedDeadline(
                                    clockRow.getResolveDeadline(), pausedMinutes, policy, null, null, null);
                            clockRow.setResolveDeadline(extended);
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
            case "RECEIVED":
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
                        LocalDateTime responseDeadline = slaCalculator.calculateDeadline(now, policy.getResponseTimeHours(), policy, null, null, null);
                        LocalDateTime resolveDeadline = slaCalculator.calculateDeadline(now, policy.getResolveTimeHours(), policy, null, null, null);

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
                }
                break;

            default:
                throw BusinessException.of(400, "無効なステータスです: " + toStatus);
        }

        existing.setStatus(toStatus);
        existing.setUpdatedBy("INTERNAL_USER".equals(actorType) ? actorId : null);
        existing.setUpdatedAt(now);
        serviceRequestMapper.updateById(existing);

        if (clockRow != null && !"RECEIVED".equals(toStatus)) {
            clockRow.setUpdatedAt(now);
            slaClockMapper.updateById(clockRow);
        }

        Long effectiveActorId = actorId != null ? actorId : SecurityUtils.currentUserId();
        String effectiveActorName = StringUtils.hasText(actorName) ? actorName
                : ("PORTAL_USER".equals(actorType) ? resolvePortalUserName(effectiveActorId) : resolveUserName(effectiveActorId));

        // 状態変更監査イベント記録（追記専用）
        ServiceStateEvent event = ServiceStateEvent.builder()
                .serviceRequestId(existing.getId())
                .roundNo(currentRound)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .reason(req.getReason())
                .actorType(actorType)
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
        ServiceRequest existing = serviceRequestMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }
        if (!isPortal && dataScopeService.isScoped()) {
            dataScopeService.assertAllowedCustomer(existing.getCustomerId());
        }

        String visibility = isPortal ? "PORTAL_VISIBLE" : (StringUtils.hasText(req.getVisibility()) ? req.getVisibility() : "PORTAL_VISIBLE");
        if (isPortal && !"PORTAL_VISIBLE".equals(visibility)) {
            visibility = "PORTAL_VISIBLE";
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Long effectiveAuthorId = actorId != null ? actorId : (isPortal ? null : SecurityUtils.currentUserId());
        String effectiveAuthorName = StringUtils.hasText(authorName) ? authorName
                : (isPortal ? resolvePortalUserName(effectiveAuthorId) : resolveUserName(effectiveAuthorId));

        ServiceComment comment = ServiceComment.builder()
                .serviceRequestId(existing.getId())
                .authorType(authorType)
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
            serviceRequestMapper.updateById(existing);

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
                slaClockMapper.updateById(clockRow);
            }
        }

        // ポータル顧客からの返信時、WAITING_CUSTOMER であれば自動的に IN_PROGRESS に復帰
        if (isPortal && "WAITING_CUSTOMER".equals(existing.getStatus())) {
            changeStatus(existing.getId(),
                    ServiceRequestStatusChangeRequest.builder().toStatus("IN_PROGRESS").reason("顧客返信により自動再開").build(),
                    effectiveAuthorId, authorType, effectiveAuthorName);
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
