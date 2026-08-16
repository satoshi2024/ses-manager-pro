package com.ses.staffing;

import com.ses.dto.staffing.AllocationCardDto;
import com.ses.entity.StaffingScenario;
import com.ses.entity.StaffingScenarioAllocation;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import com.ses.service.staffing.StaffingScenarioCompareService;
import com.ses.service.staffing.StaffingScenarioService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * S12-R1-P1-02: 共有scenarioの要員scope filter（SQL境界）とlistVisibleの組織scopeを
 * mockしたscope serviceで決定的に検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StaffingScenarioScopeTest {

    private static final long VIEWER_USER_ID = 92001L;
    private static final long OTHER_OWNER_ID = 92002L;

    @Autowired
    private StaffingScenarioService scenarioService;

    @Autowired
    private StaffingScenarioCompareService compareService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private DataScopeService dataScopeService;

    @MockBean
    private OrganizationScopeService organizationScopeService;

    private long engineerId1;
    private long engineerId2;

    @BeforeEach
    void setUp() {
        String suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) "
                + "VALUES (?, '正社員', 'Bench')", "T079scope-eng1-" + suffix);
        engineerId1 = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T079scope-eng1-" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) "
                + "VALUES (?, '正社員', 'Bench')", "T079scope-eng2-" + suffix);
        engineerId2 = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T079scope-eng2-" + suffix);

        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.allowedEngineerIds()).thenReturn(Set.of(engineerId1));
        when(organizationScopeService.hasFullAccess()).thenReturn(false);

        authenticate(VIEWER_USER_ID, "マネージャー");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 共有scenarioの仮配置は閲覧者のscopeでSQL境界filterされる() {
        StaffingScenario scenario = scenarioService.create(scenario("S1"));
        scenarioService.upsertAllocation(alloc(scenario.getId(), engineerId1, 100, "[\"2026-09-01\"]"));
        scenarioService.upsertAllocation(alloc(scenario.getId(), engineerId2, 100, "[\"2026-09-02\"]"));
        scenario.setSharedFlag(1);
        scenarioService.update(scenario);

        // scope内のengineer1の行だけが返る（Java filterではなくSQL境界の結果）
        List<AllocationCardDto> visible = compareService.visibleAllocations(scenario.getId());
        assertEquals(1, visible.size(), "scope外の要員の仮配置は返らない");
        assertEquals(engineerId1, visible.get(0).getEngineerId());

        // compareもscope内の要員だけを集計する
        List<StaffingScenarioCompareService.ScenarioMonthDto> rows =
                compareService.compare(List.of(scenario.getId()), LocalDate.of(2026, 8, 1));
        StaffingScenarioCompareService.ScenarioMonthDto sept = rows.stream()
                .filter(r -> r.month().equals(java.time.YearMonth.of(2026, 9)))
                .findFirst().orElse(null);
        assertEquals(1, sept.engineerCount(), "compareの要員数はscope内のみ");
    }

    @Test
    void listVisibleは組織scope外のownerの共有scenarioを返さない() {
        // 自分（92001）のscenario
        StaffingScenario mine = scenarioService.create(scenario("Mine"));
        mine.setSharedFlag(1);
        scenarioService.update(mine);

        // 他者（92002）の共有scenario
        authenticate(OTHER_OWNER_ID, "マネージャー");
        StaffingScenario others = scenarioService.create(scenario("Others"));
        others.setSharedFlag(1);
        scenarioService.update(others);

        // 組織scopeが他者を含まない → 他者の共有scenarioは見えない
        authenticate(VIEWER_USER_ID, "マネージャー");
        when(organizationScopeService.allowedUserIds(java.time.LocalDate.now()))
                .thenReturn(Set.of(999999L));
        List<StaffingScenario> hidden = scenarioService.listVisible();
        assertTrue(hidden.stream().noneMatch(s -> s.getId().equals(others.getId())),
                "組織scope外のownerの共有scenarioを表示しない");
        assertTrue(hidden.stream().anyMatch(s -> s.getId().equals(mine.getId())),
                "自分のscenarioは常に表示される");

        // 組織scopeが他者を含む → 共有scenarioが見える
        when(organizationScopeService.allowedUserIds(java.time.LocalDate.now()))
                .thenReturn(Set.of(OTHER_OWNER_ID));
        List<StaffingScenario> visible = scenarioService.listVisible();
        assertTrue(visible.stream().anyMatch(s -> s.getId().equals(others.getId())),
                "組織scope内のownerの共有scenarioは表示される");
    }

    // ---------------------------------------------------------------

    private StaffingScenario scenario(String name) {
        StaffingScenario s = new StaffingScenario();
        s.setName(name);
        s.setBaseDate(LocalDate.of(2026, 8, 1));
        return s;
    }

    private StaffingScenarioAllocation alloc(Long scenarioId, long engineerIdRow, int percent, String datesJson) {
        StaffingScenarioAllocation a = new StaffingScenarioAllocation();
        a.setScenarioId(scenarioId);
        a.setEngineerId(engineerIdRow);
        a.setPercent(new BigDecimal(percent));
        a.setDates(datesJson);
        return a;
    }

    private void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
