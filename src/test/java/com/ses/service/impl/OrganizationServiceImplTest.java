package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.SysUserMapper;
import com.ses.service.OrganizationService;
import com.ses.service.ManagementBudgetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 組織階層・所属期間のF1業務ルール検証。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
class OrganizationServiceImplTest {

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private ManagementBudgetService managementBudgetService;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Test
    void 親子登録と子孫取得ができる() {
        OrganizationUnit division = organization("DIV-01", "開発事業部", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(division);
        OrganizationUnit team = organization("TEAM-01", "第一課", division.getId(),
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(team);

        assertEquals(2, organizationService.descendantIds(division.getId(),
                LocalDate.of(2026, 7, 1)).size());
    }

    @Test
    void 階層循環と同一コードの期間重複を拒否する() {
        OrganizationUnit parent = organization("ORG-01", "親", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(parent);
        OrganizationUnit child = organization("ORG-02", "子", parent.getId(),
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(child);

        parent.setParentId(child.getId());
        assertThrows(BusinessException.class, () -> organizationService.updateById(parent));

        OrganizationUnit overlap = organization("ORG-01", "同一コード", null,
                LocalDate.of(2026, 6, 1), null);
        assertThrows(BusinessException.class, () -> organizationService.save(overlap));
    }

    @Test
    void 主所属の期間重複と参照中削除を拒否し無効化は許可する() {
        OrganizationUnit first = organization("ORG-PRIMARY-1", "第一部", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(first);
        OrganizationUnit second = organization("ORG-PRIMARY-2", "第二部", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(second);

        Long userId = sysUserMapper.selectByUsername("admin").getId();
        organizationService.assignUser(UserOrganization.builder()
                .userId(userId).organizationId(first.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());

        SysUser member = new SysUser();
        member.setUsername("org-member");
        member.setPassword("pass");
        member.setRealName("組織メンバー");
        member.setRole("HR");
        member.setStatus(1);
        sysUserMapper.insert(member);
        organizationService.assignUser(UserOrganization.builder()
                .userId(member.getId()).organizationId(second.getId()).primaryFlag(0)
                .managerUserId(userId).validFrom(LocalDate.of(2026, 1, 1)).build());
        assertEquals(userId, organizationService.listUserOrganizations(member.getId(),
                LocalDate.of(2026, 7, 1)).get(0).getManagerUserId());

        assertThrows(BusinessException.class, () -> organizationService.assignUser(UserOrganization.builder()
                .userId(userId).organizationId(second.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 6, 1)).build()));
        assertThrows(BusinessException.class, () -> organizationService.removeById(first.getId()));

        assertTrue(organizationService.deactivate(first.getId()));
        assertEquals("無効", organizationService.getById(first.getId()).getStatus());
    }

    @Test
    void 予算upsertはversionを検査する() {
        OrganizationUnit unit = organization("ORG-BUDGET", "予算部", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(unit);
        var budget = com.ses.entity.ManagementBudget.builder()
                .organizationId(unit.getId()).budgetMonth(LocalDate.of(2026, 7, 1))
                .revenue(BigDecimal.valueOf(1000000)).grossProfit(BigDecimal.valueOf(300000))
                .utilizationCount(5).hireCount(1).build();
        managementBudgetService.upsert(budget, null);
        assertEquals(0, budget.getVersion());

        budget.setRevenue(BigDecimal.valueOf(1100000));
        managementBudgetService.upsert(budget, 0);
        assertEquals(1, budget.getVersion());

        assertThrows(BusinessException.class, () -> managementBudgetService.upsert(budget, 0));
    }

    private OrganizationUnit organization(String code, String name, Long parentId,
                                          LocalDate validFrom, LocalDate validTo) {
        return OrganizationUnit.builder()
                .code(code).name(name).type("部")
                .parentId(parentId).validFrom(validFrom).validTo(validTo)
                .status("有効").version(0).build();
    }
}
