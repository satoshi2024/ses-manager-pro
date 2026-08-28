package com.ses.service.certification;

import com.ses.entity.EngineerAccountLink;
import com.ses.entity.LifecycleCase;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CertificationNotificationPopulationResolverTest {

    @Mock private LifecycleCaseMapper lifecycleCaseMapper;
    @Mock private EngineerAccountLinkMapper accountLinkMapper;
    @Mock private UserOrganizationMapper userOrganizationMapper;
    @Mock private SysUserMapper sysUserMapper;

    @Test
    void normalはselfとasOf時点のmanagerとactiveHrを同一母集団で返す() {
        stubAccountAndManager();
        SysUser hr = user(901L, "HR");
        when(sysUserMapper.selectList(any())).thenReturn(List.of(hr));

        CertificationNotificationPopulationResolver.Population result = resolver().resolve(10L, date());

        assertEquals(501L, result.selfUserId());
        assertEquals(List.of(900L), result.managerUserIds());
        assertEquals(List.of(901L), result.hrUserIds());
        assertEquals(List.of(501L, 900L, 901L), result.recipientUserIds());
        assertFalse(result.reinstatement());
    }

    @Test
    void 休職中は本人へ送らずmanagerとhrだけを返す() {
        stubAccountAndManager();
        when(lifecycleCaseMapper.selectList(any())).thenReturn(List.of(
                lifecycle(1L, "LEAVE", "ACTIVE", date(), null)));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user(901L, "HR")));

        CertificationNotificationPopulationResolver.Population result = resolver().resolve(10L, date());

        assertEquals(CertificationNotificationPopulationResolver.PopulationCase.LEAVE, result.lifecycleCase());
        assertNull(result.selfUserId());
        assertEquals(List.of(900L, 901L), result.recipientUserIds());
    }

    @Test
    void 退職完了は旧managerと本人を除外しhrだけを返す() {
        stubAccountAndManager();
        when(lifecycleCaseMapper.selectList(any())).thenReturn(List.of(
                lifecycle(1L, "RESIGNATION", "COMPLETED", date().minusDays(1),
                        LocalDateTime.of(2026, 8, 27, 10, 0))));
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user(901L, "HR")));

        CertificationNotificationPopulationResolver.Population result = resolver().resolve(10L, date());

        assertEquals(CertificationNotificationPopulationResolver.PopulationCase.RESIGNATION, result.lifecycleCase());
        assertTrue(result.recipientUserIds().equals(List.of(901L)));
        assertTrue(result.managerUserIds().isEmpty());
    }

    @Test
    void 復職当日はreinstatementとして本人と現managerへ一度だけ渡す() {
        stubAccountAndManager();
        when(lifecycleCaseMapper.selectList(any())).thenReturn(List.of(
                lifecycle(1L, "LEAVE", "COMPLETED", date().minusDays(10),
                        LocalDateTime.of(2026, 8, 20, 10, 0)),
                lifecycle(2L, "REINSTATEMENT", "COMPLETED", date(),
                        LocalDateTime.of(2026, 8, 28, 10, 0))));
        when(sysUserMapper.selectList(any())).thenReturn(List.of());

        CertificationNotificationPopulationResolver.Population result = resolver().resolve(10L, date());

        assertEquals(CertificationNotificationPopulationResolver.PopulationCase.REINSTATEMENT, result.lifecycleCase());
        assertTrue(result.reinstatement());
        assertEquals(List.of(501L, 900L), result.recipientUserIds());
    }

    @Test
    void account未linkではselfを作らずmanager変更前の推測もしない() {
        when(lifecycleCaseMapper.selectList(any())).thenReturn(List.of());
        when(accountLinkMapper.selectByEngineerId(10L)).thenReturn(null);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(user(901L, "HR")));

        CertificationNotificationPopulationResolver.Population result = resolver().resolve(10L, date());

        assertNull(result.selfUserId());
        assertTrue(result.managerUserIds().isEmpty());
        assertEquals(List.of(901L), result.recipientUserIds());
        assertFalse(result.accountLinked());
    }

    private CertificationNotificationPopulationResolver resolver() {
        return new CertificationNotificationPopulationResolver(lifecycleCaseMapper, accountLinkMapper,
                userOrganizationMapper, sysUserMapper);
    }

    private void stubAccountAndManager() {
        when(lifecycleCaseMapper.selectList(any())).thenReturn(List.of());
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(10L);
        link.setSysUserId(501L);
        lenient().when(accountLinkMapper.selectByEngineerId(10L)).thenReturn(link);
        lenient().when(sysUserMapper.selectById(501L)).thenReturn(user(501L, "要員"));
        lenient().when(sysUserMapper.selectById(900L)).thenReturn(user(900L, "マネージャー"));
        UserOrganization assignment = new UserOrganization();
        assignment.setManagerUserId(900L);
        assignment.setPrimaryFlag(1);
        assignment.setValidFrom(LocalDate.of(2026, 1, 1));
        lenient().when(userOrganizationMapper.selectList(any())).thenReturn(List.of(assignment));
    }

    private SysUser user(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole(role);
        user.setStatus(1);
        return user;
    }

    private LifecycleCase lifecycle(Long id, String type, String status, LocalDate anchor,
                                    LocalDateTime completedAt) {
        LifecycleCase lifecycle = new LifecycleCase();
        lifecycle.setId(id);
        lifecycle.setEngineerId(10L);
        lifecycle.setLifecycleType(type);
        lifecycle.setStatus(status);
        lifecycle.setAnchorDate(anchor);
        lifecycle.setCompletedAt(completedAt);
        return lifecycle;
    }

    private LocalDate date() {
        return LocalDate.of(2026, 8, 28);
    }
}
