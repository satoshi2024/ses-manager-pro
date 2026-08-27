package com.ses.service.report.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.LoginUser;
import com.ses.dto.accounting.ManagementAccountingSummaryDto;
import com.ses.dto.billing.CashFlowForecastDto;
import com.ses.dto.dashboard.DashboardSummaryDto;
import com.ses.dto.dashboard.UtilizationForecastDto;
import com.ses.dto.invoice.AgingReportDto;
import com.ses.dto.report.ReportGenerationCommand;
import com.ses.dto.report.ReportGenerationResult;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.dto.report.ReportSectionKey;
import com.ses.dto.salesperformance.SalesPerformanceDto;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSectionSnapshot;
import com.ses.entity.ReportTemplateVersion;
import com.ses.entity.SysUser;
import com.ses.mapper.ReportRunMapper;
import com.ses.mapper.ReportSectionSnapshotMapper;
import com.ses.mapper.ReportTemplateVersionMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.DashboardService;
import com.ses.service.InvoiceService;
import com.ses.service.ManagementAccountingService;
import com.ses.service.MonthlyClosingService;
import com.ses.service.SalesPerformanceService;
import com.ses.service.UtilizationForecastService;
import com.ses.service.billing.CashFlowForecastService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.report.ReportSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 正本serviceの戻り値をreport section snapshotへ固定する実装。
 * このクラスには金額の再計算、再丸め、report専用SQLを置かない。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportSnapshotServiceImpl implements ReportSnapshotService {

    private static final String TENANT_ID = "default";
    private static final String TIMEZONE = "Asia/Tokyo";
    private static final String SNAPSHOT_SCHEMA = "report-1.0";
    private static final String POLICY_VERSION = "scope-policy-approved-1";
    private static final String ADAPTER_VERSION = "scheduled-management-reporting-f2-1";

    private final ReportTemplateVersionMapper templateVersionMapper;
    private final ReportRunMapper runMapper;
    private final ReportSectionSnapshotMapper sectionMapper;
    private final SysUserMapper sysUserMapper;
    private final OrganizationScopeService organizationScopeService;
    private final MonthlyClosingService monthlyClosingService;
    private final DashboardService dashboardService;
    private final UtilizationForecastService utilizationForecastService;
    private final CashFlowForecastService cashFlowForecastService;
    private final ManagementAccountingService managementAccountingService;
    private final SalesPerformanceService salesPerformanceService;
    private final InvoiceService invoiceService;
    private final ObjectMapper objectMapper;
    private final ReportRecipientPreviewService recipientPreviewService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ReportGenerationResult generate(ReportGenerationCommand command) {
        validateCommand(command);
        if (command.systemPrincipal()) {
            return withExplicitPrincipal(command.principalUserId(),
                    () -> generateInternal(command));
        }
        requireReportRole();
        return generateInternal(command);
    }

    @Override
    public ReportRun findRun(Long runId) {
        ReportRun run = runMapper.selectById(runId);
        if (run == null) {
            throw BusinessException.of(404, "error.managementReport.runNotFound");
        }
        return run;
    }

    @Override
    public List<ReportSectionSnapshot> listSections(Long runId) {
        findRun(runId);
        return sectionMapper.selectList(new QueryWrapper<ReportSectionSnapshot>()
                .eq("run_id", runId)
                .orderByAsc("id"));
    }

    private ReportGenerationResult generateInternal(ReportGenerationCommand command) {
        YearMonth target = command.period();
        LocalDate periodFrom = target.atDay(1);
        LocalDate periodTo = target.atEndOfMonth();
        String cutoffKind = normalizeCutoff(command.cutoffKind());
        boolean confirmed = "MONTHLY_CLOSING".equals(cutoffKind);
        if (confirmed && !monthlyClosingService.isClosed(target.toString())) {
            throw BusinessException.of(400, "error.managementReport.closingRequired");
        }

        ReportTemplateVersion templateVersion = templateVersionMapper.selectById(command.templateVersionId());
        if (templateVersion == null || !"PUBLISHED".equals(templateVersion.getStatus())) {
            throw BusinessException.of(400, "error.managementReport.templateVersionNotPublished");
        }

        // generation直前に同一principalでrecipient scopeを再評価する。APIからhashが渡された場合は
        // previewとgenerationの間に権限・組織が変わっていないことも確認する。
        ReportRecipientPreviewResult preview = recipientPreviewService.preview(
                command.templateVersionId(), target);
        if (command.recipientPreviewHash() != null
                && !command.recipientPreviewHash().equals(preview.getPreviewHash())) {
            throw BusinessException.of(403, "error.managementReport.recipientPreviewStale");
        }

        LocalDateTime asOfAt = LocalDateTime.now(ZoneId.of(TIMEZONE));
        ReportScopeSnapshot scope = resolveScope(periodTo);
        String stableRunKey = buildRunKey(templateVersion, target, cutoffKind, scope, command);
        ReportRun run = runMapper.selectOne(new QueryWrapper<ReportRun>()
                .eq("tenant_id", TENANT_ID)
                .eq("run_key", stableRunKey));

        if (run != null && !command.explicitRegeneration()
                && "SUCCEEDED".equals(run.getStatus())) {
            return new ReportGenerationResult(run, listSectionsWithoutLookup(run.getId()), true);
        }
        if (run == null) {
            run = new ReportRun();
            run.setTenantId(TENANT_ID);
            run.setRunKey(stableRunKey);
            run.setTemplateId(templateVersion.getTemplateId());
            run.setTemplateVersionId(templateVersion.getId());
            run.setScheduleId(command.scheduleId());
            run.setPrincipalType("SYSTEM_PRINCIPAL");
            run.setPrincipalUserId(command.principalUserId() != null
                    ? command.principalUserId() : SecurityUtils.currentUserId());
            run.setScopeOwnerType(scope.getOwnerType());
            run.setScopeOwnerId(scope.getOwnerId());
            run.setOrganizationScopeJson(scope.getJson());
            run.setScopePolicyVersion(scope.getPolicyVersion());
            run.setScopeHash(scope.getHash());
            run.setPeriodFrom(periodFrom);
            run.setPeriodTo(periodTo);
            run.setCutoffKind(cutoffKind);
            run.setAsOfAt(asOfAt);
            run.setTimezoneId(TIMEZONE);
            run.setDataAsOfAt(asOfAt);
            run.setStatus("PENDING");
            run.setSnapshotSchemaVersion(SNAPSHOT_SCHEMA);
            run.setSourcePolicyHash(sha256("canonical-services-v1|" + POLICY_VERSION));
            run.setCreatedBy(SecurityUtils.currentUserId());
            runMapper.insert(run);
        }

        run.setStatus("RUNNING");
        run.setFailureCode(null);
        run.setFailureMessage(null);
        runMapper.updateById(run);

        List<String> sectionKeys = readSectionKeys(templateVersion.getSectionConfigJson());
        Map<String, JsonNode> sourceCache = new HashMap<>();
        boolean hasFailure = false;
        for (String sectionKey : sectionKeys) {
            ReportSectionSnapshot existing = findSection(run.getId(), sectionKey);
            if (existing != null && "SUCCEEDED".equals(existing.getSectionStatus())) {
                continue;
            }
            try {
                SectionValue value = loadSection(sectionKey, target, sourceCache);
                saveSection(run, existing, sectionKey, value, confirmed, asOfAt, periodFrom, periodTo);
            } catch (Exception ex) {
                hasFailure = true;
                saveFailedSection(run, existing, sectionKey, confirmed, asOfAt,
                        periodFrom, periodTo, ex);
            }
        }

        run.setStatus(hasFailure ? "PARTIAL" : "SUCCEEDED");
        run.setGeneratedAt(LocalDateTime.now(ZoneId.of(TIMEZONE)));
        if (hasFailure) {
            run.setFailureCode("SECTION_FAILED");
            run.setFailureMessage("1つ以上のsection生成に失敗したため配布を停止しました");
        }
        runMapper.updateById(run);
        return new ReportGenerationResult(run, listSectionsWithoutLookup(run.getId()), false);
    }

    private void saveSection(ReportRun run, ReportSectionSnapshot existing, String sectionKey,
                             SectionValue value, boolean confirmed, LocalDateTime asOfAt,
                             LocalDate periodFrom, LocalDate periodTo) {
        ReportSectionSnapshot snapshot = existing == null ? new ReportSectionSnapshot() : existing;
        snapshot.setTenantId(TENANT_ID);
        snapshot.setRunId(run.getId());
        snapshot.setSectionKey(sectionKey);
        snapshot.setSectionStatus("SUCCEEDED");
        snapshot.setFactType(value.factType());
        snapshot.setConfirmation(confirmed ? "確定" : "速報");
        snapshot.setPeriodFrom(periodFrom);
        snapshot.setPeriodTo(periodTo);
        snapshot.setCutoffKind(run.getCutoffKind());
        snapshot.setAsOfAt(asOfAt);
        snapshot.setDataAsOfAt(asOfAt);
        snapshot.setFreshnessStatus("FRESH");
        snapshot.setCanonicalService(value.canonicalService());
        snapshot.setCanonicalDto(value.canonicalDto());
        snapshot.setAdapterVersion(ADAPTER_VERSION);
        snapshot.setSourceRowCount(countRows(value.source()));
        snapshot.setSourceHash(sha256(toJson(value.source())));
        snapshot.setValueJson(toJson(value.value()));
        snapshot.setErrorCode(null);
        snapshot.setErrorMessage(null);
        snapshot.setSnapshotHash(sha256(sectionHashInput(snapshot)));
        snapshot.setAttemptCount(existing == null || existing.getAttemptCount() == null
                ? 1 : existing.getAttemptCount() + 1);
        if (existing == null) {
            sectionMapper.insert(snapshot);
        } else {
            sectionMapper.updateById(snapshot);
        }
    }

    private void saveFailedSection(ReportRun run, ReportSectionSnapshot existing, String sectionKey,
                                   boolean confirmed, LocalDateTime asOfAt, LocalDate periodFrom,
                                   LocalDate periodTo, Exception ex) {
        log.warn("[定期管理レポート] section生成失敗: runId={} section={}", run.getId(), sectionKey, ex);
        ReportSectionSnapshot snapshot = existing == null ? new ReportSectionSnapshot() : existing;
        snapshot.setTenantId(TENANT_ID);
        snapshot.setRunId(run.getId());
        snapshot.setSectionKey(sectionKey);
        snapshot.setSectionStatus("FAILED");
        snapshot.setFactType("未提供");
        snapshot.setConfirmation(confirmed ? "確定" : "速報");
        snapshot.setPeriodFrom(periodFrom);
        snapshot.setPeriodTo(periodTo);
        snapshot.setCutoffKind(run.getCutoffKind());
        snapshot.setAsOfAt(asOfAt);
        snapshot.setDataAsOfAt(asOfAt);
        snapshot.setFreshnessStatus("UNKNOWN");
        snapshot.setCanonicalService(null);
        snapshot.setCanonicalDto(null);
        snapshot.setAdapterVersion(ADAPTER_VERSION);
        snapshot.setSourceRowCount(0L);
        snapshot.setSourceHash(null);
        snapshot.setValueJson(null);
        snapshot.setErrorCode("SECTION_GENERATION_FAILED");
        snapshot.setErrorMessage("正本serviceの呼出に失敗しました");
        snapshot.setSnapshotHash(sha256(sectionKey + "|SECTION_GENERATION_FAILED|" + run.getId()));
        snapshot.setAttemptCount(existing == null || existing.getAttemptCount() == null
                ? 1 : existing.getAttemptCount() + 1);
        if (existing == null) {
            sectionMapper.insert(snapshot);
        } else {
            sectionMapper.updateById(snapshot);
        }
    }

    private SectionValue loadSection(String sectionKey, YearMonth target,
                                     Map<String, JsonNode> sourceCache) {
        return switch (sectionKey) {
            case ReportSectionKey.SALES, ReportSectionKey.GROSS_PROFIT -> {
                JsonNode source = sourceCache.computeIfAbsent("dashboard",
                        ignored -> objectMapper.valueToTree(dashboardService.getSummary(target.getYear())));
                yield new SectionValue(source, source.path("kpi"), "実績",
                        DashboardSummaryDto.class.getSimpleName(), DashboardSummaryDto.class.getName());
            }
            case ReportSectionKey.REVENUE_FORECAST -> {
                JsonNode source = sourceCache.computeIfAbsent("dashboard",
                        ignored -> objectMapper.valueToTree(dashboardService.getSummary(target.getYear())));
                yield new SectionValue(source, source.path("charts").path("revenue"), "予測",
                        DashboardSummaryDto.class.getSimpleName(), DashboardSummaryDto.class.getName());
            }
            case ReportSectionKey.UTILIZATION, ReportSectionKey.BENCH, ReportSectionKey.CONTRACT_RENEWAL_OUTLOOK -> {
                JsonNode source = sourceCache.computeIfAbsent("utilization",
                        ignored -> objectMapper.valueToTree(utilizationForecastService.getForecast(1)));
                JsonNode value = switch (sectionKey) {
                    case ReportSectionKey.UTILIZATION, ReportSectionKey.BENCH -> source.path("monthlyForecasts");
                    default -> source.path("rolloffEngineers");
                };
                yield new SectionValue(source, value, "予測",
                        UtilizationForecastDto.class.getSimpleName(), UtilizationForecastDto.class.getName());
            }
            case ReportSectionKey.MANAGEMENT_ACCOUNTING -> {
                JsonNode source = objectMapper.valueToTree(managementAccountingService.summary(target.toString()));
                yield new SectionValue(source, source, "実績",
                        ManagementAccountingSummaryDto.class.getSimpleName(), ManagementAccountingSummaryDto.class.getName());
            }
            case ReportSectionKey.CASH_FLOW, ReportSectionKey.BP_PAYMENT_PLAN -> {
                JsonNode source = sourceCache.computeIfAbsent("cash-flow",
                        ignored -> objectMapper.valueToTree(cashFlowForecastService.forecast(target, 1, null)));
                JsonNode value = ReportSectionKey.BP_PAYMENT_PLAN.equals(sectionKey)
                        ? source.path("months") : source;
                yield new SectionValue(source, value, "予測",
                        CashFlowForecastDto.class.getSimpleName(), CashFlowForecastDto.class.getName());
            }
            case ReportSectionKey.AR_AGING -> {
                JsonNode source = objectMapper.valueToTree(invoiceService.aging(target.atEndOfMonth()));
                yield new SectionValue(source, source, "実績",
                        AgingReportDto.class.getSimpleName(), AgingReportDto.class.getName());
            }
            default -> throw BusinessException.of(400, "error.managementReport.sectionNotAccepted");
        };
    }

    private ReportScopeSnapshot resolveScope(LocalDate asOf) {
        String role = SecurityUtils.currentRole();
        Long userId = SecurityUtils.currentUserId();
        if ("管理者".equals(role)) {
            return buildScope("COMPANY", null, true, List.of(), List.of());
        }
        if (!"マネージャー".equals(role) || userId == null) {
            throw BusinessException.of(403, "error.managementReport.roleDenied");
        }
        Set<Long> organizations = organizationScopeService.allowedOrganizationIds(asOf);
        Set<Long> directUsers = organizationScopeService.allowedDirectUserIds(asOf);
        return buildScope("ORGANIZATION", userId, false,
                sorted(organizations), sorted(directUsers));
    }

    private ReportScopeSnapshot buildScope(String ownerType, Long ownerId, boolean companyWide,
                                           List<Long> organizationIds, List<Long> directUserIds) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ownerType", ownerType);
        map.put("ownerId", ownerId);
        map.put("companyWide", companyWide);
        map.put("organizationIds", organizationIds);
        map.put("directUserIds", directUserIds);
        map.put("policyVersion", POLICY_VERSION);
        map.put("sessionIndependent", true);
        String json = toJson(map);
        return new ReportScopeSnapshot(ownerType, ownerId, companyWide, organizationIds,
                directUserIds, POLICY_VERSION, json, sha256(json));
    }

    private <T> T withExplicitPrincipal(Long userId, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        Authentication authentication;
        if (userId == null) {
            authentication = new UsernamePasswordAuthenticationToken(
                    "report-scheduler", "N/A", List.of(new SimpleGrantedAuthority("ROLE_管理者")));
        } else {
            SysUser user = sysUserMapper.selectById(userId);
            if (user == null || user.getStatus() == null || user.getStatus() != 1
                    || !("管理者".equals(user.getRole()) || "マネージャー".equals(user.getRole()))) {
                throw BusinessException.of(403, "error.managementReport.principalDenied");
            }
            authentication = new UsernamePasswordAuthenticationToken(
                    new LoginUser(user, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()))),
                    "N/A", List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole())));
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private void requireReportRole() {
        String role = SecurityUtils.currentRole();
        if (!("管理者".equals(role) || "マネージャー".equals(role))) {
            throw BusinessException.of(403, "error.managementReport.roleDenied");
        }
    }

    private List<String> readSectionKeys(String json) {
        if (json == null || json.isBlank()) {
            return ReportSectionKey.DEFAULT_ORDER;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode sections = root.isArray() ? root : root.path("sections");
            if (!sections.isArray()) {
                return ReportSectionKey.DEFAULT_ORDER;
            }
            List<String> result = new ArrayList<>();
            sections.forEach(node -> {
                String key = node.isTextual() ? node.asText() : node.path("sectionKey").asText(null);
                if (key != null && ReportSectionKey.DEFAULT_ORDER.contains(key) && !result.contains(key)) {
                    result.add(key);
                }
            });
            return result.isEmpty() ? ReportSectionKey.DEFAULT_ORDER : result;
        } catch (Exception ex) {
            throw BusinessException.of(400, "error.managementReport.sectionConfigInvalid");
        }
    }

    private ReportSectionSnapshot findSection(Long runId, String sectionKey) {
        return sectionMapper.selectOne(new QueryWrapper<ReportSectionSnapshot>()
                .eq("run_id", runId).eq("section_key", sectionKey));
    }

    private List<ReportSectionSnapshot> listSectionsWithoutLookup(Long runId) {
        return sectionMapper.selectList(new QueryWrapper<ReportSectionSnapshot>()
                .eq("run_id", runId).orderByAsc("id"));
    }

    private String buildRunKey(ReportTemplateVersion version, YearMonth period, String cutoff,
                               ReportScopeSnapshot scope, ReportGenerationCommand command) {
        String stable = version.getId() + "|" + period + "|" + cutoff + "|" + scope.getHash();
        if (command.explicitRegeneration()) {
            return "report:" + period + ":" + UUID.randomUUID();
        }
        return "report:" + period + ":" + sha256(stable).substring(0, 64);
    }

    private String normalizeCutoff(String value) {
        if ("確定".equals(value) || "MONTHLY_CLOSING".equals(value)) {
            return "MONTHLY_CLOSING";
        }
        if (value == null || value.isBlank() || "速報".equals(value) || "GENERATED_AT".equals(value)) {
            return "GENERATED_AT";
        }
        throw BusinessException.of(400, "error.managementReport.cutoffInvalid");
    }

    private void validateCommand(ReportGenerationCommand command) {
        if (command == null || command.templateVersionId() == null || command.period() == null) {
            throw BusinessException.of(400, "error.managementReport.requestInvalid");
        }
        if (command.period().isBefore(YearMonth.of(2000, 1))) {
            throw BusinessException.of(400, "error.managementReport.periodInvalid");
        }
    }

    private String sectionHashInput(ReportSectionSnapshot snapshot) {
        return snapshot.getRunId() + "|" + snapshot.getSectionKey() + "|"
                + snapshot.getFactType() + "|" + snapshot.getConfirmation() + "|"
                + snapshot.getPeriodFrom() + "|" + snapshot.getPeriodTo() + "|"
                + snapshot.getCutoffKind() + "|" + snapshot.getDataAsOfAt() + "|"
                + snapshot.getSourceHash() + "|" + snapshot.getValueJson();
    }

    private long countRows(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return 0L;
        }
        if (node.isArray()) {
            return node.size();
        }
        if (node.isObject()) {
            long nested = 0L;
            var fields = node.fields();
            while (fields.hasNext()) {
                JsonNode child = fields.next().getValue();
                if (child.isArray()) {
                    nested += child.size();
                }
            }
            return nested > 0 ? nested : 1L;
        }
        return 1L;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.managementReport.serializationFailed");
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                result.append(String.format("%02x", b));
            }
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256を利用できません", ex);
        }
    }

    private List<Long> sorted(Set<Long> values) {
        return values == null ? List.of() : values.stream().sorted().toList();
    }

    private record SectionValue(JsonNode source, JsonNode value, String factType,
                                String canonicalService, String canonicalDto) {
    }
}
