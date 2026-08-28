package com.ses.staffing;

import com.ses.entity.ProjectPosition;
import com.ses.entity.ProjectPositionEvent;
import com.ses.mapper.ProjectPositionEventMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.staffing.PositionService;
import com.ses.service.effective.EffectiveIntervalSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private ProjectPositionEventMapper projectPositionEventMapper;

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

    @Test
    void deleteは先行snapshotを閉じてからDELETEイベントを記録する() {
        ProjectPosition created = positionService.create(position("P1"));
        positionService.delete(created.getId());

        List<ProjectPositionEvent> events = projectPositionEventMapper.selectByPositionId(created.getId());
        assertEquals(2, events.size());
        ProjectPositionEvent create = events.get(0);
        assertEquals(ProjectPositionEvent.TYPE_CREATE, create.getEventType());
        assertNotNull(create.getEffectiveTo());
        ProjectPositionEvent delete = events.get(1);
        assertEquals(ProjectPositionEvent.TYPE_DELETE, delete.getEventType());
        assertNotNull(delete.getEffectiveTo());
    }

    @Test
    void updateは先行snapshotを閉じてから新snapshotを記録する() {
        ProjectPosition created = positionService.create(position("P1"));

        ProjectPosition patch = new ProjectPosition();
        patch.setId(created.getId());
        patch.setPositionNo(created.getPositionNo());
        patch.setRoleName("更新後ロール");
        patch.setRequiredCount(created.getRequiredCount());
        patch.setAllocationPercent(created.getAllocationPercent());
        patch.setStartDate(created.getStartDate());
        patch.setEndDate(created.getEndDate());
        patch.setVersion(created.getVersion());

        positionService.update(patch);

        List<ProjectPositionEvent> events = projectPositionEventMapper.selectByPositionId(created.getId());
        assertEquals(2, events.size());
        assertNotNull(events.get(0).getEffectiveTo());
        assertNull(events.get(1).getEffectiveTo());
        assertEquals(ProjectPositionEvent.TYPE_UPDATE, events.get(1).getEventType());
        assertEquals(LocalDate.now(), events.get(1).getEffectiveFrom());
    }

    @Test
    void updateは過去asOfを新snapshotで置き換えない() {
        ProjectPosition p = position("P1");
        p.setStartDate(LocalDate.now().minusMonths(2));
        ProjectPosition created = positionService.create(p);
        String originalRole = created.getRoleName();

        ProjectPosition patch = new ProjectPosition();
        patch.setId(created.getId());
        patch.setPositionNo(created.getPositionNo());
        patch.setRoleName("更新後ロール");
        patch.setRequiredCount(created.getRequiredCount());
        patch.setAllocationPercent(created.getAllocationPercent());
        patch.setStartDate(created.getStartDate());
        patch.setEndDate(created.getEndDate());
        patch.setVersion(created.getVersion());
        positionService.update(patch);

        List<ProjectPositionEvent> events = projectPositionEventMapper.selectByPositionId(created.getId());
        LocalDate pastAsOf = LocalDate.now().minusMonths(1);
        ProjectPositionEvent activePast = activeSnapshotAt(events, pastAsOf);
        assertNotNull(activePast);
        assertEquals(originalRole, activePast.getRoleName());
        assertEquals(ProjectPositionEvent.TYPE_CREATE, activePast.getEventType());

        ProjectPositionEvent activeToday = activeSnapshotAt(events, LocalDate.now());
        assertNotNull(activeToday);
        assertEquals("更新後ロール", activeToday.getRoleName());
        assertEquals(ProjectPositionEvent.TYPE_UPDATE, activeToday.getEventType());
    }

    private static ProjectPositionEvent activeSnapshotAt(List<ProjectPositionEvent> events, LocalDate asOf) {
        ProjectPositionEvent active = null;
        for (ProjectPositionEvent event : events) {
            if (EffectiveIntervalSupport.isActiveAtAsOf(event.getEffectiveFrom(), event.getEffectiveTo(), asOf)) {
                active = event;
            }
        }
        return active;
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
