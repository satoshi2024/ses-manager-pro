package com.ses.service.attendance;

import com.ses.entity.Engineer;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** T070 R2-P1-03のHR法人scopeとUNKNOWN/履歴境界をSQLで検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
class AttendanceScopeResolverTest {

    @Autowired
    private AttendanceScopeResolver resolver;

    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;

    @Autowired
    private UserOrganizationMapper userOrganizationMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void HRは担当法人だけを一覧母集団にする() {
        OrganizationUnit companyA = organization("ATT-HR-A", 81001L);
        OrganizationUnit companyB = organization("ATT-HR-B", 81002L);
        organizationUnitMapper.insert(companyA);
        organizationUnitMapper.insert(companyB);
        SysUser hr = user("att-hr-" + System.nanoTime(), "HR");
        userOrganizationMapper.insert(UserOrganization.builder().userId(hr.getId())
                .organizationId(companyA.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());

        Engineer engineerA = engineer("ATT-A", companyA.getId());
        Engineer engineerB = engineer("ATT-B", companyB.getId());

        assertEquals(Set.of(engineerA.getId()), resolver.allowedHrEngineerIds(hr.getId(),
                LocalDate.of(2026, 8, 31)));
        assertEquals(new AttendanceScopeSnapshot(81001L, companyA.getId()),
                resolver.requireSnapshot(engineerA.getId(), null, LocalDate.of(2026, 8, 31)));
        // B法人のsnapshotは解決できても、HR Aの母集団へは混入しない。
        assertEquals(0, resolver.allowedHrEngineerIds(hr.getId(), LocalDate.of(2026, 8, 31))
                .contains(engineerB.getId()) ? 1 : 0);
    }

    @Test
    void 履歴ありNULLは連携ユーザー所属へfallbackせず判定不能にする() {
        OrganizationUnit companyA = organization("ATT-HR-NULL", 81003L);
        organizationUnitMapper.insert(companyA);
        SysUser hr = user("att-hr-null-" + System.nanoTime(), "HR");
        userOrganizationMapper.insert(UserOrganization.builder().userId(hr.getId())
                .organizationId(companyA.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());
        Engineer engineer = engineer("ATT-NULL", companyA.getId());
        jdbcTemplate.update("INSERT INTO t_engineer_accounting_history "
                        + "(engineer_id, organization_id, organization_history_status, valid_from, valid_to) "
                        + "VALUES (?, NULL, 'KNOWN', '2026-01-01', NULL)", engineer.getId());

        assertNull(resolver.resolveSnapshot(engineer.getId(), hr.getId(), LocalDate.of(2026, 8, 31)));
        assertEquals(Set.of(), resolver.allowedHrEngineerIds(hr.getId(), LocalDate.of(2026, 8, 31)));
    }

    private OrganizationUnit organization(String code, Long legalEntityId) {
        return OrganizationUnit.builder().tenantId(1L).legalEntityId(legalEntityId)
                .code(code).name(code).type("部門").validFrom(LocalDate.of(2026, 1, 1))
                .status("有効").version(0).build();
    }

    private SysUser user(String username, String role) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword("pass");
        user.setRealName(username);
        user.setRole(role);
        user.setStatus(1);
        sysUserMapper.insert(user);
        return user;
    }

    private Engineer engineer(String name, Long organizationId) {
        Engineer engineer = Engineer.builder().fullName(name + System.nanoTime())
                .employmentType("正社員").status("Bench").organizationId(organizationId).build();
        engineerMapper.insert(engineer);
        return engineer;
    }
}
