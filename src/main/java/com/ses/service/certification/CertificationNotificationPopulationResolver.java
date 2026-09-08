package com.ses.service.certification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.LifecycleCase;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 資格期限通知のdispatch時点母集団を一箇所で解決する。
 * Engineer.statusを通知除外の根拠にせず、NF-01 lifecycleとaccount linkを優先する。
 */
@Service
public class CertificationNotificationPopulationResolver {

    private final LifecycleCaseMapper lifecycleCaseMapper;
    private final EngineerAccountLinkMapper accountLinkMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final SysUserMapper sysUserMapper;
    private final CertificationLifecycleStateResolver lifecycleStateResolver;

    public CertificationNotificationPopulationResolver(LifecycleCaseMapper lifecycleCaseMapper,
                                                       EngineerAccountLinkMapper accountLinkMapper,
                                                       UserOrganizationMapper userOrganizationMapper,
                                                       SysUserMapper sysUserMapper) {
        this(lifecycleCaseMapper, accountLinkMapper, userOrganizationMapper, sysUserMapper,
                new CertificationLifecycleStateResolver());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CertificationNotificationPopulationResolver(LifecycleCaseMapper lifecycleCaseMapper,
                                                       EngineerAccountLinkMapper accountLinkMapper,
                                                       UserOrganizationMapper userOrganizationMapper,
                                                       SysUserMapper sysUserMapper,
                                                       CertificationLifecycleStateResolver lifecycleStateResolver) {
        this.lifecycleCaseMapper = lifecycleCaseMapper;
        this.accountLinkMapper = accountLinkMapper;
        this.userOrganizationMapper = userOrganizationMapper;
        this.sysUserMapper = sysUserMapper;
        this.lifecycleStateResolver = lifecycleStateResolver;
    }

    public Population resolve(Long engineerId, LocalDate asOf) {
        return resolveInternal(null, engineerId, asOf, false);
    }

    /** tenantを全母集団queryへ渡す資格期限通知用resolver。 */
    public Population resolve(String tenantId, Long engineerId, LocalDate asOf) {
        if (tenantId == null || tenantId.isBlank()) {
            return Population.empty();
        }
        return resolveInternal(tenantId, engineerId, asOf, true);
    }

    private Population resolveInternal(String tenantId, Long engineerId, LocalDate asOf, boolean tenantAware) {
        if (engineerId == null || asOf == null) {
            return Population.empty();
        }
        List<LifecycleCase> cases = tenantAware
                ? lifecycleCaseMapper.selectByEngineerIdAndTenant(engineerId, tenantId)
                : lifecycleCaseMapper.selectList(new LambdaQueryWrapper<LifecycleCase>()
                    .eq(LifecycleCase::getEngineerId, engineerId)
                    .orderByAsc(LifecycleCase::getAnchorDate).orderByAsc(LifecycleCase::getId));
        CertificationLifecycleStateResolver.Resolution lifecycle = lifecycleStateResolver.resolve(cases, asOf);
        if ("RESIGNED".equals(lifecycle.state())) {
            return populationFor(tenantId, cases, engineerId, asOf, PopulationCase.RESIGNATION, false, tenantAware);
        }
        if ("ON_LEAVE".equals(lifecycle.state())) {
            return populationFor(tenantId, cases, engineerId, asOf, PopulationCase.LEAVE, false, tenantAware);
        }
        return populationFor(tenantId, cases, engineerId, asOf,
                lifecycle.reinstatement() ? PopulationCase.REINSTATEMENT : PopulationCase.NORMAL,
                lifecycle.reinstatement(), tenantAware);
    }

    private Population populationFor(List<LifecycleCase> cases, Long engineerId, LocalDate asOf,
                                     PopulationCase state, boolean reinstatement) {
        return populationFor(null, cases, engineerId, asOf, state, reinstatement, false);
    }

    private Population populationFor(String tenantId, List<LifecycleCase> cases, Long engineerId, LocalDate asOf,
                                     PopulationCase state, boolean reinstatement, boolean tenantAware) {
        EngineerAccountLink link = tenantAware
                ? accountLinkMapper.selectByEngineerIdAndTenant(engineerId, tenantId)
                : accountLinkMapper.selectByEngineerId(engineerId);
        SysUser account = link == null ? null : (tenantAware
                ? sysUserMapper.selectByIdAndTenant(link.getSysUserId(), tenantId)
                : sysUserMapper.selectById(link.getSysUserId()));
        boolean accountActive = account != null && Integer.valueOf(1).equals(account.getStatus());
        boolean allowSelf = accountActive && state != PopulationCase.RESIGNATION && state != PopulationCase.LEAVE;

        Set<Long> managerIds = new LinkedHashSet<>();
        if (state != PopulationCase.RESIGNATION && link != null) {
            List<UserOrganization> assignments = tenantAware
                    ? userOrganizationMapper.selectByUserAndTenant(link.getSysUserId(), tenantId).stream()
                        .filter(item -> Integer.valueOf(1).equals(item.getPrimaryFlag()))
                        .filter(item -> item.getValidFrom() == null || !item.getValidFrom().isAfter(asOf))
                        .filter(item -> item.getValidTo() == null || !item.getValidTo().isBefore(asOf))
                        .toList()
                    : userOrganizationMapper.selectList(
                    new LambdaQueryWrapper<UserOrganization>()
                            .eq(UserOrganization::getUserId, link.getSysUserId())
                            .eq(UserOrganization::getPrimaryFlag, 1)
                            .eq(UserOrganization::getDeletedFlag, 0)
                            .le(UserOrganization::getValidFrom, asOf)
                            .and(wrapper -> wrapper.isNull(UserOrganization::getValidTo)
                                    .or().ge(UserOrganization::getValidTo, asOf))
                            .orderByDesc(UserOrganization::getId));
            for (UserOrganization assignment : assignments) {
                Long managerId = assignment.getManagerUserId();
                if (managerId == null || !isActiveUser(managerId, tenantId, tenantAware)) {
                    continue;
                }
                managerIds.add(managerId);
                break;
            }
        }

        List<SysUser> hrUsers = tenantAware ? sysUserMapper.selectActiveByRoleAndTenant("HR", tenantId)
                : sysUserMapper.selectList(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getRole, "HR")
                        .eq(SysUser::getStatus, 1)
                        .eq(SysUser::getDeletedFlag, 0)
                        .orderByAsc(SysUser::getId));
        List<Long> hrIds = hrUsers.stream().map(SysUser::getId).filter(java.util.Objects::nonNull).toList();

        List<Long> recipients = new ArrayList<>();
        if (allowSelf) {
            recipients.add(account.getId());
        }
        recipients.addAll(managerIds);
        recipients.addAll(hrIds);
        return new Population(state, allowSelf ? account.getId() : null,
                List.copyOf(managerIds), List.copyOf(hrIds), List.copyOf(new LinkedHashSet<>(recipients)),
                reinstatement, account != null);
    }

