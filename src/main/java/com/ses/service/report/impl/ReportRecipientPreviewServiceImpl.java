package com.ses.service.report.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.LoginUser;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.entity.ReportTemplateVersion;
import com.ses.entity.ReportRun;
import com.ses.entity.SysUser;
import com.ses.mapper.ReportTemplateVersionMapper;
import com.ses.mapper.RoleMenuMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.security.OrganizationScopeService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** recipientの現在scopeをpreviewし、許可結果がない場合はfail-closedにする。 */
@Service
public class ReportRecipientPreviewServiceImpl implements ReportRecipientPreviewService {

    private static final String TIMEZONE = "Asia/Tokyo";
    private static final String POLICY_VERSION = "scope-policy-approved-1";
    private final ReportTemplateVersionMapper versionMapper;
    private final SysUserMapper sysUserMapper;
    private final OrganizationScopeService organizationScopeService;
    private final ObjectMapper objectMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final boolean strictMenuCheck;

    /** 既存の軽量単体テスト向け互換コンストラクタ。Spring実行時はmenu権限も必ず検証する。 */
    public ReportRecipientPreviewServiceImpl(ReportTemplateVersionMapper versionMapper,
                                             SysUserMapper sysUserMapper,
                                             OrganizationScopeService organizationScopeService,
                                             ObjectMapper objectMapper) {
        this(versionMapper, sysUserMapper, organizationScopeService, objectMapper, null, false);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ReportRecipientPreviewServiceImpl(ReportTemplateVersionMapper versionMapper,
                                             SysUserMapper sysUserMapper,
                                             OrganizationScopeService organizationScopeService,
                                             ObjectMapper objectMapper,
                                             RoleMenuMapper roleMenuMapper) {
        this(versionMapper, sysUserMapper, organizationScopeService, objectMapper, roleMenuMapper, true);
    }

    private ReportRecipientPreviewServiceImpl(ReportTemplateVersionMapper versionMapper,
                                              SysUserMapper sysUserMapper,
                                              OrganizationScopeService organizationScopeService,
                                              ObjectMapper objectMapper,
                                              RoleMenuMapper roleMenuMapper,
                                              boolean strictMenuCheck) {
        this.versionMapper = versionMapper;
        this.sysUserMapper = sysUserMapper;
        this.organizationScopeService = organizationScopeService;
        this.objectMapper = objectMapper;
        this.roleMenuMapper = roleMenuMapper;
        this.strictMenuCheck = strictMenuCheck;
    }

    @Override
    public ReportRecipientPreviewResult preview(Long templateVersionId, YearMonth period) {
        String tenantId = requireTenant();
        ReportTemplateVersion version = versionMapper.selectOne(new QueryWrapper<ReportTemplateVersion>()
                .eq("tenant_id", tenantId).eq("id", templateVersionId));
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw BusinessException.of(400, "error.managementReport.templateVersionNotPublished");
        }
        String actorRole = SecurityUtils.currentRole();
        if (!("管理者".equals(actorRole) || "マネージャー".equals(actorRole))) {
            throw BusinessException.of(403, "error.managementReport.roleDenied");
        }
        Scope owner = scopeForCurrentUser(currentAsOf());
        return previewInternal(version, period, owner);
    }

