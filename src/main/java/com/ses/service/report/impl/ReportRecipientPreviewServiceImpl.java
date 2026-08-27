package com.ses.service.report.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.config.LoginUser;
import com.ses.dto.report.ReportRecipientPreview;
import com.ses.dto.report.ReportRecipientPreviewResult;
import com.ses.entity.ReportTemplateVersion;
import com.ses.entity.ReportRun;
import com.ses.entity.SysUser;
import com.ses.mapper.ReportTemplateVersionMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.report.ReportRecipientPreviewService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class ReportRecipientPreviewServiceImpl implements ReportRecipientPreviewService {

    private static final String TIMEZONE = "Asia/Tokyo";
    private static final String POLICY_VERSION = "scope-policy-approved-1";
    private final ReportTemplateVersionMapper versionMapper;
    private final SysUserMapper sysUserMapper;
    private final OrganizationScopeService organizationScopeService;
    private final ObjectMapper objectMapper;

    @Override
    public ReportRecipientPreviewResult preview(Long templateVersionId, YearMonth period) {
        ReportTemplateVersion version = versionMapper.selectById(templateVersionId);
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw BusinessException.of(400, "error.managementReport.templateVersionNotPublished");
        }
        String actorRole = SecurityUtils.currentRole();
        if (!("管理者".equals(actorRole) || "マネージャー".equals(actorRole))) {
            throw BusinessException.of(403, "error.managementReport.roleDenied");
        }
        Scope owner = scopeForCurrentUser(period.atEndOfMonth());
        return previewInternal(version, period, owner);
    }

    @Override
    public ReportRecipientPreviewResult previewForRun(ReportRun run) {
        if (run == null || run.getTemplateVersionId() == null || run.getPeriodFrom() == null) {
            throw BusinessException.of(400, "error.managementReport.runInvalid");
        }
        ReportTemplateVersion version = versionMapper.selectById(run.getTemplateVersionId());
        if (version == null || !"PUBLISHED".equals(version.getStatus())) {
            throw BusinessException.of(400, "error.managementReport.templateVersionNotPublished");
        }
        Scope owner;
        try {
            JsonNode saved = objectMapper.readTree(run.getOrganizationScopeJson());
            Set<Long> ids = new HashSet<>();
            saved.path("organizationIds").forEach(node -> ids.add(node.asLong()));
            owner = new Scope(saved.path("companyWide").asBoolean(false), ids, run.getScopeHash());
        } catch (Exception ex) {
            throw BusinessException.of(500, "error.managementReport.scopeSnapshotInvalid");
        }
        return previewInternal(version, YearMonth.from(run.getPeriodFrom()), owner);
    }

    private ReportRecipientPreviewResult previewInternal(ReportTemplateVersion version, YearMonth period, Scope owner) {
        RecipientPolicy policy = readPolicy(version.getRecipientConfigJson());
        List<SysUser> candidates = candidateUsers(policy);
        List<ReportRecipientPreview> results = new ArrayList<>();
        for (SysUser candidate : candidates) {
            Scope recipient = withUser(candidate, () -> scopeForCurrentUser(period.atEndOfMonth()));
            boolean allowed = "管理者".equals(candidate.getRole())
                    || (owner.companyWide()
                    || (!recipient.organizationIds().isEmpty()
                    && !owner.organizationIds().isEmpty()
                    && isSubset(recipient.organizationIds(), owner.organizationIds())));
            String reason = allowed ? "SCOPE_MATCH" : "RECIPIENT_SCOPE_MISMATCH";
            results.add(new ReportRecipientPreview(candidate.getId(), candidate.getRole(),
                    allowed ? "ALLOW" : "DENY", reason, recipient.hash()));
        }
        List<Long> allowedIds = results.stream().filter(r -> "ALLOW".equals(r.getScopeDecision()))
                .map(ReportRecipientPreview::getRecipientUserId).sorted().toList();
        if (allowedIds.isEmpty()) {
            throw BusinessException.of(403, "error.managementReport.recipientScopeDenied");
        }
        String previewHash = sha256(version.getId() + "|" + period + "|" + owner.hash()
                + "|" + allowedIds + "|" + POLICY_VERSION);
        return new ReportRecipientPreviewResult(previewHash, "APPROVED_SCOPE_CHECKED",
                LocalDateTime.now(ZoneId.of(TIMEZONE)), results);
    }

    private List<SysUser> candidateUsers(RecipientPolicy policy) {
        QueryWrapper<SysUser> query = new QueryWrapper<SysUser>().eq("status", 1);
        // recipient設定のuserIdsだけを信頼せず、承認済みroleの交差条件を必ず付ける。
        // これにより、誤設定で営業・HR等を配布対象へ混入させない。
        Set<String> allowedRoles = Set.of("管理者", "マネージャー");
        if (policy.roles().isEmpty()) {
            query.in("role", allowedRoles);
        } else {
            query.in("role", policy.roles().stream().filter(allowedRoles::contains).toList());
        }
        if (!policy.userIds().isEmpty()) {
            query.in("id", policy.userIds());
        }
        return sysUserMapper.selectList(query);
    }

    private RecipientPolicy readPolicy(String json) {
        try {
            JsonNode root = json == null || json.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(json);
            Set<String> roles = new HashSet<>();
            root.path("roles").forEach(node -> roles.add(node.asText()));
            List<Long> ids = new ArrayList<>();
            root.path("userIds").forEach(node -> ids.add(node.asLong()));
            if (roles.isEmpty() && ids.isEmpty()) {
                roles.add("管理者");
                roles.add("マネージャー");
            }
            roles.removeIf(role -> !"管理者".equals(role) && !"マネージャー".equals(role));
            return new RecipientPolicy(roles, ids);
        } catch (Exception ex) {
            throw BusinessException.of(400, "error.managementReport.recipientConfigInvalid");
        }
    }

    private Scope scopeForCurrentUser(LocalDate asOf) {
        String role = SecurityUtils.currentRole();
        if ("管理者".equals(role)) {
            return new Scope(true, Set.of(), sha256("COMPANY|" + POLICY_VERSION));
        }
        if (!"マネージャー".equals(role)) {
            throw BusinessException.of(403, "error.managementReport.roleDenied");
        }
        Set<Long> ids = organizationScopeService.allowedOrganizationIds(asOf);
        return new Scope(false, ids == null ? Set.of() : new HashSet<>(ids),
                sha256("ORGANIZATION|" + sorted(ids) + "|" + POLICY_VERSION));
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

    private record Scope(boolean companyWide, Set<Long> organizationIds, String hash) {
    }
}
