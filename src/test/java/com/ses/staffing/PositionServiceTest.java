package com.ses.staffing;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ProjectPosition;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.staffing.PositionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static com.ses.entity.ProjectPosition.STATUS_CANDIDATE;
import static com.ses.entity.ProjectPosition.STATUS_CANCELLED;
import static com.ses.entity.ProjectPosition.STATUS_FILLED;
import static com.ses.entity.ProjectPosition.STATUS_HOLD;
import static com.ses.entity.ProjectPosition.STATUS_RECRUITING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T075 F1: ポジション状態機械・CRUDの定向test（L1〜L2）。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PositionServiceTest {

    @Autowired
    private PositionService positionService;

    @Autowired
    private ProjectPositionMapper positionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String name = "T075pos-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", name);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, name);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", name, customerId);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, name);
    }

    @Test
    void 作成すると募集中で登録される() {
        ProjectPosition created = positionService.create(position("P1"));
        assertEquals(STATUS_RECRUITING, created.getStatus());
        assertEquals(0, created.getVersion());
        ProjectPosition loaded = positionMapper.selectById(created.getId());
        assertEquals("Javaエンジニア", loaded.getRoleName());
    }

    @Test
    void 状態機械の許可遷移と拒否() {
        ProjectPosition p = positionService.create(position("P1"));
        // 募集中 → 候補選定 → 充足 → 募集中（欠員発生）
        positionService.changeStatus(p.getId(), STATUS_CANDIDATE);
        positionService.changeStatus(p.getId(), STATUS_FILLED);
        assertEquals(STATUS_FILLED, positionMapper.selectById(p.getId()).getStatus());
        positionService.changeStatus(p.getId(), STATUS_RECRUITING);
        assertEquals(STATUS_RECRUITING, positionMapper.selectById(p.getId()).getStatus());
        // 候補選定 → 保留 → 募集中
        positionService.changeStatus(p.getId(), STATUS_CANDIDATE);
        positionService.changeStatus(p.getId(), STATUS_HOLD);
        positionService.changeStatus(p.getId(), STATUS_RECRUITING);
        // 募集中 → 取消 → 募集中
        positionService.changeStatus(p.getId(), STATUS_CANCELLED);
        positionService.changeStatus(p.getId(), STATUS_RECRUITING);
        // 許可されない遷移: 募集中 → 充足
        BusinessException ex = assertThrows(BusinessException.class,
                () -> positionService.changeStatus(p.getId(), STATUS_FILLED));
        assertEquals("error.staffing.invalidTransition", ex.getMessageKey());
    }

    @Test
    void 状態CASはversionを進め不正遷移を拒否する() {
        ProjectPosition p = positionService.create(position("P1"));
        // 遷移ごとにversionが進む（楽観ロックのCAS用）
        positionService.changeStatus(p.getId(), STATUS_CANDIDATE);
        assertEquals(1, positionMapper.selectById(p.getId()).getVersion());
        // 候補選定を経ずに充足へ遷移する不正遷移は拒否される（状態CAS）
        ProjectPosition p2 = positionService.create(position("P2"));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> positionService.changeStatus(p2.getId(), STATUS_FILLED));
        assertEquals("error.staffing.invalidTransition", ex.getMessageKey());
        assertEquals(STATUS_RECRUITING, positionMapper.selectById(p2.getId()).getStatus());
    }

    @Test
    void 更新はversionCASで行われる() {
        ProjectPosition p = positionService.create(position("P1"));
        p.setRoleName("更新後");
        positionService.update(p);
        assertEquals("更新後", positionMapper.selectById(p.getId()).getRoleName());
        p.setRoleName("古いversion");
        p.setVersion(0);
        BusinessException ex = assertThrows(BusinessException.class, () -> positionService.update(p));
        assertEquals("error.common.optimisticLock", ex.getMessageKey());
    }

    @Test
    void 充足済みは削除できず募集中は削除できる() {
        ProjectPosition p = positionService.create(position("P1"));
        positionService.changeStatus(p.getId(), STATUS_CANDIDATE);
        positionService.changeStatus(p.getId(), STATUS_FILLED);
        BusinessException ex = assertThrows(BusinessException.class, () -> positionService.delete(p.getId()));
        assertEquals("error.staffing.positionFilled", ex.getMessageKey());
        positionService.changeStatus(p.getId(), STATUS_RECRUITING);
        positionService.delete(p.getId());
        // 論理削除済みのため通常selectでは見えなくなる
        assertTrue(positionMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProjectPosition>()
                .eq(ProjectPosition::getId, p.getId())) == 0);
    }

    @Test
    void 案件内でポジション番号は一意() {
        positionService.create(position("P1"));
        positionService.create(position("P2"));
        // 同一案件内の同一position_noはDB UNIQUEで拒否される
        org.springframework.dao.DuplicateKeyException ex =
                assertThrows(org.springframework.dao.DuplicateKeyException.class,
                        () -> positionService.create(position("P1")));
        assertTrue(ex != null);
    }

    private ProjectPosition position(String no) {
        ProjectPosition p = new ProjectPosition();
        p.setProjectId(projectId);
        p.setPositionNo(no);
        p.setRoleName("Javaエンジニア");
        p.setRequiredCount(2);
        p.setAllocationPercent(new BigDecimal("100"));
        p.setStartDate(java.time.LocalDate.of(2026, 9, 1));
        p.setEndDate(java.time.LocalDate.of(2026, 12, 31));
        return p;
    }
}
