package com.ses.dto.report;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Collections;

/** 生成時に解決した組織scope。runへJSON/hashとして保存する。 */
@Data
@NoArgsConstructor
public class ReportScopeSnapshot {
    private String ownerType;
    private Long ownerId;
    private boolean companyWide;
    private List<Long> organizationIds;
    private List<Long> directUserIds;
    private String policyVersion;
    private String json;
    private String hash;
    /** 正本serviceへ渡すため、生成時点で解決したID母集団も固定する。 */
    private List<Long> engineerIds = List.of();
    private List<Long> contractIds = List.of();
    private List<Long> invoiceIds = List.of();

    public ReportScopeSnapshot(String ownerType, Long ownerId, boolean companyWide,
                               List<Long> organizationIds, List<Long> directUserIds,
                               String policyVersion, String json, String hash) {
        this(ownerType, ownerId, companyWide, organizationIds, directUserIds,
                policyVersion, json, hash, List.of(), List.of(), List.of());
    }

    public ReportScopeSnapshot(String ownerType, Long ownerId, boolean companyWide,
                               List<Long> organizationIds, List<Long> directUserIds,
                               String policyVersion, String json, String hash,
                               List<Long> engineerIds, List<Long> contractIds,
                               List<Long> invoiceIds) {
        this.ownerType = ownerType;
        this.ownerId = ownerId;
        this.companyWide = companyWide;
        this.organizationIds = immutable(organizationIds);
        this.directUserIds = immutable(directUserIds);
        this.policyVersion = policyVersion;
        this.json = json;
        this.hash = hash;
        this.engineerIds = immutable(engineerIds);
        this.contractIds = immutable(contractIds);
        this.invoiceIds = immutable(invoiceIds);
    }

    private static List<Long> immutable(List<Long> values) {
        return values == null ? List.of() : Collections.unmodifiableList(List.copyOf(values));
    }
}
