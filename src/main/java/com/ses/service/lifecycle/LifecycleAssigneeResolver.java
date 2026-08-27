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
        // 1. 個別指定の優先解決
        if (customAssignees != null && customAssignees.containsKey(tplTask.getTaskCode())) {
            Long customUserId = customAssignees.get(tplTask.getTaskCode());
            if (customUserId != null) {
                SysUser user = sysUserMapper.selectById(customUserId);
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
                        SysUser user = sysUserMapper.selectById(targetUserId);
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
                                    .eq(SysUser::getRole, ruleValue.trim())
                                    .eq(SysUser::getStatus, 1)
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
                                    .eq(UserOrganization::getOrganizationId, engineer.getOrganizationId())
                                    .isNotNull(UserOrganization::getManagerUserId)
                    );
                    if (userOrgs != null && !userOrgs.isEmpty()) {
                        Long mgrId = userOrgs.get(0).getManagerUserId();
                        SysUser manager = sysUserMapper.selectById(mgrId);
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
                            SysUser salesUser = sysUserMapper.selectById(primary.getSalesUserId());
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
                        SysUser engUser = sysUserMapper.selectById(link.getSysUserId());
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
                    SysUser applicant = sysUserMapper.selectById(applicantUserId);
                    if (applicant != null) {
                        resolved = ResolvedAssignee.builder()
                                .userId(applicant.getId())
                                .role(applicant.getRole())
                                .nameSnapshot(applicant.getRealName() != null ? applicant.getRealName() : applicant.getUsername())
                                .build();
                    }
                }
                break;
        }

        if (resolved == null) {
            if (tplTask.getIsMandatory() != null && tplTask.getIsMandatory() == 1) {
                throw BusinessException.of("error.lifecycle.assigneeResolutionFailed",
                        "タスク「" + tplTask.getTaskName() + "」の担当者を解決できませんでした (ルール: " + rule + ")");
            }
            // 任意タスクなら未アサインとして許容
            return ResolvedAssignee.builder()
                    .userId(null)
                    .role(null)
                    .nameSnapshot("未割当")
                    .build();
        }

        return resolved;
    }
}
