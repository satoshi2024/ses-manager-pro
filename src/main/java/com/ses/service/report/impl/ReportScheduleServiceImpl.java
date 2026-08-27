package com.ses.service.report.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.report.ReportScheduleCreateRequest;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.entity.ReportSchedule;
import com.ses.entity.ReportTemplateVersion;
import com.ses.mapper.ReportScheduleMapper;
import com.ses.mapper.ReportTemplateVersionMapper;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.report.ReportScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** scheduleを管理し、二重実行防止用lock keyをDB一意制約へ保存する。 */
@Service
@RequiredArgsConstructor
public class ReportScheduleServiceImpl implements ReportScheduleService {

    private static final String TENANT_ID = "default";
    private static final String TIMEZONE = "Asia/Tokyo";
    private final ReportScheduleMapper scheduleMapper;
    private final ReportTemplateVersionMapper templateVersionMapper;
    private final OrganizationScopeService organizationScopeService;
    private final ObjectMapper objectMapper;

    @Override
    public List<ReportSchedule> list() {
        QueryWrapper<ReportSchedule> query = new QueryWrapper<ReportSchedule>()
                .eq("tenant_id", TENANT_ID);
        if ("マネージャー".equals(SecurityUtils.currentRole())) {
            Long userId = SecurityUtils.currentUserId();
            if (userId == null) throw BusinessException.of(403, "error.managementReport.roleDenied");
            query.eq("scope_owner_id", userId);
        } else if (!"管理者".equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "error.managementReport.roleDenied");
        }
        return scheduleMapper.selectList(query.orderByAsc("id"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportSchedule create(ReportScheduleCreateRequest request) {
        if (request == null || request.getTemplateVersionId() == null
                || request.getCronExpression() == null || request.getCronExpression().isBlank()) {
            throw BusinessException.of(400, "error.managementReport.scheduleInvalid");
        }
        String cron = request.getCronExpression().trim();
        try {
            CronExpression.parse(cron);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.of(400, "error.managementReport.scheduleInvalid");
        }
        String role = SecurityUtils.currentRole();
        if (!"管理者".equals(role) && !"マネージャー".equals(role)) {
            throw BusinessException.of(403, "error.managementReport.roleDenied");
        }
        ReportTemplateVersion version = templateVersionMapper.selectById(request.getTemplateVersionId());
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw BusinessException.of(400, "error.managementReport.templateVersionNotPublished");
        }
        ZoneId zone = ZoneId.of(TIMEZONE);
        LocalDateTime next = request.getNextRunAt();
        if (next == null) {
            LocalDateTime now = LocalDateTime.now(zone);
            var nextOccurrence = CronExpression.parse(cron).next(now.atZone(zone));
            if (nextOccurrence == null) {
                throw BusinessException.of(400, "error.managementReport.scheduleInvalid");
            }
            next = nextOccurrence.toLocalDateTime();
        }
        ReportSchedule schedule = new ReportSchedule();
        schedule.setTenantId(TENANT_ID);
        schedule.setTemplateVersionId(request.getTemplateVersionId());
        schedule.setCronExpression(cron);
        schedule.setTimezoneId(TIMEZONE);
        schedule.setEnabled(0);
        schedule.setLockKey("management-report:" + UUID.randomUUID());
        schedule.setNextRunAt(next);
        ReportScopeSnapshot scope = resolveScope(LocalDate.now(ZoneId.of(TIMEZONE)));
        schedule.setScopeOwnerType(scope.getOwnerType());
        schedule.setScopeOwnerId(scope.getOwnerId());
        schedule.setOrganizationScopeJson(scope.getJson());
        schedule.setScopePolicyVersion(scope.getPolicyVersion());
        schedule.setScopeHash(scope.getHash());
        schedule.setFailureCount(0);
        Long currentUserId = SecurityUtils.currentUserId();
        schedule.setCreatedBy(currentUserId);
        schedule.setUpdatedBy(currentUserId);
        scheduleMapper.insert(schedule);
        return schedule;
    }

    private ReportScopeSnapshot resolveScope(LocalDate asOf) {
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return buildScope("COMPANY", null, true, List.of(), List.of(),
                    List.of(), List.of(), List.of());
        }
        Long ownerId = SecurityUtils.currentUserId();
        if (!"マネージャー".equals(role) || ownerId == null) {
            throw BusinessException.of(403, "error.managementReport.roleDenied");
        }
        return buildScope("ORGANIZATION", ownerId, false,
                sorted(organizationScopeService.allowedOrganizationIds(asOf)),
                sorted(organizationScopeService.allowedDirectUserIds(asOf)),
                sorted(organizationScopeService.allowedEngineerIds(asOf)),
                sorted(organizationScopeService.allowedContractIds(asOf)),
                sorted(organizationScopeService.allowedInvoiceIds(asOf)));
    }

    private ReportScopeSnapshot buildScope(String ownerType, Long ownerId, boolean companyWide,
                                           List<Long> organizationIds, List<Long> directUserIds,
                                           List<Long> engineerIds, List<Long> contractIds,
                                           List<Long> invoiceIds) {
        try {
            LinkedHashMap<String, Object> map = new LinkedHashMap<>();
            map.put("ownerType", ownerType);
            map.put("ownerId", ownerId);
            map.put("companyWide", companyWide);
            map.put("organizationIds", organizationIds);
            map.put("directUserIds", directUserIds);
            map.put("engineerIds", engineerIds);
            map.put("contractIds", contractIds);
            map.put("invoiceIds", invoiceIds);
            map.put("policyVersion", "scope-policy-approved-1");
            map.put("sessionIndependent", true);
            String json = objectMapper.writeValueAsString(map);
            return new ReportScopeSnapshot(ownerType, ownerId, companyWide, organizationIds,
                    directUserIds, "scope-policy-approved-1", json, sha256(json),
                    engineerIds, contractIds, invoiceIds);
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.managementReport.scopeSnapshotInvalid");
        }
    }

    private List<Long> sorted(Set<Long> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256を利用できません", ex);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportSchedule setEnabled(Long scheduleId, boolean enabled) {
        if (!"管理者".equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "error.managementReport.adminRequired");
        }
        ReportSchedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) throw BusinessException.of(404, "error.managementReport.scheduleNotFound");
        schedule.setEnabled(enabled ? 1 : 0);
        schedule.setUpdatedBy(SecurityUtils.currentUserId());
        scheduleMapper.updateById(schedule);
        return schedule;
    }
}
