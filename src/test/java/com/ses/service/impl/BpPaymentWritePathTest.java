package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.BpCompany;
import com.ses.entity.BpPayment;
import com.ses.entity.Contract;
import com.ses.entity.Engineer;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.BpCompanyService;
import com.ses.service.BpPaymentService;
import com.ses.service.EngineerBpAffiliationService;
import com.ses.service.WorkRecordService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BpPaymentWritePathTest {

    @Autowired
    private BpPaymentService bpPaymentService;

    @Autowired
    private BpCompanyService bpCompanyService;

    @Autowired
    private BpPaymentMapper bpPaymentMapper;

    @Autowired
    private WorkRecordMapper workRecordMapper;

    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private EngineerBpAffiliationService engineerBpAffiliationService;

    @Autowired
    private WorkRecordService workRecordService;

    /**
     * 勤怠のスコープ判定は契約→要員をINNER JOINするため、勤怠には実在する契約が必要。
     * 以前は contractId=1 という「他テストが作った既存データ」に暗黙依存しており、
     * 実行順（surefireのrunOrder）や先行テストの論理削除で結果が変わっていた。
     * テスト内で必要なデータを自前に作ることで実行順に依存しないようにする。
     */
    private Long insertOwnContract() {
        Engineer engineer = new Engineer();
        engineer.setFullName("BP支払テスト要員");
        engineer.setEmploymentType("BP");
        engineer.setStatus("稼動中");
        engineerMapper.insert(engineer);

        Contract contract = new Contract();
        contract.setEngineerId(engineer.getId());
        contract.setProjectId(1L);
        contract.setCustomerId(1L);
        contract.setStartDate(java.time.LocalDate.of(2026, 5, 1));
        contract.setSellingPrice(new BigDecimal("700000"));
        contract.setCostPrice(new BigDecimal("600000"));
        contract.setStatus("稼動中");
        contractMapper.insert(contract);
        return contract.getId();
    }

    @Test
    @DisplayName("支払先の会社名自由入力は拒否し、BP ID指定時は表示用snapshotを自動設定する")
    void addLayerRequiresBpCompanyIdWhenPayeeNameProvided() {
        BpPayment freeText = new BpPayment();
        freeText.setPayeeCompanyName("株式会社フリーテキスト");
        freeText.setAmount(new BigDecimal("100000"));
        assertThrows(BusinessException.class, () -> bpPaymentService.addLayer(freeText));

        BpPayment withoutId = new BpPayment();
        withoutId.setAmount(new BigDecimal("100000"));
        BusinessException missingId = assertThrows(BusinessException.class,
                () -> bpPaymentService.addLayer(withoutId));
        assertEquals("error.bpPayment.bpCompanyRequired", missingId.getMessage());

        BpCompany company = BpCompany.builder()
                .legalName("株式会社BPマスタ")
                .entityType("CORPORATE")
                .build();
        bpCompanyService.createBpCompany(company);

        WorkRecord record = new WorkRecord();
        record.setWorkMonth("2026-05");
        record.setContractId(insertOwnContract());
        record.setActualHours(new BigDecimal("160.0"));
        workRecordMapper.insert(record);

        BpPayment withId = new BpPayment();
        withId.setWorkRecordId(record.getId());
        withId.setBpCompanyId(company.getId());
        withId.setPayeeCompanyName("株式会社BPマスタ");
        withId.setAmount(new BigDecimal("100000"));
        BpPayment saved = bpPaymentService.addLayer(withId);

        assertEquals("株式会社BPマスタ", saved.getBpCompanyNameSnapshot(),
                "マスタ会社名のsnapshotが自動設定されること");
        BpPayment persisted = bpPaymentMapper.selectById(saved.getId());
        assertEquals(company.getId(), persisted.getBpCompanyId());
    }

    @Test
    @DisplayName("勤怠確定のBP自動生成もbpCompanyIdと名称snapshotを必須にする")
    void confirmAutoGenerateRequiresBpCompanyIdAndSnapshot() {
        BpCompany company = BpCompany.builder()
                .legalName("株式会社自動生成BP")
                .entityType("CORPORATE")
                .build();
        bpCompanyService.createBpCompany(company);

        Engineer engineer = new Engineer();
        engineer.setFullName("自動生成テスト要員");
        engineer.setEmploymentType("BP");
        engineer.setStatus("稼動中");
        engineerMapper.insert(engineer);
        engineerBpAffiliationService.assignBpAffiliation(
                engineer.getId(), company.getId(), LocalDate.of(2026, 1, 1), null);

        Contract contract = new Contract();
        contract.setEngineerId(engineer.getId());
        contract.setProjectId(1L);
        contract.setCustomerId(1L);
        contract.setStartDate(LocalDate.of(2026, 5, 1));
        contract.setSellingPrice(new BigDecimal("700000"));
        contract.setCostPrice(new BigDecimal("600000"));
        contract.setStatus("稼動中");
        contractMapper.insert(contract);

        // H2 test schema (V5) の status ENUM は 入力中/確定 のみ（V32の提出済は未適用）
        WorkRecord record = new WorkRecord();
        record.setWorkMonth("2026-05");
        record.setContractId(contract.getId());
        record.setActualHours(new BigDecimal("160.0"));
        record.setPaymentAmount(new BigDecimal("600000"));
        record.setStatus("入力中");
        workRecordMapper.insert(record);

        workRecordService.confirmMonth("2026-05");

        List<BpPayment> payments = bpPaymentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<BpPayment>()
                        .eq("work_record_id", record.getId()));
        assertEquals(1, payments.size());
        BpPayment generated = payments.get(0);
        assertEquals(company.getId(), generated.getBpCompanyId());
        assertEquals("株式会社自動生成BP", generated.getBpCompanyNameSnapshot());
        assertNotNull(generated.getBpCompanyId());
    }
}
