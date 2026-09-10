package com.ses.service.ai.copilot.scope;

import com.ses.common.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

/**
 * query開始時点で確定した最終可視集合のimmutable snapshot。
 * nullは「そのdimensionに制限なし」、空集合は「0件」を表す。
 */
public final class EffectiveScopeSnapshot {
    private final String tenantId;
    private final Long legalEntityId;
    private final LocalDate asOf;
    private final String scopeType;
    private final boolean organizationFullAccess;
    private final boolean dataScoped;
    private final boolean salesDataScoped;
    private final Set<Long> organizationIds;
    private final Set<Long> directUserIds;
    private final Set<Long> userIds;
    private final Set<Long> engineerIds;
    private final Set<Long> projectIds;
    private final Set<Long> contractIds;
    private final Set<Long> customerIds;
    private final Set<Long> invoiceIds;
    private final Set<Long> proposalIds;
    private final String canonicalMembers;
    private final CopilotScopeContext scope;

    public EffectiveScopeSnapshot(String tenantId, Long legalEntityId, LocalDate asOf,
                                  String scopeType, boolean organizationFullAccess,
                                  boolean dataScoped, boolean salesDataScoped,
                                  Set<Long> organizationIds, Set<Long> directUserIds,
                                  Set<Long> userIds, Set<Long> engineerIds, Set<Long> projectIds,
                                  Set<Long> contractIds, Set<Long> customerIds, Set<Long> invoiceIds,
                                  Set<Long> proposalIds, String policyVersion,
                                  boolean emptyPopulation, String canonicalMembers,
                                  String scopeHash) {
        if (tenantId == null || tenantId.isBlank() || legalEntityId == null || legalEntityId <= 0
                || asOf == null || scopeType == null || scopeType.isBlank()
                || policyVersion == null || policyVersion.isBlank()
                || canonicalMembers == null || scopeHash == null || scopeHash.isBlank()) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }
        String normalizedTenantId = tenantId.trim();
        this.tenantId = normalizedTenantId;
        this.legalEntityId = legalEntityId;
        this.asOf = asOf;
        this.scopeType = scopeType;
        this.organizationFullAccess = organizationFullAccess;
        this.dataScoped = dataScoped;
        this.salesDataScoped = salesDataScoped;
        this.organizationIds = immutable(organizationIds);
        this.directUserIds = immutable(directUserIds);
        this.userIds = immutable(userIds);
        this.engineerIds = immutable(engineerIds);
        this.projectIds = immutable(projectIds);
        this.contractIds = immutable(contractIds);
        this.customerIds = immutable(customerIds);
        this.invoiceIds = immutable(invoiceIds);
        this.proposalIds = immutable(proposalIds);
        String expectedMembers = expectedCanonicalMembers(scopeType, organizationFullAccess, dataScoped,
                organizationIds, directUserIds, userIds, engineerIds, projectIds, contractIds,
                customerIds, invoiceIds, proposalIds);
        String canonicalInput = "tenant=" + normalizedTenantId
                + "|legalEntity=" + legalEntityId
                + "|asOf=" + asOf
                + "|scopeType=" + scopeType
                + "|policy=" + policyVersion
                + "|members=" + expectedMembers;
        String expectedScopeHash = sha256(canonicalInput);
        boolean expectedEmptyPopulation = !"ALL".equals(expectedMembers)
                && isEmpty(organizationIds) && isEmpty(directUserIds) && isEmpty(userIds)
                && isEmpty(engineerIds) && isEmpty(projectIds) && isEmpty(contractIds)
                && isEmpty(customerIds) && isEmpty(invoiceIds) && isEmpty(proposalIds);
        if (!EffectiveScopeSnapshotFactory.POLICY_VERSION.equals(policyVersion)
                || !expectedMembers.equals(canonicalMembers)
                || !expectedScopeHash.equals(scopeHash)
                || expectedEmptyPopulation != emptyPopulation) {
            throw BusinessException.of(403, "EFFECTIVE_SCOPE_SNAPSHOT_INVALID");
        }
        this.canonicalMembers = canonicalMembers;
        this.scope = new CopilotScopeContext(scopeType, policyVersion, scopeHash,
                emptyPopulation, normalizedTenantId, legalEntityId, canonicalMembers);
    }

    private static Set<Long> immutable(Set<Long> values) {
        if (values == null) return null;
        return Collections.unmodifiableSet(new TreeSet<>(values));
    }

    private static String expectedCanonicalMembers(String scopeType, boolean organizationFullAccess,
                                                   boolean dataScoped, Set<Long> organizationIds,
                                                   Set<Long> directUserIds, Set<Long> userIds,
                                                   Set<Long> engineerIds, Set<Long> projectIds,
                                                   Set<Long> contractIds, Set<Long> customerIds,
                                                   Set<Long> invoiceIds, Set<Long> proposalIds) {
        if ("COMPANY_WIDE".equals(scopeType) && organizationFullAccess && !dataScoped) {
            return "ALL";
        }
        return "organizations=" + canonical(organizationIds)
                + ";directUsers=" + canonical(directUserIds)
                + ";users=" + canonical(userIds)
                + ";engineers=" + canonical(engineerIds)
                + ";projects=" + canonical(projectIds)
                + ";contracts=" + canonical(contractIds)
                + ";customers=" + canonical(customerIds)
                + ";invoices=" + canonical(invoiceIds)
                + ";proposals=" + canonical(proposalIds);
    }

    private static String canonical(Set<Long> values) {
        return values == null ? "ALL" : new TreeSet<>(values).toString();
    }

    private static boolean isEmpty(Set<Long> values) {
        return values == null || values.isEmpty();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("effective scope hash calculation failed", ex);
        }
    }

    public String tenantId() { return tenantId; }
    public Long legalEntityId() { return legalEntityId; }
    public LocalDate asOf() { return asOf; }
    public YearMonth asOfMonth() { return YearMonth.from(asOf); }
    public String scopeType() { return scopeType; }
    public boolean organizationFullAccess() { return organizationFullAccess; }
    public boolean dataScoped() { return dataScoped; }
    public boolean salesDataScoped() { return salesDataScoped; }
    public Set<Long> organizationIds() { return organizationIds; }
    public Set<Long> directUserIds() { return directUserIds; }
    public Set<Long> userIds() { return userIds; }
    public Set<Long> engineerIds() { return engineerIds; }
    public Set<Long> projectIds() { return projectIds; }
    public Set<Long> contractIds() { return contractIds; }
    public Set<Long> customerIds() { return customerIds; }
    public Set<Long> invoiceIds() { return invoiceIds; }
    public Set<Long> proposalIds() { return proposalIds; }
    public String canonicalMembers() { return canonicalMembers; }
    public CopilotScopeContext scope() { return scope; }
    public String scopeHash() { return scope.scopeHash(); }

    public boolean allowsEngineer(Long id) { return allows(engineerIds, id); }
    public boolean allowsProject(Long id) { return allows(projectIds, id); }
    public boolean allowsContract(Long id) { return allows(contractIds, id); }
    public boolean allowsCustomer(Long id) { return allows(customerIds, id); }
    public boolean allowsInvoice(Long id) { return allows(invoiceIds, id); }
    public boolean allowsProposal(Long id) { return allows(proposalIds, id); }

    private static boolean allows(Set<Long> allowed, Long id) {
        return id != null && (allowed == null || allowed.contains(id));
    }
}
