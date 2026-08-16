package com.ses.staffing;

import com.ses.common.exception.BusinessException;
import com.ses.entity.StaffingScenario;
import com.ses.entity.StaffingScenarioAllocation;
import com.ses.entity.SysUser;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.mapper.StaffingScenarioAllocationMapper;
import com.ses.mapper.StaffingScenarioMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.staffing.StaffingScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T075 F1: シナリオのisolation（R3.3）・dates検証・可視性の定向test（L2〜L3）。
 * scenario操作の前後で実データ（t_allocation_plan）が不変であることを固定する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StaffingScenarioServiceTest {

    @Autowired
    private StaffingScenarioService scenarioService;

    @Autowired
    private StaffingScenarioMapper scenarioMapper;

    @Autowired
    private StaffingScenarioAllocationMapper scenarioAllocationMapper;

    @Autowired
    private AllocationPlanMapper allocationPlanMapper;

    @Autowired
    private ProjectPositionMapper positionMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long engineerId;
    private long positionId;
    private long ownerUserId;
    private long otherUserId;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) "
                + "VALUES (?, '正社員', 'Bench')", "T075scen-eng-" + suffix);
        engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T075scen-eng-" + suffix);
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T075scen-" + suffix);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "T075scen-" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", "T075scen-prj-" + suffix, customerId);
        long projectIdRow = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "T075scen-prj-" + suffix);

        com.ses.entity.ProjectPosition position = new com.ses.entity.ProjectPosition();
        position.setProjectId(projectIdRow);
        position.setPositionNo("P1");
        position.setRoleName("Javaエンジニア");
        position.setRequiredCount(1);
        position.setAllocationPercent(new BigDecimal("100"));
        positionMapper.insert(position);
        positionId = position.getId();

        ownerUserId = insertUser("t075-owner");
        otherUserId = insertUser("t075-other");
        authenticate(ownerUserId, "管理者");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 作成者のみが作成者になり基準日と名前が保存される() {
        StaffingScenario created = scenarioService.create(scenario("S1"));
        assertEquals(ownerUserId, created.getOwnerUserId());
        assertEquals(LocalDate.of(2026, 9, 1), created.getBaseDate());
        assertEquals(0, created.getSharedFlag());
    }

    @Test
    void scenario操作の前後で実データが不変である() {
        // 実データ側に1件の配置を入れておく
        jdbcTemplate.update("INSERT INTO t_allocation_plan "
                + "(engineer_id, position_id, allocation_type, start_date, end_date, allocation_percent, status) "
                + "VALUES (?, ?, '案件', '2026-09-01', '2026-09-30', 100, '確定')",
                engineerId, positionId);
        long before = allocationPlanMapper.selectCount(null);

        StaffingScenario s = scenarioService.create(scenario("S1"));
        scenarioService.upsertAllocation(alloc(s.getId(), 50, "[\"2026-09-01\",\"2026-09-02\",\"2026-09-03\"]"));
        scenarioService.upsertAllocation(alloc(s.getId(), 60, "[\"2026-09-04\"]"));
        s.setName("S1-updated");
        scenarioService.update(s);
        List<StaffingScenarioAllocation> all = scenarioService.listAllocations(s.getId());
        assertEquals(2, all.size());
        scenarioService.deleteAllocation(all.get(0).getId());
        scenarioService.delete(s.getId());

        assertEquals(before, allocationPlanMapper.selectCount(null),
                "scenario操作はt_allocation_planを一切変更しない（R3.3）");
        assertEquals(0, scenarioAllocationMapper.selectCount(null));
    }

    @Test
    void datesは昇順のISO日付JSON配列に正規化される() {
        StaffingScenario s = scenarioService.create(scenario("S1"));
        StaffingScenarioAllocation saved = scenarioService.upsertAllocation(
                alloc(s.getId(), 50, "[\"2026-09-03\",\"2026-09-01\",\"2026-09-02\"]"));
        assertEquals("[\"2026-09-01\",\"2026-09-02\",\"2026-09-03\"]", saved.getDates());
    }

    @Test
    void 不正なdatesを拒否する() {
        StaffingScenario s = scenarioService.create(scenario("S1"));
        // 重複は正規化で除去される
        StaffingScenarioAllocation deduped = scenarioService.upsertAllocation(
                alloc(s.getId(), 50, "[\"2026-09-01\",\"2026-09-01\"]"));
        assertEquals("[\"2026-09-01\"]", deduped.getDates());
        // 非ISO
        assertThrows(BusinessException.class, () -> scenarioService.upsertAllocation(
                alloc(s.getId(), 50, "[\"2026/09/01\"]")));
        // 空
        assertThrows(BusinessException.class, () -> scenarioService.upsertAllocation(
                alloc(s.getId(), 50, "[]")));
        // 基準日より前
        assertThrows(BusinessException.class, () -> scenarioService.upsertAllocation(
                alloc(s.getId(), 50, "[\"2026-08-31\"]")));
        // 24か月超
        assertThrows(BusinessException.class, () -> scenarioService.upsertAllocation(
                alloc(s.getId(), 50, "[\"2028-09-02\"]")));
    }

    @Test
    void 共有シナリオは他ユーザーが参照できるが編集はownerのみ() {
        StaffingScenario s = scenarioService.create(scenario("S1"));
        s.setSharedFlag(1);
        scenarioService.update(s);

        authenticate(otherUserId, "管理者");
        assertTrue(scenarioService.listVisible().stream().anyMatch(x -> x.getId().equals(s.getId())));
        assertEquals(s.getId(), scenarioService.get(s.getId()).getId());

        StaffingScenario edited = scenarioService.get(s.getId());
        edited.setName("他人による編集");
        BusinessException ex = assertThrows(BusinessException.class, () -> scenarioService.update(edited));
        assertEquals("error.staffing.scenarioForbidden", ex.getMessageKey());
        assertThrows(BusinessException.class, () -> scenarioService.delete(s.getId()));

        // 共有していないシナリオは他ユーザーから不可視（ownerとして作成してから確認）
        authenticate(ownerUserId, "管理者");
        StaffingScenario privateS = scenarioService.create(scenario("S2"));
        authenticate(otherUserId, "管理者");
        assertThrows(BusinessException.class, () -> scenarioService.get(privateS.getId()));
        assertTrue(scenarioService.listVisible().stream().noneMatch(x -> x.getId().equals(privateS.getId())));
    }

    private StaffingScenario scenario(String name) {
        StaffingScenario s = new StaffingScenario();
        s.setName(name);
        s.setBaseDate(LocalDate.of(2026, 9, 1));
        return s;
    }

    private StaffingScenarioAllocation alloc(Long scenarioId, int percent, String datesJson) {
        StaffingScenarioAllocation a = new StaffingScenarioAllocation();
        a.setScenarioId(scenarioId);
        a.setEngineerId(engineerId);
        a.setPositionId(positionId);
        a.setDates(datesJson);
        a.setPercent(new BigDecimal(percent));
        return a;
    }

    private long insertUser(String prefix) {
        SysUser user = SysUser.builder()
                .username(prefix + "-" + System.nanoTime())
                .password("x")
                .realName(prefix)
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    private void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
