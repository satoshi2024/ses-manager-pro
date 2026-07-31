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
import java.util.List;

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

    @Test
    @DisplayName("表記揺れ（法人格・空白）を正規化して同一仮BPへ集約し、同名別法人は例外一覧へ出す")
    void testNormalizedNameCollapsesSpellings() {
        BpAvailability avail1 = BpAvailability.builder()
                .bpCompany("（株）アイティ・ソリューションズ")
                .initialName("T1")
                .build();
        BpAvailability avail2 = BpAvailability.builder()
                .bpCompany("株式会社アイティ・ソリューションズ")
                .initialName("T2")
                .build();
        BpAvailability avail3 = BpAvailability.builder()
                .bpCompany(" 有限会社 テストBP ")
                .initialName("T3")
                .build();
        BpAvailability avail4 = BpAvailability.builder()
                .bpCompany("テストBP（有）")
                .initialName("T4")
                .build();
        availabilityMapper.insert(avail1);
        availabilityMapper.insert(avail2);
        availabilityMapper.insert(avail3);
        availabilityMapper.insert(avail4);

        // 移行前: 同一正規化名の別表記が候補衝突として列挙される（自動統合はしない）
        List<com.ses.dto.bpmigration.BpMigrationExceptionDto> before =
                bpMigrationService.getMigrationExceptions();
        assertTrue(before.stream().anyMatch(e ->
                "DUPLICATE_NORMALIZED_NAME".equals(e.getReason())
                        && "アイティ・ソリューションズ".equals(e.getNormalizedCompanyName())));
        assertTrue(before.stream().anyMatch(e ->
                "DUPLICATE_NORMALIZED_NAME".equals(e.getReason())
                        && "テストBP".equals(e.getNormalizedCompanyName())));

        bpMigrationService.migrateLegacyBpData();

        BpCompany c1 = bpCompanyMapper.selectById(availabilityMapper.selectById(avail1.getId()).getBpCompanyId());
        BpCompany c2 = bpCompanyMapper.selectById(availabilityMapper.selectById(avail2.getId()).getBpCompanyId());
        BpCompany c3 = bpCompanyMapper.selectById(availabilityMapper.selectById(avail3.getId()).getBpCompanyId());
        BpCompany c4 = bpCompanyMapper.selectById(availabilityMapper.selectById(avail4.getId()).getBpCompanyId());
        assertEquals(c1.getId(), c2.getId(), "表記揺れは同一仮BPへ集約されること");
        assertEquals(c3.getId(), c4.getId(), "表記揺れは同一仮BPへ集約されること");
        assertNotEquals(c1.getId(), c3.getId(), "正規化名が異なる候補は別BPのまま");

        // 再実行しても重複仮BPを生成しない
        long countBefore = bpCompanyMapper.selectCount(null);
        bpMigrationService.migrateLegacyBpData();
        assertEquals(countBefore, bpCompanyMapper.selectCount(null));
    }
}
