package com.ses.service.attendance;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AttendanceMonth;
import com.ses.entity.Engineer;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.AttendanceMonthMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.OrganizationUnitMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.AttendanceService;
import org.junit.jupiter.api.AfterEach;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** R2-P1-03の月次法人snapshotをHRのlist/action共通母集団へ適用する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
class AttendanceMonthSnapshotScopeTest {

    @Autowired
    private AttendanceService attendanceService;
    @Autowired
    private AttendanceMonthMapper attendanceMonthMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private OrganizationUnitMapper organizationUnitMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private UserOrganizationMapper userOrganizationMapper;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 月次snapshot法人をHRのlistとclose認可の正にする() {
        OrganizationUnit companyA = organization("ATT-SNAPSHOT-A", 83001L);
        OrganizationUnit companyB = organization("ATT-SNAPSHOT-B", 83002L);
        organizationUnitMapper.insert(companyA);
        organizationUnitMapper.insert(companyB);
        Engineer engineer = Engineer.builder().fullName("ATT-SNAPSHOT-ENGINEER")
                .employmentType("正社員").status("Bench").organizationId(companyB.getId()).build();
        engineerMapper.insert(engineer);
        attendanceMonthMapper.insert(AttendanceMonth.builder()
                .engineerId(engineer.getId()).legalEntityId(companyA.getLegalEntityId())
                .organizationId(companyA.getId()).workMonth(LocalDate.of(2026, 8, 1))
                .scheduledMinutes(480).workedMinutes(480).regularMinutes(480)
                .overtimeMinutes(0).holidayMinutes(0).lateNightMinutes(0).leaveMinutes(0)
                .status("承認済").version(0).build());
        SysUser hrA = hr("att-snapshot-a-" + System.nanoTime(), companyA.getId());
        SysUser hrB = hr("att-snapshot-b-" + System.nanoTime(), companyB.getId());

        authenticate(hrB);
        assertEquals(0, attendanceService.management("2026-08").getMonths().size());
        assertThrows(BusinessException.class, () -> attendanceService.close(engineer.getId(), "2026-08"));

        authenticate(hrA);
        assertEquals(List.of(engineer.getId()), attendanceService.management("2026-08").getMonths()
                .stream().map(month -> month.getEngineerId()).toList());
        attendanceService.close(engineer.getId(), "2026-08");
        assertEquals("締め済", attendanceMonthMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AttendanceMonth>()
                        .eq(AttendanceMonth::getEngineerId, engineer.getId())
                        .eq(AttendanceMonth::getWorkMonth, LocalDate.of(2026, 8, 1))).getStatus());
    }

    private OrganizationUnit organization(String code, Long legalEntityId) {
        return OrganizationUnit.builder().tenantId(1L).legalEntityId(legalEntityId)
                .code(code).name(code).type("部門").validFrom(LocalDate.of(2026, 1, 1))
                .status("有効").version(0).build();
    }

    private SysUser hr(String username, Long organizationId) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword("pass");
        user.setRealName(username);
        user.setRole("HR");
        user.setStatus(1);
        sysUserMapper.insert(user);
        userOrganizationMapper.insert(UserOrganization.builder().userId(user.getId())
                .organizationId(organizationId).primaryFlag(1).validFrom(LocalDate.of(2026, 1, 1)).build());
        return user;
    }

    private void authenticate(SysUser user) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(user.getId()), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_HR"))));
    }
}
