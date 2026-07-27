package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.SysUserMapper;
import com.ses.service.OrganizationService;
import com.ses.service.ManagementBudgetService;
import com.ses.service.CostCenterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private CostCenterService costCenterService;

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

        // 在籍者を残したまま無効化すると、その所属者は存在しない組織に所属したままになる。
        assertThrows(BusinessException.class, () -> organizationService.deactivate(first.getId()));

        UserOrganization active = organizationService.listUserOrganizations(userId, LocalDate.of(2026, 7, 1)).get(0);
        assertTrue(organizationService.releaseAssignment(active.getId(), LocalDate.of(2026, 6, 30),
                active.getVersion()));
        assertTrue(organizationService.deactivate(first.getId()));
        assertEquals("無効", organizationService.getById(first.getId()).getStatus());
    }

    /** 属性の編集で組織IDが変わると所属・原価部門・予算・snapshotが全部旧IDに取り残される。 */
    @Test
    void 組織の属性更新は同じIDのままversionCASで行う() {
        OrganizationUnit unit = organization("ORG-RENAME", "旧名称", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(unit);
        Long originalId = unit.getId();

        OrganizationUnit patch = organization("ORG-RENAME", "新名称", null,
                LocalDate.of(2026, 1, 1), null);
        patch.setId(originalId);
        assertTrue(organizationService.updateOrganization(patch, 0));

        assertEquals("新名称", organizationService.getById(originalId).getName());
        assertEquals(1, organizationService.getById(originalId).getVersion());
        // 版番号が古いままの2回目は409相当で弾く。
        assertThrows(BusinessException.class, () -> organizationService.updateOrganization(patch, 0));
    }

    /** 統合は「生きている参照」を統合先へ移し、過去実績(snapshot)は動かさない。 */
    @Test
    void 統合は子組織所属原価部門を統合先へ付け替える() {
        OrganizationUnit source = organization("ORG-MERGE-SRC", "統合元", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(source);
        OrganizationUnit target = organization("ORG-MERGE-DST", "統合先", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(target);
        OrganizationUnit child = organization("ORG-MERGE-CHILD", "統合元の課", source.getId(),
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(child);

        Long userId = sysUserMapper.selectByUsername("admin").getId();
        organizationService.assignUser(UserOrganization.builder()
                .userId(userId).organizationId(source.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());

        assertTrue(organizationService.merge(source.getId(), target.getId(), 0));

        assertEquals(target.getId(), organizationService.getById(child.getId()).getParentId());
        // 統合日前は旧所属を維持し、統合日以後だけ統合先へ遷移する。
        assertEquals(source.getId(), organizationService.listUserOrganizations(userId,
                LocalDate.of(2026, 7, 1)).get(0).getOrganizationId());
        assertEquals(target.getId(), organizationService.listUserOrganizations(userId,
                LocalDate.now()).get(0).getOrganizationId());
        assertEquals("無効", organizationService.getById(source.getId()).getStatus());
        assertEquals(target.getId(), organizationService.getById(source.getId()).getMergedInto());
        // 統合済み組織の名前は引けること。過去snapshotの組織別合計を突合するのに必要(R4)。
        assertEquals("統合元", organizationService.namesByIds(java.util.List.of(source.getId())).get(source.getId()));
    }

    /** 退職・停止時に有効な所属が残ると、退職者が組織scopeと部門損益の帰属に居座り続ける。 */
    @Test
    void 退職時に有効な所属と上長参照を閉じる() {
        OrganizationUnit unit = organization("ORG-RETIRE", "退職部", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(unit);

        SysUser leaver = new SysUser();
        leaver.setUsername("retiring-user");
        leaver.setPassword("pass");
        leaver.setRealName("退職者");
        leaver.setRole("HR");
        leaver.setStatus(1);
        sysUserMapper.insert(leaver);

        SysUser member = new SysUser();
        member.setUsername("remaining-user");
        member.setPassword("pass");
        member.setRealName("残留者");
        member.setRole("HR");
        member.setStatus(1);
        sysUserMapper.insert(member);

        organizationService.assignUser(UserOrganization.builder()
                .userId(leaver.getId()).organizationId(unit.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());
        organizationService.assignUser(UserOrganization.builder()
                .userId(member.getId()).organizationId(unit.getId()).primaryFlag(1)
                .managerUserId(leaver.getId()).validFrom(LocalDate.of(2026, 1, 1)).build());

        assertEquals(1, organizationService.closeAssignmentsForUser(leaver.getId(), LocalDate.of(2026, 6, 30)));

        assertTrue(organizationService.listUserOrganizations(leaver.getId(), LocalDate.of(2026, 7, 1)).isEmpty());
        assertEquals(LocalDate.of(2026, 6, 30), organizationService
                .listUserOrganizations(leaver.getId(), LocalDate.of(2026, 6, 1)).get(0).getValidTo());
        assertNull(organizationService.listUserOrganizations(member.getId(), LocalDate.of(2026, 7, 1))
                .get(0).getManagerUserId());
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

    @Test
    void 同日異動は旧所属と新所属の重複を作らず拒否する() {
        OrganizationUnit source = organization("TRANSFER-SAME-DAY-SRC", "異動元", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(source);
        OrganizationUnit target = organization("TRANSFER-SAME-DAY-DST", "異動先", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(target);
        Long userId = sysUserMapper.selectByUsername("admin").getId();
        LocalDate start = LocalDate.now().minusDays(1);
        organizationService.assignUser(UserOrganization.builder()
                .userId(userId).organizationId(source.getId()).primaryFlag(1).validFrom(start).build());
        UserOrganization current = organizationService.listUserOrganizations(userId, LocalDate.now()).get(0);

        assertThrows(BusinessException.class, () -> organizationService.transferUser(
                UserOrganization.builder().userId(userId).organizationId(target.getId()).primaryFlag(1)
                        .validFrom(start).build(), current.getVersion()));
    }

    @Test
    void 所属とsnapshotで参照中の組織期間変更を拒否する() {
        OrganizationUnit unit = organization("ORG-PERIOD-REF", "期間固定部", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(unit);
        Long userId = sysUserMapper.selectByUsername("admin").getId();
        organizationService.assignUser(UserOrganization.builder()
                .userId(userId).organizationId(unit.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());

        OrganizationUnit patch = organization("ORG-PERIOD-REF", "期間固定部", null,
                LocalDate.of(2026, 2, 1), null);
        patch.setId(unit.getId());
        assertThrows(BusinessException.class, () -> organizationService.updateOrganization(patch, 0));
    }

    @Test
    void 法人不一致的父子组织与統合を拒否する() {
        OrganizationUnit parent = organization("LEGAL-1-PARENT", "法人一の親", null,
                LocalDate.of(2026, 1, 1), null);
        parent.setLegalEntityId(1L);
        organizationService.save(parent);

        OrganizationUnit crossLegalChild = organization("LEGAL-2-CHILD", "法人二の子", parent.getId(),
                LocalDate.of(2026, 1, 1), null);
        crossLegalChild.setLegalEntityId(2L);
        assertThrows(BusinessException.class, () -> organizationService.save(crossLegalChild));

        OrganizationUnit source = organization("LEGAL-1-SOURCE", "法人一の統合元", null,
                LocalDate.of(2026, 1, 1), null);
        source.setLegalEntityId(1L);
        organizationService.save(source);
        OrganizationUnit target = organization("LEGAL-2-TARGET", "法人二の統合先", null,
                LocalDate.of(2026, 1, 1), null);
        target.setLegalEntityId(2L);
        organizationService.save(target);
        assertThrows(BusinessException.class, () -> organizationService.merge(source.getId(), target.getId(), 0));
    }

    @Test
    void 参照中組織の法人変更を拒否する() {
        OrganizationUnit unit = organization("LEGAL-CHANGE", "法人変更対象", null,
                LocalDate.of(2026, 1, 1), null);
        unit.setLegalEntityId(1L);
        organizationService.save(unit);
        Long adminId = sysUserMapper.selectByUsername("admin").getId();
        organizationService.assignUser(UserOrganization.builder()
                .userId(adminId).organizationId(unit.getId()).primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).build());

        OrganizationUnit patch = organization("LEGAL-CHANGE", "法人変更対象", null,
                LocalDate.of(2026, 1, 1), null);
        patch.setId(unit.getId());
        patch.setLegalEntityId(2L);
        assertThrows(BusinessException.class, () -> organizationService.updateOrganization(patch, 0));
    }

    @Test
    void 予算の組織と原価部門が不一致なら保存を拒否する() {
        OrganizationUnit first = organization("BUDGET-ORG-A", "予算法人一", null,
                LocalDate.of(2026, 1, 1), null);
        first.setLegalEntityId(1L);
        organizationService.save(first);
        OrganizationUnit second = organization("BUDGET-ORG-B", "原価部門所属", null,
                LocalDate.of(2026, 1, 1), null);
        second.setLegalEntityId(1L);
        organizationService.save(second);
        var center = com.ses.entity.CostCenter.builder()
                .legalEntityId(1L).organizationId(second.getId()).code("CC-B")
                .name("法人一別部門").validFrom(LocalDate.of(2026, 1, 1)).status("有効").version(0).build();
        costCenterService.save(center);

        var budget = com.ses.entity.ManagementBudget.builder()
                .organizationId(first.getId()).costCenterId(center.getId())
                .budgetMonth(LocalDate.of(2026, 7, 1)).revenue(BigDecimal.valueOf(1000000))
                .grossProfit(BigDecimal.valueOf(300000)).utilizationCount(5).hireCount(1).build();
        assertThrows(BusinessException.class, () -> managementBudgetService.upsert(budget, null));
    }

    @Test
    void 無効化済みまたは存在しない上長を所属へ設定できない() {
        OrganizationUnit unit = organization("ORG-MANAGER-GUARD", "上長検証部", null,
                LocalDate.of(2026, 1, 1), null);
        organizationService.save(unit);

        SysUser disabled = new SysUser();
        disabled.setUsername("disabled-manager");
        disabled.setPassword("pass");
        disabled.setRealName("無効上長");
        disabled.setRole("HR");
        disabled.setStatus(0);
        sysUserMapper.insert(disabled);
        SysUser member = new SysUser();
        member.setUsername("manager-guard-member");
        member.setPassword("pass");
        member.setRealName("所属対象");
        member.setRole("HR");
        member.setStatus(1);
        sysUserMapper.insert(member);

        UserOrganization disabledManagerAssignment = UserOrganization.builder()
                .userId(member.getId()).organizationId(unit.getId()).managerUserId(disabled.getId())
                .validFrom(LocalDate.of(2026, 1, 1)).build();
        assertThrows(BusinessException.class, () -> organizationService.assignUser(disabledManagerAssignment));

        UserOrganization missingManagerAssignment = UserOrganization.builder()
                .userId(member.getId()).organizationId(unit.getId()).managerUserId(999999L)
                .validFrom(LocalDate.of(2026, 1, 1)).build();
        assertThrows(BusinessException.class, () -> organizationService.assignUser(missingManagerAssignment));
    }

    private OrganizationUnit organization(String code, String name, Long parentId,
                                          LocalDate validFrom, LocalDate validTo) {
        return OrganizationUnit.builder()
                .code(code).name(name).type("部")
                .parentId(parentId).validFrom(validFrom).validTo(validTo)
                .status("有効").version(0).build();
    }
}
