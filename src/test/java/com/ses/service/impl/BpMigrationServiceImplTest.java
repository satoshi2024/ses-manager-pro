package com.ses.service.impl;

import com.ses.entity.BpAvailability;
import com.ses.entity.BpCompany;
import com.ses.entity.BpPayment;
import com.ses.entity.WorkRecord;
import com.ses.mapper.BpAvailabilityMapper;
import com.ses.mapper.BpCompanyMapper;
import com.ses.mapper.BpPaymentMapper;
import com.ses.mapper.WorkRecordMapper;
import com.ses.service.BpCompanyService;
import com.ses.service.BpMigrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BpMigrationServiceImplTest {

    @Autowired
    private BpMigrationService bpMigrationService;

    @Autowired
    private BpAvailabilityMapper availabilityMapper;

    @Autowired
    private BpPaymentMapper paymentMapper;

    @Autowired
    private WorkRecordMapper workRecordMapper;

    @Autowired
    private BpCompanyMapper bpCompanyMapper;

    @Autowired
    private BpCompanyService bpCompanyService;

    @Test
    @DisplayName("自由入力既存データの移行、仮BP自動生成、冪等性の検証")
    void testLegacyDataMigration() {
        // 1. レガシーデータ投入
        BpAvailability avail = BpAvailability.builder()
                .bpCompany("（株）アイティ・ソリューションズ")
                .initialName("T.S")
                .build();
        availabilityMapper.insert(avail);

        WorkRecord record = new WorkRecord();
        record.setWorkMonth("2026-05");
        record.setContractId(1L);
        record.setActualHours(new BigDecimal("160.0"));
        workRecordMapper.insert(record);

        BpPayment payment = new BpPayment();
        payment.setWorkRecordId(record.getId());
        payment.setPayeeCompanyName("株式会社アイティ・ソリューションズ");
        payment.setAmount(new BigDecimal("500000"));
        payment.setPaidDate(LocalDate.of(2026, 5, 20));
        paymentMapper.insert(payment);

        // 2. 移行実行
        bpMigrationService.migrateLegacyBpData();

        // 3. 検証
        BpAvailability migratedAvail = availabilityMapper.selectById(avail.getId());
        assertNotNull(migratedAvail.getBpCompanyId());

        BpPayment migratedPayment = paymentMapper.selectById(payment.getId());
        assertNotNull(migratedPayment.getBpCompanyId());
        assertEquals("株式会社アイティ・ソリューションズ", migratedPayment.getBpCompanyNameSnapshot());

        BpCompany company = bpCompanyMapper.selectById(migratedPayment.getBpCompanyId());
        assertNotNull(company);
        assertEquals("PROVISIONAL", company.getEntityType());

        // 4. 過去支払スナップショットの不変性検証
        // マスタの社名を更新しても BpPayment の snapshot は変化しないこと
        company.setLegalName("株式会社ITソリューションズHD");
        bpCompanyService.updateBpCompany(company);

        BpPayment paymentAfterMasterUpdate = paymentMapper.selectById(payment.getId());
        assertEquals("株式会社アイティ・ソリューションズ", paymentAfterMasterUpdate.getBpCompanyNameSnapshot());

        // 5. 移行の冪等性検証 (再実行しても重複仮BPが生成されないこと)
        long companyCountBefore = bpCompanyMapper.selectCount(null);
        bpMigrationService.migrateLegacyBpData();
        long companyCountAfter = bpCompanyMapper.selectCount(null);
        assertEquals(companyCountBefore, companyCountAfter);
    }
}
