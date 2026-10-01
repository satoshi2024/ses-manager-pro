package com.ses.service.ai.copilot.scope;

import com.ses.common.exception.BusinessException;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;
import java.util.TreeSet;

/** DataScopeとOrganizationScopeをquery開始時に一度だけ最終集合へ収束させる。 */
@Component
public class EffectiveScopeSnapshotFactory {
    public static final String POLICY_VERSION = "nf08-effective-scope-1";

    private final DataScopeService dataScopeService;
    private final OrganizationScopeService organizationScopeService;

    public EffectiveScopeSnapshotFactory(DataScopeService dataScopeService,
                                         OrganizationScopeService organizationScopeService) {
        this.dataScopeService = dataScopeService;
        this.organizationScopeService = organizationScopeService;
    }

    public EffectiveScopeSnapshot create(String tenantId, Long legalEntityId, LocalDate asOf) {
        if (tenantId == null || tenantId.isBlank() || legalEntityId == null || legalEntityId <= 0
                || asOf == null) {
            throw BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
        }

        boolean organizationFullAccess = organizationScopeService.hasFullAccess();
        boolean salesDataScoped = dataScopeService.isSalesDataScoped();
        // 営業専用DataScopeはisScoped()の実装上もtrueだが、最終集合の計算条件として
        // 明示的に含める。こうしてscope判定と集合snapshotが別々にずれないようにする。
        boolean dataScoped = dataScopeService.isScoped() || salesDataScoped;

        Set<Long> dataOrganizations = dataScoped ? nonNull(dataScopeService.allowedOrganizationIds(asOf)) : null;
        Set<Long> dataDirectUsers = null;
        Set<Long> dataUsers = null;
        Set<Long> dataEngineers = dataScoped ? nonNull(dataScopeService.allowedEngineerIds(asOf)) : null;
        Set<Long> dataProjects = dataScoped ? nonNull(dataScopeService.allowedProjectIds(asOf)) : null;
        Set<Long> dataContracts = dataScoped ? nonNull(dataScopeService.allowedContractIds(asOf)) : null;
        Set<Long> dataCustomers = dataScoped ? nonNull(dataScopeService.allowedCustomerIds(asOf)) : null;
        Set<Long> dataInvoices = null;
        Set<Long> dataProposals = dataScoped ? nonNull(dataScopeService.allowedProposalIds(asOf)) : null;

        Set<Long> orgOrganizations = !organizationFullAccess
                ? nonNull(organizationScopeService.allowedOrganizationIds(asOf)) : null;
        Set<Long> orgDirectUsers = !organizationFullAccess
                ? nonNull(organizationScopeService.allowedDirectUserIds(asOf)) : null;
        Set<Long> orgUsers = !organizationFullAccess
                ? nonNull(organizationScopeService.allowedUserIds(asOf)) : null;
        Set<Long> orgEngineers = !organizationFullAccess
                ? nonNull(organizationScopeService.allowedEngineerIds(asOf)) : null;
        Set<Long> orgProjects = !organizationFullAccess
                ? nonNull(organizationScopeService.allowedProjectIds(asOf)) : null;
        Set<Long> orgContracts = !organizationFullAccess
                ? nonNull(organizationScopeService.allowedContractIds(asOf)) : null;
        Set<Long> orgCustomers = !organizationFullAccess
                ? nonNull(organizationScopeService.allowedCustomerIds(asOf)) : null;
        Set<Long> orgInvoices = !organizationFullAccess
                ? nonNull(organizationScopeService.allowedInvoiceIds(asOf)) : null;
        Set<Long> orgProposals = !organizationFullAccess
                ? nonNull(organizationScopeService.allowedProposalIds(asOf)) : null;

        Set<Long> organizations = intersect(dataOrganizations, orgOrganizations);
        Set<Long> directUsers = intersect(dataDirectUsers, orgDirectUsers);
        Set<Long> users = intersect(dataUsers, orgUsers);
        Set<Long> engineers = intersect(dataEngineers, orgEngineers);
        Set<Long> projects = intersect(dataProjects, orgProjects);
        Set<Long> contracts = intersect(dataContracts, orgContracts);
        Set<Long> customers = intersect(dataCustomers, orgCustomers);
        Set<Long> invoices = intersect(dataInvoices, orgInvoices);
        Set<Long> proposals = intersect(dataProposals, orgProposals);

        boolean unrestricted = organizationFullAccess && !dataScoped;
        String scopeType = unrestricted ? "COMPANY_WIDE"
                : salesDataScoped ? "SALES_DATA_SCOPED"
                : !organizationFullAccess ? "ORGANIZATION_SCOPED" : "DATA_SCOPED";
        String members = unrestricted ? "ALL"
                : "organizations=" + canonical(organizations)
                + ";directUsers=" + canonical(directUsers)
                + ";users=" + canonical(users)
                + ";engineers=" + canonical(engineers)
                + ";projects=" + canonical(projects)
                + ";contracts=" + canonical(contracts)
                + ";customers=" + canonical(customers)
                + ";invoices=" + canonical(invoices)
                + ";proposals=" + canonical(proposals);
        boolean emptyPopulation = !unrestricted && organizationsEmpty(
                organizations, directUsers, users, engineers, projects, contracts,
                customers, invoices, proposals);
        String canonicalInput = "tenant=" + tenantId.trim()
                + "|legalEntity=" + legalEntityId
                + "|asOf=" + asOf
                + "|scopeType=" + scopeType
                + "|policy=" + POLICY_VERSION
                + "|members=" + members;
        return new EffectiveScopeSnapshot(tenantId.trim(), legalEntityId, asOf, scopeType,
                organizationFullAccess, dataScoped, salesDataScoped,
                organizations, directUsers, users, engineers, projects, contracts,
                customers, invoices, proposals, POLICY_VERSION, emptyPopulation, members,
                sha256(canonicalInput));
    }

    private static boolean organizationsEmpty(Set<Long> organizations, Set<Long> directUsers,
                                              Set<Long> users, Set<Long> engineers, Set<Long> projects,
                                              Set<Long> contracts, Set<Long> customers, Set<Long> invoices,
                                              Set<Long> proposals) {
        return isEmpty(organizations) && isEmpty(directUsers) && isEmpty(users)
                && isEmpty(engineers) && isEmpty(projects) && isEmpty(contracts)
                && isEmpty(customers) && isEmpty(invoices) && isEmpty(proposals);
    }

    private static boolean isEmpty(Set<Long> values) { return values == null || values.isEmpty(); }

    private static Set<Long> intersect(Set<Long> first, Set<Long> second) {
        if (first == null) return second;
        if (second == null) return first;
        Set<Long> result = new TreeSet<>(first);
        result.retainAll(second);
        return result;
    }

    private static Set<Long> nonNull(Set<Long> values) { return values == null ? Set.of() : new TreeSet<>(values); }

    private static String canonical(Set<Long> values) {
        return values == null ? "ALL" : new TreeSet<>(values).toString();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("effective scope hash calculation failed", ex);
        }
    }
}
