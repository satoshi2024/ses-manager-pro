package com.ses.service.servicedesk.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.exception.BusinessException;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
    private final ServiceSlaCalculator slaCalculator;
    private final DataScopeService dataScopeService;

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
        if (customer == null) {
            throw BusinessException.of(404, "指定された顧客が見つかりません");
        }

        if (!isPortal) {
            dataScopeService.assertAllowedCustomer(req.getCustomerId());
        }

        LocalDateTime now = LocalDateTime.now();
        String requestNo = generateRequestNo(now);

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
                .createdBy(isPortal ? null : actorUserId)
                .version(0)
                .createdAt(now)
                .updatedAt(now)
                .build();

        serviceRequestMapper.insert(serviceRequest);

        // SLA ポリシー取得・初期SLA計時作成
        ServiceSlaPolicy policy = getActivePolicy(req.getPriority());
        if (policy != null) {
            LocalDateTime responseDeadline = slaCalculator.calculateDeadline(now, policy.getResponseTimeHours(), policy);
            LocalDateTime resolveDeadline = slaCalculator.calculateDeadline(now, policy.getResolveTimeHours(), policy);

            ServiceSlaClock clock = ServiceSlaClock.builder()
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
            slaClockMapper.insert(clock);
        }

        // 初期監査イベント記録
        ServiceStateEvent event = ServiceStateEvent.builder()
                .serviceRequestId(serviceRequest.getId())
                .roundNo(1)
                .fromStatus(null)
                .toStatus("RECEIVED")
                .reason("新規起票")
                .actorType(isPortal ? "PORTAL_USER" : "INTERNAL_USER")
                .actorId(isPortal ? portalUserId : actorUserId)
                .actorName(isPortal ? "ポータル利用者" : resolveUserName(actorUserId))
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
        dataScopeService.assertAllowedCustomer(req.getCustomerId());

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
    public Page<ServiceRequestDto> searchInternalRequests(int page, int size, String keyword, String status, String priority, String category, Long customerId) {
        Page<ServiceRequest> mpPage = new Page<>(page, size);
        LambdaQueryWrapper<ServiceRequest> wrapper = new LambdaQueryWrapper<>();

        if (customerId != null) {
            dataScopeService.assertAllowedCustomer(customerId);
            wrapper.eq(ServiceRequest::getCustomerId, customerId);
        } else {
            // DataScope の適用
            Set<Long> allowedCustomerIds = dataScopeService.allowedCustomerIds();
            if (allowedCustomerIds != null) {
                if (allowedCustomerIds.isEmpty()) {
                    return new Page<>(page, size, 0);
                }
                wrapper.in(ServiceRequest::getCustomerId, allowedCustomerIds);
            }
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

        wrapper.orderByDesc(ServiceRequest::getCreatedAt);

        Page<ServiceRequest> result = serviceRequestMapper.selectPage(mpPage, wrapper);
        List<ServiceRequestDto> dtoList = result.getRecords().stream()
                .map(this::convertToInternalDto)
                .collect(Collectors.toList());

        Page<ServiceRequestDto> dtoPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(dtoList);
        return dtoPage;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PortalServiceRequestDto> searchPortalRequests(int page, int size, String keyword, String status, Long customerId) {
        Page<ServiceRequest> mpPage = new Page<>(page, size);
        LambdaQueryWrapper<ServiceRequest> wrapper = new LambdaQueryWrapper<>();

        wrapper.eq(ServiceRequest::getCustomerId, customerId);

        if (StringUtils.hasText(status)) {
            wrapper.eq(ServiceRequest::getStatus, status);
        }
        if (StringUtils.hasText(keyword)) {
            wrapper.and(w -> w.like(ServiceRequest::getRequestNo, keyword)
                    .or().like(ServiceRequest::getSubject, keyword)
                    .or().like(ServiceRequest::getDescription, keyword));
        }

        wrapper.orderByDesc(ServiceRequest::getCreatedAt);

        Page<ServiceRequest> result = serviceRequestMapper.selectPage(mpPage, wrapper);
        List<PortalServiceRequestDto> dtoList = result.getRecords().stream()
                .map(this::convertToPortalDto)
                .collect(Collectors.toList());

        Page<PortalServiceRequestDto> dtoPage = new Page<>(result.getCurrent(), result.getSize(), result.getTotal());
        dtoPage.setRecords(dtoList);
        return dtoPage;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRequest(Long id, ServiceRequestUpdateRequest req) {
        ServiceRequest existing = serviceRequestMapper.selectById(id);
        if (existing == null) {
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }
        dataScopeService.assertAllowedCustomer(existing.getCustomerId());

        if (StringUtils.hasText(req.getCategory())) {
            if (!VALID_CATEGORIES.contains(req.getCategory())) {
                throw BusinessException.of(400, "無効なカテゴリです: " + req.getCategory());
            }
            existing.setCategory(req.getCategory());
        }
        if (StringUtils.hasText(req.getPriority())) {
            if (!VALID_PRIORITIES.contains(req.getPriority())) {
                throw BusinessException.of(400, "無効な優先度です: " + req.getPriority());
            }
            existing.setPriority(req.getPriority());
        }
        if (StringUtils.hasText(req.getChannel())) {
            existing.setChannel(req.getChannel());
        }
        if (StringUtils.hasText(req.getSubject())) {
            existing.setSubject(req.getSubject());
        }
        if (StringUtils.hasText(req.getDescription())) {
            existing.setDescription(req.getDescription());
        }
        if (req.getOwnerUserId() != null) {
            existing.setOwnerUserId(req.getOwnerUserId());
        }
        if (req.getContactId() != null) {
            existing.setContactId(req.getContactId());
        }
        if (req.getContractId() != null) {
            existing.setContractId(req.getContractId());
        }
        if (req.getProjectId() != null) {
            existing.setProjectId(req.getProjectId());
        }
        if (req.getEngineerId() != null) {
            existing.setEngineerId(req.getEngineerId());
        }
        if (req.getVersion() != null) {
            existing.setVersion(req.getVersion());
        }

        existing.setUpdatedAt(LocalDateTime.now());
        int updated = serviceRequestMapper.updateById(existing);
        if (updated == 0) {
            throw BusinessException.of(409, "他のユーザーによって更新されました。画面を再読み込みしてください。");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changeStatus(Long id, ServiceRequestStatusChangeRequest req, Long actorId, String actorType, String actorName) {
        ServiceRequest request = serviceRequestMapper.selectById(id);
        if (request == null) {
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }

        String fromStatus = request.getStatus();
        String toStatus = req.getToStatus();

        if (Objects.equals(fromStatus, toStatus)) {
            return;
        }

        validateStateTransition(fromStatus, toStatus);

        LocalDateTime now = LocalDateTime.now();
        int currentRound = request.getReopenCount() == null ? 1 : (request.getReopenCount() + 1);

        ServiceSlaClock currentClock = slaClockMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, id)
                        .eq(ServiceSlaClock::getRoundNo, currentRound)
        );

        if ("IN_PROGRESS".equals(toStatus)) {
            if (request.getFirstResponseAt() == null) {
                request.setFirstResponseAt(now);
            }
            if (currentClock != null) {
                if (currentClock.getFirstRespondedAt() == null) {
                    currentClock.setFirstRespondedAt(now);
                    currentClock.setResponseBreached(now.isAfter(currentClock.getResponseDeadline()));
                }
                // もし前状態が WAITING_CUSTOMER (PAUSED) だった場合は再開
                if ("WAITING_CUSTOMER".equals(fromStatus) && currentClock.getLastPausedAt() != null) {
                    int pauseMinutes = (int) ChronoUnit.MINUTES.between(currentClock.getLastPausedAt(), now);
                    currentClock.setTotalPauseMinutes(
                            (currentClock.getTotalPauseMinutes() == null ? 0 : currentClock.getTotalPauseMinutes()) + pauseMinutes
                    );
                    ServiceSlaPolicy policy = slaPolicyMapper.selectById(currentClock.getPolicyId());
                    currentClock.setResolveDeadline(slaCalculator.calculateExtendedDeadline(currentClock.getResolveDeadline(), pauseMinutes, policy));
                    currentClock.setLastPausedAt(null);
                    currentClock.setStatus("RUNNING");
                }
                slaClockMapper.updateById(currentClock);
            }
        } else if ("WAITING_CUSTOMER".equals(toStatus)) {
            if (currentClock != null && "RUNNING".equals(currentClock.getStatus())) {
                currentClock.setStatus("PAUSED");
                currentClock.setLastPausedAt(now);
                slaClockMapper.updateById(currentClock);
            }
        } else if ("RESOLVED".equals(toStatus)) {
            request.setResolvedAt(now);
            if (currentClock != null) {
                currentClock.setResolvedAt(now);
                currentClock.setResolveBreached(now.isAfter(currentClock.getResolveDeadline()));
                currentClock.setStatus("COMPLETED");
                slaClockMapper.updateById(currentClock);
            }
        } else if ("CLOSED".equals(toStatus)) {
            request.setClosedAt(now);
            if (currentClock != null && !"COMPLETED".equals(currentClock.getStatus())) {
                currentClock.setStatus("COMPLETED");
                slaClockMapper.updateById(currentClock);
            }
        } else if ("REOPENED".equals(toStatus)) {
            // 再オープン処理: 新ラウンド作成
            request.setReopenedAt(now);
            int newRound = currentRound + 1;
            request.setReopenCount(newRound - 1);

            // 新ラウンドの SLA Clock 作成
            ServiceSlaPolicy policy = getActivePolicy(request.getPriority());
            if (policy != null) {
                LocalDateTime responseDeadline = slaCalculator.calculateDeadline(now, policy.getResponseTimeHours(), policy);
                LocalDateTime resolveDeadline = slaCalculator.calculateDeadline(now, policy.getResolveTimeHours(), policy);

                ServiceSlaClock newClock = ServiceSlaClock.builder()
                        .serviceRequestId(request.getId())
                        .roundNo(newRound)
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
            // 再オープン後の状態は IN_PROGRESS
            toStatus = "IN_PROGRESS";
        }

        request.setStatus(toStatus);
        request.setUpdatedAt(now);
        if (req.getVersion() != null) {
            request.setVersion(req.getVersion());
        }

        int updated = serviceRequestMapper.updateById(request);
        if (updated == 0) {
            throw BusinessException.of(409, "他のユーザーによって状態が更新されました");
        }

        // 状態変更監査イベント記録
        ServiceStateEvent event = ServiceStateEvent.builder()
                .serviceRequestId(request.getId())
                .roundNo(currentRound)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .reason(req.getReason())
                .actorType(actorType)
                .actorId(actorId)
                .actorName(actorName)
                .createdAt(now)
                .build();
        stateEventMapper.insert(event);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ServiceCommentDto addComment(Long id, ServiceCommentCreateRequest req, Long actorId, String authorType, String authorName, boolean isPortal) {
        ServiceRequest request = serviceRequestMapper.selectById(id);
        if (request == null) {
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }

        if (!isPortal) {
            dataScopeService.assertAllowedCustomer(request.getCustomerId());
        }

        String visibility = isPortal ? "PORTAL_VISIBLE" : (StringUtils.hasText(req.getVisibility()) ? req.getVisibility() : "PORTAL_VISIBLE");
        LocalDateTime now = LocalDateTime.now();

        ServiceComment comment = ServiceComment.builder()
                .serviceRequestId(id)
                .authorType(authorType)
                .authorId(actorId)
                .authorName(authorName)
                .visibility(visibility)
                .commentText(req.getCommentText())
                .createdAt(now)
                .updatedAt(now)
                .build();
        commentMapper.insert(comment);

        // 顧客ポータルからの返信で、かつステータスが WAITING_CUSTOMER の場合、自動的に IN_PROGRESS へ復帰（SLA Resume）
        if (isPortal && "WAITING_CUSTOMER".equals(request.getStatus())) {
            ServiceRequestStatusChangeRequest statusReq = ServiceRequestStatusChangeRequest.builder()
                    .toStatus("IN_PROGRESS")
                    .reason("顧客ポータルからの返信による自動再開")
                    .build();
            changeStatus(id, statusReq, actorId, authorType, authorName);
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
                .build();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void submitCsat(Long id, PortalCsatCreateRequest req, Long customerId, Long portalUserId) {
        ServiceRequest request = serviceRequestMapper.selectById(id);
        if (request == null || !Objects.equals(request.getCustomerId(), customerId)) {
            throw BusinessException.of(404, "指定されたリクエストが見つかりません");
        }

        if (!"RESOLVED".equals(request.getStatus()) && !"CLOSED".equals(request.getStatus())) {
            throw BusinessException.of(400, "解決または終了したリクエストのみ評価が可能です");
        }

        CustomerCsat existing = csatMapper.selectOne(
                new LambdaQueryWrapper<CustomerCsat>().eq(CustomerCsat::getServiceRequestId, id)
        );
        if (existing != null) {
            throw BusinessException.of(409, "既にアンケート回答済みです");
        }

        CustomerCsat csat = CustomerCsat.builder()
                .serviceRequestId(id)
                .customerId(customerId)
                .portalUserId(portalUserId)
                .score(req.getScore())
                .feedbackComment(req.getFeedbackComment())
                .answeredAt(LocalDateTime.now())
                .build();
        csatMapper.insert(csat);
    }

    private void validateStateTransition(String from, String to) {
        boolean valid = switch (from) {
            case "RECEIVED" -> Set.of("IN_PROGRESS", "WAITING_CUSTOMER", "CLOSED").contains(to);
            case "IN_PROGRESS" -> Set.of("WAITING_CUSTOMER", "RESOLVED", "CLOSED").contains(to);
            case "WAITING_CUSTOMER" -> Set.of("IN_PROGRESS", "RESOLVED", "CLOSED").contains(to);
            case "RESOLVED" -> Set.of("CLOSED", "REOPENED").contains(to);
            case "CLOSED" -> "REOPENED".equals(to);
            case "REOPENED" -> "IN_PROGRESS".equals(to);
            default -> false;
        };

        if (!valid) {
            throw BusinessException.of(400, String.format("ステータス遷移が不正です: %s -> %s", from, to));
        }
    }

    private ServiceSlaPolicy getActivePolicy(String priority) {
        return slaPolicyMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaPolicy>()
                        .eq(ServiceSlaPolicy::getPriority, priority)
                        .eq(ServiceSlaPolicy::getStatus, "ACTIVE")
        );
    }

    private synchronized String generateRequestNo(LocalDateTime now) {
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

        ServiceSlaClock clock = slaClockMapper.selectOne(
                new LambdaQueryWrapper<ServiceSlaClock>()
                        .eq(ServiceSlaClock::getServiceRequestId, req.getId())
                        .eq(ServiceSlaClock::getRoundNo, currentRound)
        );

        ServiceSlaClockDto clockDto = null;
        if (clock != null) {
            ServiceSlaPolicy policy = slaPolicyMapper.selectById(clock.getPolicyId());
            clockDto = ServiceSlaClockDto.builder()
                    .id(clock.getId())
                    .serviceRequestId(clock.getServiceRequestId())
                    .roundNo(clock.getRoundNo())
                    .policyId(clock.getPolicyId())
                    .policyName(policy != null ? policy.getName() : null)
                    .responseDeadline(clock.getResponseDeadline())
                    .resolveDeadline(clock.getResolveDeadline())
                    .firstRespondedAt(clock.getFirstRespondedAt())
                    .responseBreached(clock.getResponseBreached())
                    .resolvedAt(clock.getResolvedAt())
                    .resolveBreached(clock.getResolveBreached())
                    .totalPauseMinutes(clock.getTotalPauseMinutes())
                    .lastPausedAt(clock.getLastPausedAt())
                    .status(clock.getStatus())
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

        return ServiceRequestDto.builder()
                .id(req.getId())
                .requestNo(req.getRequestNo())
                .customerId(req.getCustomerId())
                .customerName(customer != null ? customer.getCompanyName() : null)
                .contactId(req.getContactId())
                .contactName(contact != null ? contact.getName() : null)
                .contractId(req.getContractId())
                .contractCode(contract != null ? contract.getContractNo() : null)
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
                .version(req.getVersion())
                .createdAt(req.getCreatedAt())
                .updatedAt(req.getUpdatedAt())
                .currentSlaClock(clockDto)
                .comments(commentDtos)
                .csatScore(csat != null ? csat.getScore() : null)
                .csatComment(csat != null ? csat.getFeedbackComment() : null)
                .build();
    }

    private PortalServiceRequestDto convertToPortalDto(ServiceRequest req) {
        // ポータル用: visibility='PORTAL_VISIBLE' のコメントのみ抽出（INTERNALメモは構造的に除外）
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
                .createdAt(req.getCreatedAt())
                .updatedAt(req.getUpdatedAt())
                .comments(commentDtos)
                .csatScore(csat != null ? csat.getScore() : null)
                .csatComment(csat != null ? csat.getFeedbackComment() : null)
                .csatAnswerable(answerable)
                .build();
    }

    private String resolveUserName(Long userId) {
        if (userId == null) return "システム";
        SysUser u = sysUserMapper.selectById(userId);
        return u != null ? u.getRealName() : "ユーザー#" + userId;
    }
}