    private boolean isActiveUser(Long userId, String tenantId, boolean tenantAware) {
        SysUser user = tenantAware ? sysUserMapper.selectByIdAndTenant(userId, tenantId)
                : sysUserMapper.selectById(userId);
        return user != null && Integer.valueOf(1).equals(user.getStatus());
    }

    private LifecycleCase latestCompleted(List<LifecycleCase> cases, String type, LocalDate asOf) {
        return cases.stream()
                .filter(item -> type.equals(item.getLifecycleType()))
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .filter(item -> !after(item.getAnchorDate(), asOf))
                .filter(item -> item.getCompletedAt() == null || !item.getCompletedAt().toLocalDate().isAfter(asOf))
                .reduce((left, right) -> isAfter(right, left) ? right : left)
                .orElse(null);
    }

    private boolean isAfter(LifecycleCase left, LifecycleCase right) {
        if (left == null) {
            return false;
        }
        if (right == null) {
            return true;
        }
        LocalDate leftDate = left.getCompletedAt() == null ? left.getAnchorDate() : left.getCompletedAt().toLocalDate();
        LocalDate rightDate = right.getCompletedAt() == null ? right.getAnchorDate() : right.getCompletedAt().toLocalDate();
        return leftDate != null && (rightDate == null || leftDate.isAfter(rightDate)
                || (leftDate.equals(rightDate) && value(left.getId()) > value(right.getId())));
    }

    private boolean after(LocalDate value, LocalDate asOf) {
        return value != null && value.isAfter(asOf);
    }

    private int value(Long value) {
        return value == null ? 0 : value > Integer.MAX_VALUE ? Integer.MAX_VALUE : value.intValue();
    }

    public enum PopulationCase {
        NORMAL,
        LEAVE,
        RESIGNATION,
        REINSTATEMENT
    }

    public record Population(PopulationCase lifecycleCase, Long selfUserId, List<Long> managerUserIds,
                             List<Long> hrUserIds, List<Long> recipientUserIds,
                             boolean reinstatement, boolean accountLinked) {
        static Population empty() {
            return new Population(PopulationCase.NORMAL, null, List.of(), List.of(), List.of(), false, false);
        }
    }
}
