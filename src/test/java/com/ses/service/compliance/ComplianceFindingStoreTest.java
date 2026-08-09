package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.entity.ContractComplianceProfile;
import com.ses.mapper.ComplianceFindingMapper;
import com.ses.mapper.ContractComplianceProfileMapper;
import com.ses.mapper.ContractMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * F2: t_compliance_findingのupsert同期（design §5.4）。
 * rule再実行でfinding重複0、ack済みfindingが再実行でOPENへ戻らない、条件解消でRESOLVEDになることを検証する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ComplianceFindingStoreTest {

    @Autowired
    private ComplianceFindingMapper findingMapper;

    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private ContractComplianceProfileMapper profileMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ComplianceFindingStore findingStore;

    @Test
    void 再実行で重複0でありack済みはOPENへ戻らず条件解消でRESOLVEDになる() {
        long contractId = insertContract();
        insertProfile(contractId);

        ComplianceFinding candidate = candidate("MISSING_WORKPLACE_LIMITATION_DATE", "workplace:10");
        ComplianceFinding candidateOrg = candidate("MISSING_ORGANIZATION_LIMITATION_DATE", "org:開発部");

        // 1回目: 2件OPEN
        ComplianceFindingStore.SyncResult first = findingStore.sync(contractId, List.of(candidate, candidateOrg));
        assertEquals(2, first.opened());
        assertEquals(2, countFindings(contractId, "MISSING_WORKPLACE_LIMITATION_DATE", "workplace:10")
                + countFindings(contractId, "MISSING_ORGANIZATION_LIMITATION_DATE", "org:開発部"));

        // 2回目（同一rule再実行）: 重複insert 0、既存はkept
        ComplianceFindingStore.SyncResult second = findingStore.sync(contractId, List.of(candidate, candidateOrg));
        assertEquals(0, second.opened(), "rule再実行でfindingが増えないはず");
        assertEquals(2, second.kept());
        assertEquals(2, countFindings(contractId, "MISSING_WORKPLACE_LIMITATION_DATE", "workplace:10")
                + countFindings(contractId, "MISSING_ORGANIZATION_LIMITATION_DATE", "org:開発部"));

        // ack済みを再実行してもOPENへ戻らない
        com.ses.entity.ComplianceFinding ackTarget = findingMapper.selectList(null).stream()
                .filter(f -> "MISSING_WORKPLACE_LIMITATION_DATE".equals(f.getCode())
                        && f.getContractId().equals(contractId))
                .findFirst().orElseThrow();
        ackTarget.setStatus("ACKNOWLEDGED");
        findingMapper.updateById(ackTarget);
        findingStore.sync(contractId, List.of(candidate, candidateOrg));
        assertEquals(1, findingMapper.selectList(null).stream()
                .filter(f -> f.getContractId().equals(contractId)
                        && "MISSING_WORKPLACE_LIMITATION_DATE".equals(f.getCode())
                        && "ACKNOWLEDGED".equals(f.getStatus())).count(),
                "ack済みfindingは再実行でOPENへ戻らないはず");

        // 条件解消（欠落profile補完に相当）: evaluatedから外れたfindingがRESOLVEDになる
        ComplianceFindingStore.SyncResult third =
                findingStore.sync(contractId, List.of(candidateOrg));
        assertEquals(1, third.resolved(), "非検出になったfindingはRESOLVEDになるはず");
        assertEquals(1, findingMapper.selectList(null).stream()
                .filter(f -> f.getContractId().equals(contractId)
                        && "MISSING_WORKPLACE_LIMITATION_DATE".equals(f.getCode())
                        && "RESOLVED".equals(f.getStatus())).count());

        // 再検出（profileが再び欠落）: RESOLVEDがOPENへ戻る
        ComplianceFindingStore.SyncResult fourth =
                findingStore.sync(contractId, List.of(candidate, candidateOrg));
        assertEquals(1, fourth.opened(), "再検出でRESOLVEDがOPENへ戻るはず");
    }

    private ComplianceFinding candidate(String code, String fingerprint) {
        ComplianceFinding finding = new ComplianceFinding(code, "warning", "msg", null);
        finding.setConditionFingerprint(fingerprint);
        return finding;
    }

    private Long workplaceCustomerId;

    private long insertContract() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('F2 store customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='F2 store customer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES ('F2 store engineer', '正社員', 'Bench')");
        Long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name='F2 store engineer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id) VALUES ('F2 store project', ?)", customerId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name='F2 store project'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract "
                + "(engineer_id, project_id, customer_id, contract_type, start_date, end_date, status, selling_price, cost_price) "
                + "VALUES (?, ?, ?, '派遣', '2026-01-01', '2026-12-31', '稼動中', 100, 50)", engineerId, projectId, customerId);
        this.workplaceCustomerId = customerId;
        return jdbcTemplate.queryForObject(
                "SELECT id FROM t_contract WHERE engineer_id=?", Long.class, engineerId);
    }

    private void insertProfile(long contractId) {
        jdbcTemplate.update("INSERT INTO m_workplace (customer_id, name, organization_unit) "
                + "VALUES (?, 'F2 store workplace', '開発部')", workplaceCustomerId);
        Long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='F2 store workplace'", Long.class);
        ContractComplianceProfile profile = new ContractComplianceProfile();
        profile.setTenantId("default");
        profile.setContractId(contractId);
        profile.setWorkplaceId(workplaceId);
        profileMapper.insert(profile);
    }

    private long countFindings(long contractId, String code, String fingerprint) {
        return findingMapper.selectList(null).stream()
                .filter(f -> f.getContractId().equals(contractId)
                        && code.equals(f.getCode())
                        && fingerprint.equals(f.getConditionFingerprint())
                        && !"RESOLVED".equals(f.getStatus()))
                .count();
    }
}
