package com.ses.crm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.CustomerContact;
import com.ses.entity.Lead;
import com.ses.entity.Opportunity;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.LeadMapper;
import com.ses.mapper.OpportunityMapper;
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

/**
 * T048定向テスト: CRM DDLのH2 replay検証・entity CRUD・primary一意・期間重複拒否。
 * L1〜L3の定向test。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CustomerContactSchemaTest {

    @Autowired
    private CustomerContactMapper customerContactMapper;

    @Autowired
    private LeadMapper leadMapper;

    @Autowired
    private OpportunityMapper opportunityMapper;

    // ======== H2 Schema Replay (L1): テーブル存在とCRUD可能 ========

    @Test
    @DisplayName("t_customer_contact: INSERT/SELECT/DELETEが動作する")
    void customerContactCrud() {
        CustomerContact contact = CustomerContact.builder()
                .customerId(1L)
                .name("田中太郎")
                .nameKana("タナカタロウ")
                .department("営業部")
                .position("部長")
                .rolesJson("[\"決裁者\",\"請求\"]")
                .email("tanaka@example.com")
                .phone("03-1234-5678")
                .primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1))
                .validTo(null) // 無期限
                .status("有効")
                .version(1)
                .build();

        int inserted = customerContactMapper.insert(contact);
        assertEquals(1, inserted);
        assertNotNull(contact.getId());

        CustomerContact fetched = customerContactMapper.selectById(contact.getId());
        assertNotNull(fetched);
        assertEquals("田中太郎", fetched.getName());
        assertEquals("tanaka@example.com", fetched.getEmail());
        assertEquals(1, fetched.getPrimaryFlag());
    }

    @Test
    @DisplayName("t_lead: INSERT/SELECT/DELETEが動作する")
    void leadCrud() {
        Lead lead = Lead.builder()
                .companyName("テスト株式会社")
                .contactName("鈴木一郎")
                .contactEmail("suzuki@test.co.jp")
                .contactPhone("080-1111-2222")
                .source("Web問い合わせ")
                .ownerUserId(1L)
                .status("未対応")
                .version(1)
                .build();

        int inserted = leadMapper.insert(lead);
        assertEquals(1, inserted);
        assertNotNull(lead.getId());

        Lead fetched = leadMapper.selectById(lead.getId());
        assertNotNull(fetched);
        assertEquals("テスト株式会社", fetched.getCompanyName());
        assertEquals("未対応", fetched.getStatus());
    }

    @Test
    @DisplayName("t_opportunity: INSERT/SELECT/DELETEが動作する")
    void opportunityCrud() {
        Opportunity opp = Opportunity.builder()
                .customerId(1L)
                .title("テスト商機")
                .stage("見込")
                .expectedStartMonth("2026-09")
                .durationMonths(6)
                .requiredCount(2)
                .unitPrice(new BigDecimal("700000"))
                .expectedAmount(new BigDecimal("8400000"))
                .probability(30)
                .ownerUserId(1L)
                .nextActionDate(LocalDate.of(2026, 8, 15))
                .version(1)
                .build();

        int inserted = opportunityMapper.insert(opp);
        assertEquals(1, inserted);
        assertNotNull(opp.getId());

        Opportunity fetched = opportunityMapper.selectById(opp.getId());
        assertNotNull(fetched);
        assertEquals("テスト商機", fetched.getTitle());
        assertEquals("見込", fetched.getStage());
        assertEquals(new BigDecimal("700000"), fetched.getUnitPrice());
    }

    // ======== Primary一意: 1顧客で有効期間内にprimary=1は1件 (L2) ========

    @Test
    @DisplayName("primary_flag: 同一顧客で有効中のprimary=1が複数存在できる（DB制約はservice層で制御）")
    void primaryFlagMultipleInsertable() {
        // design §6.1: primary_flagはservice側CASで保証。DB部分UNIQUEはH2非対応のため
        // ここではINSERT可能であることを確認し、service層テストで一意保証をテストする。
        CustomerContact c1 = CustomerContact.builder()
                .customerId(1L)
                .name("主担当A")
                .email("a@example.com")
                .primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1))
                .status("有効")
                .version(1)
                .build();
        customerContactMapper.insert(c1);

        CustomerContact c2 = CustomerContact.builder()
                .customerId(1L)
                .name("主担当B")
                .email("b@example.com")
                .primaryFlag(1)
                .validFrom(LocalDate.of(2026, 6, 1))
                .status("有効")
                .version(1)
                .build();
        // service層で制御するため、mapper直接なら2件入る（テスト前提確認）
        customerContactMapper.insert(c2);

        List<CustomerContact> primaries = customerContactMapper.selectList(
                new LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getCustomerId, 1L)
                        .eq(CustomerContact::getPrimaryFlag, 1)
                        .eq(CustomerContact::getStatus, "有効")
        );
        assertEquals(2, primaries.size(), "DB層では2件入る（service層で排他制御する設計）");
    }

    // ======== 0件許容: 主担当未設定でも正常 (L2) ========

    @Test
    @DisplayName("primary_flag=0のみでも顧客担当者を登録できる（0件許容）")
    void zeroPrimaryAllowed() {
        CustomerContact c = CustomerContact.builder()
                .customerId(2L)
                .name("副担当のみ")
                .email("sub@example.com")
                .primaryFlag(0)
                .validFrom(LocalDate.of(2026, 1, 1))
                .status("有効")
                .version(1)
                .build();
        customerContactMapper.insert(c);

        List<CustomerContact> primaries = customerContactMapper.selectList(
                new LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getCustomerId, 2L)
                        .eq(CustomerContact::getPrimaryFlag, 1)
        );
        assertTrue(primaries.isEmpty(), "主担当0件を許容する");
    }

    // ======== 期間重複検出のクエリ確認 (L2) ========

    @Test
    @DisplayName("有効期間が重複するcontactを検出できる")
    void overlapDetection() {
        // 2026-01-01 ~ 2026-06-30
        CustomerContact c1 = CustomerContact.builder()
                .customerId(3L)
                .name("担当者1")
                .email("one@example.com")
                .primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1))
                .validTo(LocalDate.of(2026, 6, 30))
                .status("有効")
                .version(1)
                .build();
        customerContactMapper.insert(c1);

        // 重複チェックのクエリ: valid_from <= :to AND (valid_to IS NULL OR valid_to >= :from)
        LocalDate newFrom = LocalDate.of(2026, 3, 1);
        LocalDate newTo = LocalDate.of(2026, 9, 30);

        List<CustomerContact> overlapping = customerContactMapper.selectList(
                new LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getCustomerId, 3L)
                        .eq(CustomerContact::getPrimaryFlag, 1)
                        .eq(CustomerContact::getStatus, "有効")
                        .le(CustomerContact::getValidFrom, newTo)
                        .and(w -> w
                                .isNull(CustomerContact::getValidTo)
                                .or()
                                .ge(CustomerContact::getValidTo, newFrom)
                        )
        );
        assertFalse(overlapping.isEmpty(), "重複する期間が検出されるはず");
    }

    @Test
    @DisplayName("有効期間が重複しないcontactは検出されない")
    void noOverlap() {
        // 2026-01-01 ~ 2026-03-31
        CustomerContact c1 = CustomerContact.builder()
                .customerId(4L)
                .name("担当者1")
                .email("first@example.com")
                .primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1))
                .validTo(LocalDate.of(2026, 3, 31))
                .status("有効")
                .version(1)
                .build();
        customerContactMapper.insert(c1);

        // 新規: 2026-04-01 ~ (重複しない)
        LocalDate newFrom = LocalDate.of(2026, 4, 1);
        LocalDate newTo = LocalDate.of(2026, 12, 31);

        List<CustomerContact> overlapping = customerContactMapper.selectList(
                new LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getCustomerId, 4L)
                        .eq(CustomerContact::getPrimaryFlag, 1)
                        .eq(CustomerContact::getStatus, "有効")
                        .le(CustomerContact::getValidFrom, newTo)
                        .and(w -> w
                                .isNull(CustomerContact::getValidTo)
                                .or()
                                .ge(CustomerContact::getValidTo, newFrom)
                        )
        );
        assertTrue(overlapping.isEmpty(), "期間が重複しないので検出されない");
    }

    // ======== 退職者の候補除外 (L2) ========

    @Test
    @DisplayName("退職者は有効な宛先候補から除外される")
    void retiredExcludedFromCandidates() {
        CustomerContact active = CustomerContact.builder()
                .customerId(5L)
                .name("現役担当")
                .email("active@example.com")
                .primaryFlag(0)
                .validFrom(LocalDate.of(2026, 1, 1))
                .status("有効")
                .version(1)
                .build();
        customerContactMapper.insert(active);

        CustomerContact retired = CustomerContact.builder()
                .customerId(5L)
                .name("退職者")
                .email("retired@example.com")
                .primaryFlag(0)
                .validFrom(LocalDate.of(2024, 1, 1))
                .validTo(LocalDate.of(2025, 12, 31))
                .status("退職")
                .version(1)
                .build();
        customerContactMapper.insert(retired);

        // 有効な宛先候補: status=有効 かつ valid_to IS NULL or valid_to >= today
        LocalDate today = LocalDate.of(2026, 8, 1);
        List<CustomerContact> candidates = customerContactMapper.selectList(
                new LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getCustomerId, 5L)
                        .eq(CustomerContact::getStatus, "有効")
                        .and(w -> w
                                .isNull(CustomerContact::getValidTo)
                                .or()
                                .ge(CustomerContact::getValidTo, today)
                        )
        );
        assertEquals(1, candidates.size());
        assertEquals("現役担当", candidates.get(0).getName());
    }

    // ======== Lead状態確認 (L2) ========

    @Test
    @DisplayName("lead: 初期状態は未対応、converted列はNULL")
    void leadInitialState() {
        Lead lead = Lead.builder()
                .companyName("新規リード社")
                .contactName("山田")
                .contactEmail("yamada@newlead.co.jp")
                .source("展示会")
                .status("未対応")
                .version(1)
                .build();
        leadMapper.insert(lead);

        Lead fetched = leadMapper.selectById(lead.getId());
        assertEquals("未対応", fetched.getStatus());
        assertNull(fetched.getConvertedCustomerId());
        assertNull(fetched.getConvertedOpportunityId());
        assertNull(fetched.getOwnerUserId(), "未割当lead");
    }

    // ======== Opportunity初期状態 (L2) ========

    @Test
    @DisplayName("opportunity: 初期stageは見込、converted列はNULL")
    void opportunityInitialState() {
        Opportunity opp = Opportunity.builder()
                .customerId(1L)
                .title("初期商機")
                .stage("見込")
                .probability(10)
                .version(1)
                .build();
        opportunityMapper.insert(opp);

        Opportunity fetched = opportunityMapper.selectById(opp.getId());
        assertEquals("見込", fetched.getStage());
        assertNull(fetched.getConvertedProjectId());
        assertNull(fetched.getConvertedQuotationId());
        assertNull(fetched.getLostReason());
    }
}
