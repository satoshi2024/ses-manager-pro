package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.entity.*;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.EngineerSalesService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * ライフサイクルタスク担当者解決コンポーネント
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LifecycleAssigneeResolver {

    private final SysUserMapper sysUserMapper;
    private final OrganizationUnitMapper organizationUnitMapper;
    private final com.ses.mapper.UserOrganizationMapper userOrganizationMapper;
    private final EngineerSalesService engineerSalesService;
    private final EngineerAccountLinkService engineerAccountLinkService;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResolvedAssignee {
        private Long userId;
        private String role;
        private String nameSnapshot;
    }

    /**
     * テンプレートタスク定義と要員情報から担当者を解決する。
     */
    public ResolvedAssignee resolve(LifecycleTemplateTask tplTask,
                                    Engineer engineer,
                                    Long applicantUserId,
                                    Map<String, Long> customAssignees) {
        String tenantId = com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext();
        if (engineer == null || !tenantId.equals(engineer.getTenantId())) {
            throw BusinessException.of(403, "TENANT_CONTEXT_MISMATCH");
        }
        // 1. 個別指定の優先解決
        if (customAssignees != null && customAssignees.containsKey(tplTask.getTaskCode())) {
            Long customUserId = customAssignees.get(tplTask.getTaskCode());
            if (customUserId != null) {
                SysUser user = sysUserMapper.selectByIdAndTenant(customUserId, tenantId);
                if (user != null && user.getStatus() != null && user.getStatus() == 1) {
                    return ResolvedAssignee.builder()
                            .userId(user.getId())
                            .role(user.getRole())
                            .nameSnapshot(user.getRealName() != null ? user.getRealName() : user.getUsername())
                            .build();
                }
            }
        }

        String rule = tplTask.getAssigneeRule();
        String ruleValue = tplTask.getAssigneeRuleValue();

        if (rule == null) {
            rule = "APPLICANT";
        }

        ResolvedAssignee resolved = null;

        switch (rule) {
            case "SPECIFIC_USER":
                if (ruleValue != null && !ruleValue.isBlank()) {
                    try {
                        Long targetUserId = Long.parseLong(ruleValue.trim());
                        SysUser user = sysUserMapper.selectByIdAndTenant(targetUserId, tenantId);
                        if (user != null && user.getStatus() != null && user.getStatus() == 1) {
                            resolved = ResolvedAssignee.builder()
                                    .userId(user.getId())
                                    .role(user.getRole())
                                    .nameSnapshot(user.getRealName() != null ? user.getRealName() : user.getUsername())
                                    .build();
                        }
                    } catch (NumberFormatException e) {
                        log.warn("Invalid specific user id: {}", ruleValue);
                    }
                }
                break;

            case "ROLE":
                if (ruleValue != null && !ruleValue.isBlank()) {
                    List<SysUser> users = sysUserMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUser>()
                                    .eq(SysUser::getTenantId, tenantId)
                                    .eq(SysUser::getRole, ruleValue.trim())
                                    .eq(SysUser::getStatus, 1)
                                    .orderByAsc(SysUser::getId)
                    );
                    if (users != null && !users.isEmpty()) {
                        SysUser activeUser = users.get(0);
                        resolved = ResolvedAssignee.builder()
                                .userId(activeUser.getId())
                                .role(ruleValue.trim())
                                .nameSnapshot(activeUser.getRealName() != null ? activeUser.getRealName() : activeUser.getUsername())
                                .build();
                    } else {
                        resolved = ResolvedAssignee.builder()
                                .userId(null)
                                .role(ruleValue.trim())
                                .nameSnapshot(ruleValue.trim() + "担当")
                                .build();
                    }
                }
                break;

            case "ORGANIZATION_MANAGER":
                if (engineer.getOrganizationId() != null) {
                    List<UserOrganization> userOrgs = userOrganizationMapper.selectList(
                            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<UserOrganization>()
                                    .eq(UserOrganization::getTenantId, tenantId)
                                    .eq(UserOrganization::getOrganizationId, engineer.getOrganizationId())
                                    .orderByAsc(UserOrganization::getId)
                                    .isNotNull(UserOrganization::getManagerUserId)
                    );
                    if (userOrgs != null && !userOrgs.isEmpty()) {
                        Long mgrId = userOrgs.get(0).getManagerUserId();
                        SysUser manager = sysUserMapper.selectByIdAndTenant(mgrId, tenantId);
                        if (manager != null && manager.getStatus() != null && manager.getStatus() == 1) {
                            resolved = ResolvedAssignee.builder()
                                    .userId(manager.getId())
                                    .role(manager.getRole())
                                    .nameSnapshot(manager.getRealName() != null ? manager.getRealName() : manager.getUsername())
                                    .build();
                        }
                    }
                }
                break;

            case "PRIMARY_SALES":
                if (engineer.getId() != null) {
                    List<EngineerSalesDto> sales = engineerSalesService.listActive(engineer.getId());
                    if (sales != null && !sales.isEmpty()) {
                        EngineerSalesDto primary = sales.stream()
                                .filter(s -> s.getPrimaryFlag() != null && s.getPrimaryFlag() == 1)
                                .findFirst()
                                .orElse(sales.get(0));
                        if (primary.getSalesUserId() != null) {
                            SysUser salesUser = sysUserMapper.selectByIdAndTenant(primary.getSalesUserId(), tenantId);
                            if (salesUser != null && salesUser.getStatus() != null && salesUser.getStatus() == 1) {
                                resolved = ResolvedAssignee.builder()
                                        .userId(salesUser.getId())
                                        .role(salesUser.getRole())
                                        .nameSnapshot(salesUser.getRealName() != null ? salesUser.getRealName() : salesUser.getUsername())
                                        .build();
                            }
                        }
                    }
                }
                break;

            case "ENGINEER_SELF":
                if (engineer.getId() != null) {
                    EngineerAccountLink link = engineerAccountLinkService.findByEngineerId(engineer.getId());
                    if (link != null && link.getSysUserId() != null) {
                        SysUser engUser = sysUserMapper.selectByIdAndTenant(link.getSysUserId(), tenantId);
                        if (engUser != null && engUser.getStatus() != null && engUser.getStatus() == 1) {
                            resolved = ResolvedAssignee.builder()
                                    .userId(engUser.getId())
                                    .role(engUser.getRole())
                                    .nameSnapshot(engUser.getRealName() != null ? engUser.getRealName() : engUser.getUsername())
                                    .build();
                        }
                    }
                }
                break;

            case "APPLICANT":
            default:
                if (applicantUserId != null) {
                    SysUser appUser = sysUserMapper.selectByIdAndTenant(applicantUserId, tenantId);
                    if (appUser != null && appUser.getStatus() != null && appUser.getStatus() == 1) {
                        resolved = ResolvedAssignee.builder()
                                .userId(appUser.getId())
                                .role(appUser.getRole())
                                .nameSnapshot(appUser.getRealName() != null ? appUser.getRealName() : appUser.getUsername())
                                .build();
                    }
                }
                break;
        }

        if (resolved == null || resolved.getUserId() == null) {
            if (tplTask.getIsMandatory() != null && tplTask.getIsMandatory() == 1) {
                throw BusinessException.of(400, "error.lifecycle.assigneeResolutionFailed",
                        "必須タスク(" + tplTask.getTaskName() + ")の担当者(" + rule + (ruleValue != null ? ":" + ruleValue : "") + ")を解決できませんでした");
            }
        }

        if (resolved == null) {
            resolved = ResolvedAssignee.builder()
                    .userId(null)
                    .role("SYSTEM")
                    .nameSnapshot("未割当")
                    .build();
        }

        return resolved;
    }
}
