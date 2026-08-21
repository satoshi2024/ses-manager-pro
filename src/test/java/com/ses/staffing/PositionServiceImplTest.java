package com.ses.staffing;

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
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P1-2: ProjectPosition.endDate（ALWAYS）の部分更新で未出現キーが NULL 上書きされないこと。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PositionServiceImplTest {

    @Autowired
    private PositionService positionService;

    @Autowired
    private ProjectPositionMapper positionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String name = "P1-2pos-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", name);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, name);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", name, customerId);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, name);
    }

    @Test
    void 役割名のみ更新してもendDateは保持される() {
        ProjectPosition created = positionService.create(position("P1"));
        assertEquals(LocalDate.of(2026, 12, 31), created.getEndDate());

        // JSON 未出現を模擬: endDate setter を呼ばない部分更新
        ProjectPosition patch = new ProjectPosition();
        patch.setId(created.getId());
        patch.setPositionNo(created.getPositionNo());
        patch.setRoleName("更新後ロール");
        patch.setRequiredCount(created.getRequiredCount());
        patch.setAllocationPercent(created.getAllocationPercent());
        patch.setStartDate(created.getStartDate());
        patch.setVersion(created.getVersion());

        ProjectPosition updated = positionService.update(patch);
        assertEquals("更新後ロール", updated.getRoleName());
        assertEquals(LocalDate.of(2026, 12, 31), updated.getEndDate());
        assertEquals(LocalDate.of(2026, 12, 31), positionMapper.selectById(created.getId()).getEndDate());
    }

    @Test
    void 明示nullでendDateをクリアできる() {
        ProjectPosition created = positionService.create(position("P1"));

        ProjectPosition patch = new ProjectPosition();
        patch.setId(created.getId());
        patch.setPositionNo(created.getPositionNo());
        patch.setRoleName(created.getRoleName());
        patch.setRequiredCount(created.getRequiredCount());
        patch.setAllocationPercent(created.getAllocationPercent());
        patch.setStartDate(created.getStartDate());
        patch.setVersion(created.getVersion());
        patch.setEndDate(null); // setter 経由 = payload 出現

        ProjectPosition updated = positionService.update(patch);
        assertNull(updated.getEndDate());
        assertNull(positionMapper.selectById(created.getId()).getEndDate());
    }

    private ProjectPosition position(String no) {
        ProjectPosition p = new ProjectPosition();
        p.setProjectId(projectId);
        p.setPositionNo(no);
        p.setRoleName("Javaエンジニア");
        p.setRequiredCount(2);
        p.setAllocationPercent(new BigDecimal("100"));
        p.setStartDate(LocalDate.of(2026, 9, 1));
        p.setEndDate(LocalDate.of(2026, 12, 31));
        return p;
    }
}
