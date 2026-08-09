package com.ses.service.attendance;

import com.ses.entity.Engineer;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerAccountingHistoryMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.security.OrganizationScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T070 R2-P1-04のmanager full-access sentinelと対象月末履歴を固定する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
class AttendanceManagerScopeTest {

    @Autowired
    private OrganizationScopeService organizationScopeService;

    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private UserOrganizationMapper userOrganizationMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private EngineerAccountingHistoryMapper historyMapper;

    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @Test
    void asOf所属履歴を使い現在所属へ巻き戻さない() {
        OrganizationUnit own = organization("ATT-MGR-OWN");
        OrganizationUnit moved = organization("ATT-MGR-MOVED");
        organizationUnitMapper.insert(own);
        organizationUnitMapper.insert(moved);
        var manager = new com.ses.entity.SysUser();
        manager.setUsername("att-manager-" + System.nanoTime());
        manager.setPassword("pass");
        manager.setRealName("manager");
        manager.setRole("マネージャー");
        manager.setStatus(1);
        sysUserMapper.insert(manager);
        userOrganizationMapper.insert(UserOrganization.builder().userId(manager.getId())
                .organizationId(own.getId()).primaryFlag(1).validFrom(LocalDate.of(2026, 1, 1)).build());
        Engineer engineer = Engineer.builder().fullName("ATT-MGR-ENGINEER").employmentType("正社員")
                .status("Bench").organizationId(moved.getId()).build();
        engineerMapper.insert(engineer);
        historyMapper.insert(com.ses.entity.EngineerAccountingHistory.builder().engineerId(engineer.getId())
                .organizationId(own.getId()).organizationHistoryStatus("KNOWN")
                .validFrom(LocalDate.of(2026, 1, 1)).validTo(LocalDate.of(2026, 6, 30)).build());
        historyMapper.insert(com.ses.entity.EngineerAccountingHistory.builder().engineerId(engineer.getId())
                .organizationId(moved.getId()).organizationHistoryStatus("KNOWN")
                .validFrom(LocalDate.of(2026, 7, 1)).validTo(null).build());

        authenticate(manager.getId());
        assertFalse(organizationScopeService.hasFullAccess());
        assertTrue(engineerAccountLinkMapper.selectEngineerIdsByOrganizationScope(
                List.of(own.getId()), List.of(), LocalDate.of(2026, 6, 30)).contains(engineer.getId()));
        assertFalse(engineerAccountLinkMapper.selectEngineerIdsByOrganizationScope(
                List.of(own.getId()), List.of(), LocalDate.of(2026, 7, 31)).contains(engineer.getId()));
        SecurityContextHolder.clearContext();
    }

    private OrganizationUnit organization(String code) {
        return OrganizationUnit.builder().tenantId(1L).legalEntityId(82001L).code(code)
                .name(code).type("部門").validFrom(LocalDate.of(2026, 1, 1)).status("有効").version(0).build();
    }

    private void authenticate(Long userId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                String.valueOf(userId), "n/a", List.of(new SimpleGrantedAuthority("ROLE_マネージャー"))));
    }
}
