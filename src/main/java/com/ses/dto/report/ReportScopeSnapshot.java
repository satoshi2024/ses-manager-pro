package com.ses.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 生成時に解決した組織scope。runへJSON/hashとして保存する。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportScopeSnapshot {
    private String ownerType;
    private Long ownerId;
    private boolean companyWide;
    private List<Long> organizationIds;
    private List<Long> directUserIds;
    private String policyVersion;
    private String json;
    private String hash;
}