    @Override
    public ReportRecipientPreviewResult previewForRun(ReportRun run) {
        if (run == null || run.getTemplateVersionId() == null || run.getPeriodFrom() == null) {
            throw BusinessException.of(400, "error.managementReport.runInvalid");
        }
        String tenantId = requireTenant();
        if (run.getTenantId() == null || !tenantId.equals(run.getTenantId())) {
            throw BusinessException.of(403, "error.tenant.contextMismatch");
        }
        ReportTemplateVersion version = versionMapper.selectOne(new QueryWrapper<ReportTemplateVersion>()
                .eq("tenant_id", tenantId).eq("id", run.getTemplateVersionId()));
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw BusinessException.of(400, "error.managementReport.templateVersionNotPublished");
        }
        if (run.getOrganizationScopeJson() == null || run.getScopeHash() == null
                || !run.getScopeHash().equals(sha256(run.getOrganizationScopeJson()))) {
            throw BusinessException.of(403, "error.managementReport.scopeChanged");
        }
        ReportScopeSnapshot savedScope;
        try {
            JsonNode savedJson = objectMapper.readTree(run.getOrganizationScopeJson());
            String savedOwnerType = savedJson.path("ownerType").asText(null);
            Long savedOwnerId = savedJson.path("ownerId").isNumber()
                    ? savedJson.path("ownerId").asLong() : null;
            String savedPolicyVersion = savedJson.path("policyVersion").asText(null);
            if (!java.util.Objects.equals(savedOwnerType, run.getScopeOwnerType())
                    || !java.util.Objects.equals(savedOwnerId, run.getScopeOwnerId())
                    || !java.util.Objects.equals(savedPolicyVersion, run.getScopePolicyVersion())) {
                throw BusinessException.of(403, "error.managementReport.scopeChanged");
            }
            Set<Long> ids = new HashSet<>();
            savedJson.path("organizationIds").forEach(node -> ids.add(node.asLong()));
            List<Long> directUsers = new ArrayList<>();
            savedJson.path("directUserIds").forEach(node -> directUsers.add(node.asLong()));
            savedScope = new ReportScopeSnapshot(run.getScopeOwnerType(), run.getScopeOwnerId(),
                    savedJson.path("companyWide").asBoolean(false), ids.stream().sorted().toList(),
                    directUsers.stream().sorted().toList(), run.getScopePolicyVersion(),
                    run.getOrganizationScopeJson(), run.getScopeHash(),
                    readLongList(savedJson.path("engineerIds")),
                    readLongList(savedJson.path("contractIds")),
                    readLongList(savedJson.path("invoiceIds")));
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.managementReport.scopeSnapshotInvalid");
        }
        return previewInternal(version, YearMonth.from(run.getPeriodFrom()), toScope(savedScope));
    }

    @Override
    public ReportRecipientPreviewResult previewForScope(Long templateVersionId, YearMonth period,
                                                         ReportScopeSnapshot scope) {
        if (period == null || scope == null) {
            throw BusinessException.of(400, "error.managementReport.scopeSnapshotInvalid");
        }
        if (scope.getJson() == null || scope.getHash() == null
                || !scope.getHash().equals(sha256(scope.getJson()))) {
            throw BusinessException.of(403, "error.managementReport.scopeChanged");
        }
        ReportTemplateVersion version = versionMapper.selectOne(new QueryWrapper<ReportTemplateVersion>()
                .eq("tenant_id", requireTenant()).eq("id", templateVersionId));
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw BusinessException.of(400, "error.managementReport.templateVersionNotPublished");
        }
        return previewInternal(version, period, toScope(scope));
    }

    private ReportRecipientPreviewResult previewInternal(ReportTemplateVersion version, YearMonth period, Scope owner) {
        RecipientPolicy policy = readPolicy(version.getRecipientConfigJson());
        List<SysUser> candidates = candidateUsers(policy);
        LocalDate permissionAsOf = currentAsOf();
        List<ReportRecipientPreview> results = new ArrayList<>();
        for (SysUser candidate : candidates) {
            Scope recipient = withUser(candidate, () -> scopeForCurrentUser(permissionAsOf));
            // reportに含まれるscopeがrecipientの現在scopeに収まる場合だけ許可する。
            // ownerのscopeより狭いrecipientへ配布すると、recipientが参照できない組織の値を通知してしまう。
            boolean allowed = "管理者".equals(candidate.getRole())
                    || (!owner.companyWide()
                    && "マネージャー".equals(candidate.getRole())
                    && isSubset(owner.organizationIds(), recipient.organizationIds())
                    && isSubset(owner.directUserIds(), recipient.directUserIds())
                    && isSubset(owner.engineerIds(), recipient.engineerIds())
                    && isSubset(owner.contractIds(), recipient.contractIds())
                    && isSubset(owner.invoiceIds(), recipient.invoiceIds()));
            String reason = allowed ? "SCOPE_MATCH" : "RECIPIENT_SCOPE_MISMATCH";
            results.add(new ReportRecipientPreview(candidate.getId(), candidate.getRole(),
                    allowed ? "ALLOW" : "DENY", reason, recipient.hash()));
        }
        List<Long> allowedIds = results.stream().filter(r -> "ALLOW".equals(r.getScopeDecision()))
                .map(ReportRecipientPreview::getRecipientUserId).sorted().toList();
        if (allowedIds.isEmpty()) {
            throw BusinessException.of(403, "error.managementReport.recipientScopeDenied");
        }
        String previewHash = sha256(version.getId() + "|" + period + "|" + owner.ownerType() + "|"
                + owner.ownerId() + "|" + owner.hash()
                + "|" + canonicalRecipients(results) + "|" + POLICY_VERSION);
        return new ReportRecipientPreviewResult(previewHash, "APPROVED_SCOPE_CHECKED",
                LocalDateTime.now(ZoneId.of(TIMEZONE)), results);
    }

    private List<SysUser> candidateUsers(RecipientPolicy policy) {
        if (policy.roles().isEmpty()) {
            return List.of();
        }
        String tenantId = requireTenant();
        QueryWrapper<SysUser> query = new QueryWrapper<SysUser>()
                .eq("tenant_id", tenantId)
                .eq("status", 1);
        // recipient設定のuserIdsだけを信頼せず、承認済みroleの交差条件を必ず付ける。
        // これにより、誤設定で営業・HR等を配布対象へ混入させない。
        Set<String> allowedRoles = Set.of("管理者", "マネージャー");
        query.in("role", policy.roles().stream().filter(allowedRoles::contains).toList());
        if (!policy.userIds().isEmpty()) {
            query.in("id", policy.userIds());
        }
        List<SysUser> users = sysUserMapper.selectList(query);
        if (users == null) return List.of();
        return users.stream()
                .filter(user -> user != null && user.getId() != null && user.getStatus() != null && user.getStatus() == 1)
                .filter(user -> tenantId.equals(user.getTenantId()))
                .filter(user -> allowedRoles.contains(user.getRole()))
                .filter(user -> !strictMenuCheck || hasManagementReportMenu(user.getRole()))
                .toList();
    }

    private RecipientPolicy readPolicy(String json) {
        try {
            JsonNode root = json == null || json.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(json);
            boolean rolesConfigured = root.has("roles");
            boolean userIdsConfigured = root.has("userIds");
            Set<String> roles = new HashSet<>();
            if (rolesConfigured && !root.path("roles").isArray()) {
                throw BusinessException.of(400, "error.managementReport.recipientConfigInvalid");
            }
            for (JsonNode node : root.path("roles")) {
                roles.add(node.asText());
            }
            List<Long> ids = new ArrayList<>();
            if (userIdsConfigured && !root.path("userIds").isArray()) {
                throw BusinessException.of(400, "error.managementReport.recipientConfigInvalid");
            }
            for (JsonNode node : root.path("userIds")) {
                ids.add(node.asLong());
            }
            if (!rolesConfigured && !userIdsConfigured) {
                roles.add("管理者");
                roles.add("マネージャー");
            } else if (rolesConfigured) {
                roles = roles.stream()
                        .filter(role -> "管理者".equals(role) || "マネージャー".equals(role))
                        .collect(java.util.stream.Collectors.toSet());
                if (roles.isEmpty()) {
                    throw BusinessException.of(400, "error.managementReport.recipientConfigInvalid");
                }
            } else {
                roles.add("管理者");
                roles.add("マネージャー");
            }
            return new RecipientPolicy(roles, ids);
        } catch (Exception ex) {
            if (ex instanceof BusinessException businessException) throw businessException;
            throw BusinessException.of(400, "error.managementReport.recipientConfigInvalid");
        }
    }

    private boolean hasManagementReportMenu(String role) {
        if (roleMenuMapper == null) return false;
        List<String> menuKeys = roleMenuMapper.selectMenuKeysByRole(role);
        return menuKeys != null && menuKeys.contains("management-report");
    }

    private String canonicalRecipients(List<ReportRecipientPreview> recipients) {
        return recipients.stream()
                .sorted(java.util.Comparator.comparing(ReportRecipientPreview::getRecipientUserId,
                        java.util.Comparator.nullsLast(Long::compareTo))
                        .thenComparing(ReportRecipientPreview::getRecipientRole,
                                java.util.Comparator.nullsLast(String::compareTo))
                        .thenComparing(ReportRecipientPreview::getScopeDecision,
                                java.util.Comparator.nullsLast(String::compareTo)))
                .map(item -> item.getRecipientUserId() + ":" + item.getRecipientRole() + ":"
                        + item.getScopeDecision() + ":" + item.getReasonCode() + ":" + item.getRecipientScopeHash())
                .toList().toString();
    }

    private List<Long> readLongList(JsonNode node) {
        List<Long> result = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(item -> result.add(item.asLong()));
        return result;
    }

    private Scope scopeForCurrentUser(LocalDate asOf) {
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return new Scope("COMPANY", null, true, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
                    sha256("COMPANY|" + POLICY_VERSION));
        }
        if (!"マネージャー".equals(role)) {
            throw BusinessException.of(403, "error.managementReport.roleDenied");
        }
        Set<Long> ids = organizationScopeService.allowedOrganizationIds(asOf);
        Set<Long> directUsers = organizationScopeService.allowedDirectUserIds(asOf);
        Set<Long> engineerIds = organizationScopeService.allowedEngineerIds(asOf);
        Set<Long> contractIds = organizationScopeService.allowedContractIds(asOf);
        Set<Long> invoiceIds = organizationScopeService.allowedInvoiceIds(asOf);
        return new Scope("ORGANIZATION", SecurityUtils.currentUserId(), false,
                ids == null ? Set.of() : new HashSet<>(ids),
                directUsers == null ? Set.of() : new HashSet<>(directUsers),
                engineerIds == null ? Set.of() : new HashSet<>(engineerIds),
                contractIds == null ? Set.of() : new HashSet<>(contractIds),
                invoiceIds == null ? Set.of() : new HashSet<>(invoiceIds),
                sha256("ORGANIZATION|" + sorted(ids) + "|" + sorted(directUsers) + "|"
                        + sorted(engineerIds) + "|" + sorted(contractIds) + "|" + sorted(invoiceIds)
                        + "|" + POLICY_VERSION));
    }

    private Scope toScope(ReportScopeSnapshot scope) {
        return new Scope(scope.getOwnerType(), scope.getOwnerId(), scope.isCompanyWide(),
                scope.getOrganizationIds() == null ? Set.of() : new HashSet<>(scope.getOrganizationIds()),
                scope.getDirectUserIds() == null ? Set.of() : new HashSet<>(scope.getDirectUserIds()),
                scope.getEngineerIds() == null ? Set.of() : new HashSet<>(scope.getEngineerIds()),
                scope.getContractIds() == null ? Set.of() : new HashSet<>(scope.getContractIds()),
                scope.getInvoiceIds() == null ? Set.of() : new HashSet<>(scope.getInvoiceIds()),
                scope.getHash() == null ? sha256(scope.getJson()) : scope.getHash());
    }

    private <T> T withUser(SysUser user, java.util.function.Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
        Authentication auth = new UsernamePasswordAuthenticationToken(new LoginUser(user, authorities),
                "N/A", authorities);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    private boolean isSubset(Set<Long> child, Set<Long> parent) {
        return parent.containsAll(child);
    }

    private String sorted(Set<Long> ids) {
        return ids == null ? "[]" : ids.stream().sorted().toList().toString();
    }

    private LocalDate currentAsOf() {
        return LocalDate.now(ZoneId.of(TIMEZONE));
    }

    private String requireTenant() {
        return com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext();
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b));
            return result.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256を利用できません", ex);
        }
    }

    private record RecipientPolicy(Set<String> roles, List<Long> userIds) {
    }

    private record Scope(String ownerType, Long ownerId, boolean companyWide,
                         Set<Long> organizationIds, Set<Long> directUserIds,
                         Set<Long> engineerIds, Set<Long> contractIds, Set<Long> invoiceIds,
                         String hash) {
    }
}
