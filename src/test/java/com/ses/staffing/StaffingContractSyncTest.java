package com.ses.staffing;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.AllocationPlan;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.ProjectPosition;
import com.ses.entity.Proposal;
import com.ses.mapper.AllocationPlanMapper;
import com.ses.mapper.ProjectPositionMapper;
import com.ses.service.ContractService;
import com.ses.service.ProposalService;
import com.ses.service.staffing.StaffingContractSyncService;
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

import static com.ses.entity.AllocationPlan.STATUS_CONFIRMED;
import static com.ses.entity.AllocationPlan.STATUS_DISCARDED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T076 F2: 契約→actual allocation同期の定向test（L2〜L3）。
 * 契約成立でactual行が作成/更新され、終了/解約/削除で破棄される。
 * 同一engineer+positionの確定planはactual成立でsupersedeされ、plan/actualの二重計上が起きない。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StaffingContractSyncTest {

    @Autowired
    private ContractService contractService;

    @Autowired
    private ProposalService proposalService;

    @Autowired
    private StaffingContractSyncService staffingSync;

    @Autowired
    private AllocationPlanMapper allocationMapper;

    @Autowired
    private ProjectPositionMapper positionMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long engineerId;
    private long projectId;
    private long positionId;
    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = String.valueOf(System.nanoTime());
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", "T076sync-" + suffix);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, "T076sync-" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", "T076sync-prj-" + suffix, customerId);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, "T076sync-prj-" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) "
                + "VALUES (?, '正社員', 'Bench')", "T076sync-eng-" + suffix);
        engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "T076sync-eng-" + suffix);
        positionId = insertPosition(projectId, "P1");
    }

    @Test
    void 契約作成でactual行が作成され再同期しても1件のまま() {
        Contract contract = newContract();
        contractService.saveWithBusinessRules(contract);
        List<AllocationPlan> rows = actualsOf(contract.getId());
        assertEquals(1, rows.size());
        assertEquals(STATUS_CONFIRMED, rows.get(0).getStatus());
        assertEquals(0, new BigDecimal("100").compareTo(rows.get(0).getAllocationPercent()));
        assertEquals(positionId, rows.get(0).getPositionId());
        assertEquals(contract.getStartDate(), rows.get(0).getStartDate());
        assertEquals(contract.getEndDate(), rows.get(0).getEndDate());

        // 再同期しても重複しない（冪等）
        staffingSync.syncActual(contract.getId());
        assertEquals(1, actualsOf(contract.getId()).size());
    }

    @Test
    void 契約更新でactualの期間とポジションが追従する() {
        Contract contract = newContract();
        contractService.saveWithBusinessRules(contract);
        contract = contractService.getById(contract.getId());
        contract.setStartDate(LocalDate.of(2026, 9, 10));
        contract.setEndDate(LocalDate.of(2026, 12, 15));
        contractService.updateWithBusinessRules(contract);
        AllocationPlan actual = actualsOf(contract.getId()).get(0);
        assertEquals(LocalDate.of(2026, 9, 10), actual.getStartDate());
        assertEquals(LocalDate.of(2026, 12, 15), actual.getEndDate());
    }

    @Test
    void 契約の終了と解約でactual行が破棄される() {
        Contract contract = newContract();
        contractService.saveWithBusinessRules(contract);
        contractService.changeStatus(contract.getId(), "稼動中", null);
        assertEquals(1, actualsOf(contract.getId()).size());
        contractService.changeStatus(contract.getId(), "終了", null);
        assertEquals(STATUS_DISCARDED, actualsOf(contract.getId()).get(0).getStatus());

        Contract contract2 = newContract();
        contractService.saveWithBusinessRules(contract2);
        contractService.changeStatus(contract2.getId(), "解約", LocalDate.of(2026, 9, 20));
        assertEquals(STATUS_DISCARDED, actualsOf(contract2.getId()).get(0).getStatus());
    }

    @Test
    void 契約削除でactual行が破棄される() {
        Contract contract = newContract();
        contractService.saveWithBusinessRules(contract);
        contractService.removeById(contract.getId());
        assertEquals(STATUS_DISCARDED, actualsOf(contract.getId()).get(0).getStatus());
    }

    @Test
    void ポジション未紐付けの契約はactual行を持たない() {
        Contract contract = newContract();
        contract.setPositionId(null);
        contractService.saveWithBusinessRules(contract);
        assertEquals(0, actualsOf(contract.getId()).size());
    }

    @Test
    void 同一engineerの確定planはactual成立でsupersedeされる() {
        // 確定plan（source_contract_id IS NULL）を先に作る
        AllocationPlan plan = new AllocationPlan();
        plan.setEngineerId(engineerId);
        plan.setPositionId(positionId);
        plan.setAllocationType(AllocationPlan.TYPE_PROJECT);
        plan.setStartDate(LocalDate.of(2026, 9, 1));
        plan.setEndDate(LocalDate.of(2026, 12, 31));
        plan.setAllocationPercent(new BigDecimal("100"));
        plan.setStatus(STATUS_CONFIRMED);
        plan.setVersion(0);
        allocationMapper.insert(plan);

        Contract contract = newContract();
        contractService.saveWithBusinessRules(contract);

        // planは破棄され、actual（契約由来）だけが残る = plan/actualの二重計上なし
        AllocationPlan planAfter = allocationMapper.selectById(plan.getId());
        assertEquals(STATUS_DISCARDED, planAfter.getStatus());
        assertEquals(1, actualsOf(contract.getId()).size());
        assertTrue(allocationMapper.selectCount(new LambdaQueryWrapper<AllocationPlan>()
                .eq(AllocationPlan::getEngineerId, engineerId)
                .eq(AllocationPlan::getStatus, STATUS_CONFIRMED)) >= 1);
    }

    @Test
    void 提案のポジションが契約ドラフトへ引き継がれactualが作られる() {
        Proposal proposal = new Proposal();
        proposal.setEngineerId(engineerId);
        proposal.setProjectId(projectId);
        proposal.setPositionId(positionId);
        proposal.setStatus("書類選考中");
        proposal.setProposedUnitPrice(new BigDecimal("700000"));
        proposalService.save(proposal);

        Contract draft = contractService.createDraftFromProposal(proposal);
        assertEquals(positionId, draft.getPositionId());
        assertEquals(1, actualsOf(draft.getId()).size());
    }

    @Test
    void 契約のポジションが案件と不一致なら拒否される() {
        long otherProject = insertProject();
        long otherPosition = insertPosition(otherProject, "P2");
        Contract contract = newContract();
        contract.setPositionId(otherPosition);
        org.junit.jupiter.api.Assertions.assertThrows(com.ses.common.exception.BusinessException.class,
                () -> contractService.saveWithBusinessRules(contract));
    }

    // ---------------------------------------------------------------

    private Contract newContract() {
        Contract contract = new Contract();
        contract.setEngineerId(engineerId);
        contract.setProjectId(projectId);
        contract.setCustomerId(jdbcTemplate.queryForObject(
                "SELECT customer_id FROM t_project WHERE id = ?", Long.class, projectId));
        contract.setContractType("準委任");
        contract.setStartDate(LocalDate.of(2026, 9, 1));
        contract.setEndDate(LocalDate.of(2026, 12, 31));
        contract.setSellingPrice(new BigDecimal("800000"));
        contract.setCostPrice(new BigDecimal("600000"));
        contract.setPositionId(positionId);
        return contract;
    }

    private List<AllocationPlan> actualsOf(Long contractId) {
        return allocationMapper.selectList(new LambdaQueryWrapper<AllocationPlan>()
                .eq(AllocationPlan::getSourceContractId, contractId));
    }

    private long insertPosition(long projectIdRow, String no) {
        ProjectPosition position = new ProjectPosition();
        position.setProjectId(projectIdRow);
        position.setPositionNo(no);
        position.setRoleName("Javaエンジニア");
        position.setRequiredCount(1);
        position.setAllocationPercent(new BigDecimal("100"));
        positionMapper.insert(position);
        return position.getId();
    }

    private long insertProject() {
        String name = "T076sync-prj2-" + suffix;
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES (?)", name);
        long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, name);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) "
                + "VALUES (?, ?, '募集中')", name, customerId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name = ?", Long.class, name);
    }
}
