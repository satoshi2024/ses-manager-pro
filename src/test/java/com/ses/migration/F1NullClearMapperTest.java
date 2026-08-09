package com.ses.migration;

import com.ses.entity.ContractComplianceProfile;
import com.ses.mapper.ContractComplianceProfileMapper;
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
 * F1-NULL-01（design §6.2）のmapper経路検証（R8-P1-01/P2-01対応）:
 * clearable列へFieldStrategy.ALWAYSを付与し、full DTOのupdateByIdで値→NULLがDBへ保存されること、
 * 楽観ロックCAS失敗（expected version不一致）が0行更新（全rollback相当）になることをMyBatis-Plus経路で確認する。
 * 省略PATCHのrejectはT063のAPI導入時に担保し、本testはfull DTO契約のmapper層を担保する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class F1NullClearMapperTest {

    @Autowired
    private ContractComplianceProfileMapper profileMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void fullDTOのupdateByIdでclearable列の値NULLが保存されCAS失敗は0行になる() {
        long contractId = insertContract();

        ContractComplianceProfile profile = new ContractComplianceProfile();
        profile.setTenantId("default");
        profile.setContractId(contractId);
        profile.setWorkplaceLimitationDate(LocalDate.of(2026, 12, 31));
        profile.setOrganizationLimitationDate(LocalDate.of(2027, 3, 31));
        profile.setDispatchFeeAmount(new BigDecimal("120000.00"));
        profile.setDispatchFeeBasis("MONTHLY");
        profile.setSocialInsuranceProcedureIncompleteReason("手続中");
        profile.setBreakStartMinute(720);
        profile.setBreakEndMinute(780);
        profileMapper.insert(profile);

        assertEquals(1, queryInt("SELECT COUNT(*) FROM t_contract_compliance_profile WHERE contract_id=" + contractId
                + " AND workplace_limitation_date IS NOT NULL AND dispatch_fee_amount IS NOT NULL"));

        // full DTO：clearable列をすべて値→NULLにしてupdateById
        ContractComplianceProfile cleared = new ContractComplianceProfile();
        cleared.setId(profile.getId());
        cleared.setTenantId("default");
        cleared.setContractId(contractId);
        cleared.setWorkplaceLimitationDate(null);
        cleared.setOrganizationLimitationDate(null);
        cleared.setDispatchFeeAmount(null);
        cleared.setDispatchFeeBasis(null);
        cleared.setDispatchFeeCurrency(null);
        cleared.setSocialInsuranceProcedureIncompleteReason(null);
        cleared.setBreakStartMinute(null);
        cleared.setBreakEndMinute(null);
        cleared.setVersion(profile.getVersion());
        int updated = profileMapper.updateById(cleared);
        assertEquals(1, updated, "full DTO updateが成功するはず");

        // FieldStrategy.ALWAYSによりSET句へNULL列が出て、旧値が残らない（R8-P1-01）
        assertEquals(0, queryInt("SELECT COUNT(*) FROM t_contract_compliance_profile WHERE contract_id=" + contractId
                + " AND (workplace_limitation_date IS NOT NULL OR organization_limitation_date IS NOT NULL "
                + "OR dispatch_fee_amount IS NOT NULL OR dispatch_fee_basis IS NOT NULL "
                + "OR social_insurance_procedure_incomplete_reason IS NOT NULL "
                + "OR break_start_minute IS NOT NULL OR break_end_minute IS NOT NULL)"),
                "clearable列の値→NULLがmapper経路でも保存されるはず");
        assertNull(queryObject("SELECT workplace_limitation_date FROM t_contract_compliance_profile WHERE contract_id=" + contractId));
        assertNull(queryObject("SELECT dispatch_fee_amount FROM t_contract_compliance_profile WHERE contract_id=" + contractId));

        // CAS失敗：期待versionとDBのversionが不一致なら0行更新（rollback相当）
        ContractComplianceProfile fresh = profileMapper.selectById(profile.getId());
        ContractComplianceProfile stale = new ContractComplianceProfile();
        stale.setId(profile.getId());
        stale.setTenantId("default");
        stale.setContractId(contractId);
        stale.setWorkplaceLimitationDate(LocalDate.of(2026, 12, 31));
        stale.setVersion(fresh.getVersion() - 1);
        int casLost = profileMapper.updateById(stale);
        assertEquals(0, casLost, "expected version不一致のCASは0行更新");
        assertEquals(fresh.getVersion(), queryInt("SELECT version FROM t_contract_compliance_profile WHERE id=" + profile.getId()),
                "CAS失敗後もDB値は不変");
        assertNull(queryObject("SELECT workplace_limitation_date FROM t_contract_compliance_profile WHERE contract_id=" + contractId),
                "CAS失敗でclearした値が復活しない");
    }

    private long insertContract() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('F1NULL customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='F1NULL customer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES ('F1NULL engineer', '正社員', 'Bench')");
        Long engineerId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_engineer WHERE full_name='F1NULL engineer'", Long.class);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id) VALUES ('F1NULL project', ?)", customerId);
        Long projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_project WHERE project_name='F1NULL project'", Long.class);
        jdbcTemplate.update("INSERT INTO t_contract "
                + "(engineer_id, project_id, customer_id, contract_type, start_date, selling_price, cost_price) "
                + "VALUES (?, ?, ?, '派遣', '2026-08-01', 100, 50)", engineerId, projectId, customerId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM t_contract WHERE engineer_id=?", Long.class, engineerId);
    }

    private int queryInt(String sql) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
        return value == null ? 0 : value;
    }

    private Object queryObject(String sql) {
        return jdbcTemplate.queryForObject(sql, Object.class);
    }
}
