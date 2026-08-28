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
import com.ses.entity.ReportSectionAttempt;
import com.ses.entity.ReportSectionSnapshot;
import com.ses.entity.ReportTemplateVersion;
import com.ses.entity.SysUser;
import com.ses.mapper.ReportRunMapper;
import com.ses.mapper.ReportSectionAttemptMapper;
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
import com.ses.service.billing.CashFlowForecastScope;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.security.ReportScopeContext;
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
    private final ReportSectionAttemptMapper sectionAttemptMapper;
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

    @Override
    public void assertAccessible(ReportRun run) {
        if (run == null) {
            throw BusinessException.of(404, "error.managementReport.runNotFound");
        }
        String role = SecurityUtils.currentRole();
        // schedulerからのdeliveryはHTTP sessionを持たない明示system principalで行う。
        // API入口はSpring Securityで保護されているため、この分岐は非HTTP実行に限定される。
        if (role == null && "SYSTEM_PRINCIPAL".equals(run.getPrincipalType())) {
            scopeSnapshotOf(run); // 保存scopeのJSON/hash改ざんはsystem実行でもfail-closedにする。
            return;
        }
        if ("管理者".equals(role)) {
            return;
        }
        Long currentUserId = SecurityUtils.currentUserId();
        if (!"マネージャー".equals(role) || currentUserId == null
                || !currentUserId.equals(run.getScopeOwnerId())
                || !"ORGANIZATION".equals(run.getScopeOwnerType())) {
            throw BusinessException.of(403, "error.managementReport.scopeDenied");
        }
        try {
            ReportScopeSnapshot savedScope = scopeSnapshotOf(run);
            JsonNode saved = objectMapper.readTree(savedScope.getJson());
            Set<Long> savedOrganizations = readLongSet(saved.path("organizationIds"));
            Set<Long> savedDirectUsers = readLongSet(saved.path("directUserIds"));
            LocalDate asOf = LocalDate.now(ZoneId.of(TIMEZONE));
            Set<Long> currentOrganizations = organizationScopeService.allowedOrganizationIds(asOf);
            Set<Long> currentDirectUsers = organizationScopeService.allowedDirectUserIds(asOf);
            if (currentOrganizations == null || currentDirectUsers == null
                    || !currentOrganizations.containsAll(savedOrganizations)
                    || !currentDirectUsers.containsAll(savedDirectUsers)) {
                throw BusinessException.of(403, "error.managementReport.scopeChanged");
            }
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BusinessException.of(403, "error.managementReport.scopeDenied");
        }
    }

    @Override
    public ReportScopeSnapshot scopeSnapshotOf(ReportRun run) {
        if (run == null || run.getOrganizationScopeJson() == null
                || run.getOrganizationScopeJson().isBlank()) {
            throw BusinessException.of(403, "error.managementReport.scopeDenied");
        }
        try {
            if (run.getScopeHash() == null || !run.getScopeHash().equals(sha256(run.getOrganizationScopeJson()))) {
                throw BusinessException.of(403, "error.managementReport.scopeChanged");
            }
            JsonNode saved = objectMapper.readTree(run.getOrganizationScopeJson());
            return new ReportScopeSnapshot(run.getScopeOwnerType(), run.getScopeOwnerId(),
                    saved.path("companyWide").asBoolean(false),
                    readLongList(saved.path("organizationIds")),
                    readLongList(saved.path("directUserIds")),
                    run.getScopePolicyVersion(), run.getOrganizationScopeJson(), run.getScopeHash(),
                    readLongList(saved.path("engineerIds")),
                    readLongList(saved.path("contractIds")),
                    readLongList(saved.path("invoiceIds")));
        } catch (Exception ex) {
            throw BusinessException.of(403, "error.managementReport.scopeDenied");
        }
    }

    private ReportGenerationResult generateInternal(ReportGenerationCommand command) {
        YearMonth target = command.period();
        LocalDate periodFrom = target.atDay(1);
        LocalDate periodTo = target.atEndOfMonth();
        LocalDate permissionAsOf = LocalDate.now(ZoneId.of(TIMEZONE));
        String cutoffKind = normalizeCutoff(command.cutoffKind());
        boolean confirmed = "MONTHLY_CLOSING".equals(cutoffKind);
        if (confirmed && !monthlyClosingService.isClosed(target.toString())) {
            throw BusinessException.of(400, "error.managementReport.closingRequired");
        }

        ReportTemplateVersion templateVersion = templateVersionMapper.selectById(command.templateVersionId());
        if (templateVersion == null || !"PUBLISHED".equals(templateVersion.getStatus())) {
            throw BusinessException.of(400, "error.managementReport.templateVersionNotPublished");
        }

        LocalDateTime asOfAt = LocalDateTime.now(ZoneId.of(TIMEZONE));
        // 渡されたscopeはowner/previewの境界確認にだけ使い、entity ID母集団は必ず生成直前に
        // 現在principalから再解決する。scheduleや再生成要求が保持する古いID集合をそのまま
        // ReportScopeContextへ入れると、異動済みengineer/contract/invoiceが混入する。
        ReportScopeSnapshot scope = resolveScope(permissionAsOf);
        if (command.scopeSnapshot() != null) {
            assertGenerationScope(command.scopeSnapshot(), permissionAsOf);
        }

        // generation直前に同一principalでrecipient scopeを再評価する。APIからhashが渡された場合は
        // previewとgenerationの間に権限・組織が変わっていないことも確認する。
        ReportRecipientPreviewResult preview = command.scopeSnapshot() == null
                ? recipientPreviewService.preview(command.templateVersionId(), target)
                : recipientPreviewService.previewForScope(command.templateVersionId(), target,
                scope);
        if (command.recipientPreviewHash() != null
                && !command.recipientPreviewHash().equals(preview.getPreviewHash())) {
            throw BusinessException.of(403, "error.managementReport.recipientPreviewStale");
        }

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
            run.setRegenerationOfRunId(command.regenerationOfRunId());
            run.setSnapshotVersion(nextSnapshotVersion(templateVersion, periodFrom, periodTo,
                    cutoffKind, scope, command));
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
            LocalDateTime attemptStartedAt = LocalDateTime.now(ZoneId.of(TIMEZONE));
            if (existing != null && "SUCCEEDED".equals(existing.getSectionStatus())) {
                continue;
            }
            try {
                SectionValue value = ReportScopeContext.with(scope,
                        () -> loadSection(sectionKey, target, sourceCache, scope));
                saveSection(run, existing, sectionKey, value, confirmed, asOfAt, periodFrom, periodTo,
                        attemptStartedAt);
            } catch (Exception ex) {
                hasFailure = true;
                saveFailedSection(run, existing, sectionKey, confirmed, asOfAt,
                        periodFrom, periodTo, attemptStartedAt, ex);
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
                             LocalDate periodFrom, LocalDate periodTo,
                             LocalDateTime attemptStartedAt) {
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
        int attemptNo = existing == null || existing.getAttemptCount() == null
                ? 1 : existing.getAttemptCount() + 1;
        snapshot.setAttemptCount(attemptNo);
        if (existing == null) {
            sectionMapper.insert(snapshot);
        } else {
            sectionMapper.updateById(snapshot);
        }
        insertAttempt(run, snapshot, attemptNo, attemptStartedAt, LocalDateTime.now(ZoneId.of(TIMEZONE)));
    }

    private void saveFailedSection(ReportRun run, ReportSectionSnapshot existing, String sectionKey,
                                   boolean confirmed, LocalDateTime asOfAt, LocalDate periodFrom,
                                   LocalDate periodTo, LocalDateTime attemptStartedAt, Exception ex) {
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
        int attemptNo = existing == null || existing.getAttemptCount() == null
                ? 1 : existing.getAttemptCount() + 1;
        snapshot.setAttemptCount(attemptNo);
        if (existing == null) {
            sectionMapper.insert(snapshot);
        } else {
            sectionMapper.updateById(snapshot);
        }
        insertAttempt(run, snapshot, attemptNo, attemptStartedAt, LocalDateTime.now(ZoneId.of(TIMEZONE)));
    }

    /** 現在のsection状態とは別に、各attemptを追記して失敗理由とhashを監査可能にする。 */
    private void insertAttempt(ReportRun run, ReportSectionSnapshot snapshot, int attemptNo,
                               LocalDateTime startedAt, LocalDateTime finishedAt) {
        ReportSectionAttempt attempt = new ReportSectionAttempt();
        attempt.setTenantId(TENANT_ID);
        attempt.setRunId(run.getId());
        attempt.setSectionKey(snapshot.getSectionKey());
        attempt.setAttemptNo(attemptNo);
        attempt.setSectionStatus(snapshot.getSectionStatus());
        attempt.setFactType(snapshot.getFactType());
        attempt.setConfirmation(snapshot.getConfirmation());
        attempt.setPeriodFrom(snapshot.getPeriodFrom());
        attempt.setPeriodTo(snapshot.getPeriodTo());
        attempt.setCutoffKind(snapshot.getCutoffKind());
        attempt.setStartedAt(startedAt);
        attempt.setFinishedAt(finishedAt);
        attempt.setDataAsOfAt(snapshot.getDataAsOfAt());
        attempt.setFreshnessStatus(snapshot.getFreshnessStatus());
        attempt.setCanonicalService(snapshot.getCanonicalService());
        attempt.setCanonicalDto(snapshot.getCanonicalDto());
        attempt.setSourceRowCount(snapshot.getSourceRowCount());
        attempt.setSourceHash(snapshot.getSourceHash());
        attempt.setValueJson(snapshot.getValueJson());
        attempt.setErrorCode(snapshot.getErrorCode());
        attempt.setErrorMessage(snapshot.getErrorMessage());
        attempt.setSnapshotHash(snapshot.getSnapshotHash());
        sectionAttemptMapper.insert(attempt);
    }

    private SectionValue loadSection(String sectionKey, YearMonth target,
                                     Map<String, JsonNode> sourceCache,
                                     ReportScopeSnapshot scope) {
        return switch (sectionKey) {
            case ReportSectionKey.SALES, ReportSectionKey.GROSS_PROFIT -> {
                JsonNode source = sourceCache.computeIfAbsent("dashboard",
                        ignored -> objectMapper.valueToTree(dashboardService.getSummary(dashboardFiscalYear(target))));
                JsonNode value = dashboardMonthValue(source, target, false);
                yield new SectionValue(source, value, "実績",
                        DashboardService.class.getSimpleName(), DashboardSummaryDto.class.getName());
            }
            case ReportSectionKey.REVENUE_FORECAST -> {
                JsonNode source = sourceCache.computeIfAbsent("dashboard",
                        ignored -> objectMapper.valueToTree(dashboardService.getSummary(dashboardFiscalYear(target))));
                JsonNode value = dashboardMonthValue(source, target, true);
                yield new SectionValue(source, value, "予測",
                        DashboardService.class.getSimpleName(), DashboardSummaryDto.class.getName());
            }
            case ReportSectionKey.UTILIZATION, ReportSectionKey.BENCH, ReportSectionKey.CONTRACT_RENEWAL_OUTLOOK -> {
                JsonNode source = sourceCache.computeIfAbsent("utilization",
                        ignored -> objectMapper.valueToTree(utilizationForecastService.getForecast(target, 1)));
                JsonNode value = switch (sectionKey) {
                    case ReportSectionKey.UTILIZATION, ReportSectionKey.BENCH -> source.path("monthlyForecasts");
                    default -> source.path("rolloffEngineers");
                };
                yield new SectionValue(source, value, "予測",
                        UtilizationForecastService.class.getSimpleName(), UtilizationForecastDto.class.getName());
            }
            case ReportSectionKey.MANAGEMENT_ACCOUNTING -> {
                JsonNode source = objectMapper.valueToTree(managementAccountingService.summary(target.toString()));
                yield new SectionValue(source, source, "実績",
                        ManagementAccountingService.class.getSimpleName(), ManagementAccountingSummaryDto.class.getName());
            }
            case ReportSectionKey.CASH_FLOW, ReportSectionKey.BP_PAYMENT_PLAN -> {
                JsonNode source = sourceCache.computeIfAbsent("cash-flow",
                        ignored -> objectMapper.valueToTree(cashFlowForecastService.forecast(
                                target, 1, null, cashFlowScope(scope, target.atEndOfMonth()))));
                JsonNode value = ReportSectionKey.BP_PAYMENT_PLAN.equals(sectionKey)
                        ? source.path("months") : source;
                yield new SectionValue(source, value, "予測",
                        CashFlowForecastService.class.getSimpleName(), CashFlowForecastDto.class.getName());
            }
            case ReportSectionKey.AR_AGING -> {
                JsonNode source = objectMapper.valueToTree(invoiceService.aging(target.atEndOfMonth()));
                yield new SectionValue(source, source, "実績",
                        InvoiceService.class.getSimpleName(), AgingReportDto.class.getName());
            }
            default -> throw BusinessException.of(400, "error.managementReport.sectionNotAccepted");
        };
    }

    /** Dashboardの既存chart値から対象月だけを取り出す。report側で売上式を再計算しない。 */
    private JsonNode dashboardMonthValue(JsonNode source, YearMonth target, boolean forecast) {
        JsonNode revenue = source.path("charts").path("revenue");
        List<String> labels = new ArrayList<>();
        revenue.path("labels").forEach(node -> labels.add(node.asText()));
        String monthLabel = target.getMonthValue() + "月";
        int index = labels.indexOf(monthLabel);
        if (index < 0) {
            throw BusinessException.of(400, "error.managementReport.sourceMonthNotFound");
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("month", target.toString());
        value.put("sales", arrayValue(revenue.path("sales"), index));
        value.put("grossProfit", arrayValue(revenue.path("profit"), index));
        value.put("isActual", arrayValue(revenue.path("isActual"), index));
        if (forecast) {
            value.put("forecast", arrayValue(revenue.path("forecast"), index));
            value.put("forecastPipelineCount", revenue.path("forecastPipelineCount").isMissingNode()
                    ? null : revenue.path("forecastPipelineCount").asInt());
            value.put("forecastPipelineAmount", revenue.path("forecastPipelineAmount").isMissingNode()
                    ? null : revenue.path("forecastPipelineAmount").asLong());
        }
        return objectMapper.valueToTree(value);
    }

    private Object arrayValue(JsonNode array, int index) {
        return array.isArray() && index < array.size() ? objectMapper.convertValue(array.get(index), Object.class) : null;
    }

    /** Dashboardは4月始まりの年度chartなので、1〜3月は前年の年度を読む。 */
    private int dashboardFiscalYear(YearMonth target) {
        return target.getMonthValue() < 4 ? target.getYear() - 1 : target.getYear();
    }

    private CashFlowForecastScope cashFlowScope(ReportScopeSnapshot scope, LocalDate asOf) {
        if (scope == null || scope.isCompanyWide()) return null;
        return new CashFlowForecastScope(false, scope.getInvoiceIds(), scope.getContractIds(),
                scope.getEngineerIds(), scope.getOrganizationIds(), scope.getDirectUserIds(), asOf);
    }

    private ReportScopeSnapshot resolveScope(LocalDate asOf) {
        String role = SecurityUtils.currentRole();
        Long userId = SecurityUtils.currentUserId();
        if ("管理者".equals(role)) {
            return buildScope("COMPANY", null, true, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        if (!"マネージャー".equals(role) || userId == null) {
            throw BusinessException.of(403, "error.managementReport.roleDenied");
        }
        Set<Long> organizations = organizationScopeService.allowedOrganizationIds(asOf);
        Set<Long> directUsers = organizationScopeService.allowedDirectUserIds(asOf);
        return buildScope("ORGANIZATION", userId, false,
                sorted(organizations), sorted(directUsers),
                sorted(organizationScopeService.allowedEngineerIds(asOf)),
                sorted(organizationScopeService.allowedContractIds(asOf)),
                sorted(organizationScopeService.allowedInvoiceIds(asOf)));
    }

    private void assertGenerationScope(ReportScopeSnapshot scope, LocalDate asOf) {
        if (scope == null || scope.isCompanyWide()) {
            if (scope == null || !scope.isCompanyWide() || !"管理者".equals(SecurityUtils.currentRole())) {
                throw BusinessException.of(403, "error.managementReport.scopeDenied");
            }
            return;
        }
        Long currentUserId = SecurityUtils.currentUserId();
        if (!"マネージャー".equals(SecurityUtils.currentRole())
                || currentUserId == null || !currentUserId.equals(scope.getOwnerId())) {
            throw BusinessException.of(403, "error.managementReport.scopeDenied");
        }
        Set<Long> currentOrganizations = organizationScopeService.allowedOrganizationIds(asOf);
        Set<Long> currentDirectUsers = organizationScopeService.allowedDirectUserIds(asOf);
        if (currentOrganizations == null || currentDirectUsers == null
                || !currentOrganizations.containsAll(scope.getOrganizationIds())
                || !currentDirectUsers.containsAll(scope.getDirectUserIds())) {
            throw BusinessException.of(403, "error.managementReport.scopeChanged");
        }
    }

    private ReportScopeSnapshot buildScope(String ownerType, Long ownerId, boolean companyWide,
                                           List<Long> organizationIds, List<Long> directUserIds,
                                           List<Long> engineerIds, List<Long> contractIds,
                                           List<Long> invoiceIds) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ownerType", ownerType);
        map.put("ownerId", ownerId);
        map.put("companyWide", companyWide);
        map.put("organizationIds", organizationIds);
        map.put("directUserIds", directUserIds);
        map.put("engineerIds", engineerIds);
        map.put("contractIds", contractIds);
        map.put("invoiceIds", invoiceIds);
        map.put("policyVersion", POLICY_VERSION);
        map.put("sessionIndependent", true);
        String json = toJson(map);
        return new ReportScopeSnapshot(ownerType, ownerId, companyWide, organizationIds,
                directUserIds, POLICY_VERSION, json, sha256(json), engineerIds, contractIds, invoiceIds);
    }

    private int nextSnapshotVersion(ReportTemplateVersion version, LocalDate periodFrom,
                                    LocalDate periodTo, String cutoff, ReportScopeSnapshot scope,
                                    ReportGenerationCommand command) {
        if (!command.explicitRegeneration()) return 1;
        List<ReportRun> history = runMapper.selectList(new QueryWrapper<ReportRun>()
                .eq("tenant_id", TENANT_ID)
                .eq("template_version_id", version.getId())
                .eq("period_from", periodFrom)
                .eq("period_to", periodTo)
                .eq("cutoff_kind", cutoff)
                .eq("scope_hash", scope.getHash()));
        int max = 0;
        if (history != null) {
            for (ReportRun item : history) {
                if (item != null && item.getSnapshotVersion() != null) {
                    max = Math.max(max, item.getSnapshotVersion());
                }
            }
        }
        return max + 1;
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

    private Set<Long> readLongSet(JsonNode node) {
        return new java.util.HashSet<>(readLongList(node));
    }

    private List<Long> readLongList(JsonNode node) {
        List<Long> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(value -> result.add(value.asLong()));
        }
        return result;
    }

    private record SectionValue(JsonNode source, JsonNode value, String factType,
                                String canonicalService, String canonicalDto) {
    }
}
