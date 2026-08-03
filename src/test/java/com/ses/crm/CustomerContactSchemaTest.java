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
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T048定向テスト: CRM DDLのH2 replay検証・entity CRUD・主担当一意・期間重複・移行DML。
 * L1〜L3の定向test。
 *
 * <p>親テーブル({@code m_customer})の行は各testが自分で作る。V73は実MySQLで
 * {@code fk_customer_contact_customer} を張るため、存在しない顧客IDを使うtestは
 * H2では通っても実DBでは成立しない（Round 1 の CRM-R1-P1-01 はまさにこれで落ちた）。
 * H2側はFKを張れない（共有DBの再initでV1の DROP TABLE が失敗する）ので、
 * 「実DBで成立する前提でtestを書く」ことでしか揃えられない。
 *
 * <p>移行DML(V73 §6)は本testが**V73ファイル本体から抽出して実行**する。H2用に書き直した
 * SQLを実行すると「V73が実行された」ことにはならない（Round 1 の CRM-R1-P1-03）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class CustomerContactSchemaTest {

    @Autowired
    private CustomerContactMapper customerContactMapper;

    @Autowired
    private LeadMapper leadMapper;

    @Autowired
    private OpportunityMapper opportunityMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** FK先の顧客を1件作り、そのIDを返す。 */
    private long newCustomer(String companyName) {
        jdbcTemplate.update(
                "INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)",
                companyName);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, companyName);
    }

    /** 既存contact情報つきの顧客を1件作り、そのIDを返す（移行DMLの入力）。 */
    private long newCustomerWithContact(String companyName, String person, String email, String phone,
                                        LocalDate createdOn) {
        jdbcTemplate.update(
                "INSERT INTO m_customer (company_name, contact_person, contact_email, contact_phone,"
                        + " trust_level, created_at, deleted_flag) VALUES (?, ?, ?, ?, 'B', ?, 0)",
                companyName, person, email, phone,
                createdOn == null ? null : java.sql.Timestamp.valueOf(createdOn.atStartOfDay()));
        return jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name = ?", Long.class, companyName);
    }

    // ======== H2 Schema Replay (L1): テーブル存在とCRUD可能 ========

    @Test
    @DisplayName("t_customer_contact: INSERT/SELECTが動作する")
    void customerContactCrud() {
        long customerId = newCustomer("CRUD検証顧客");

        CustomerContact contact = CustomerContact.builder()
                .customerId(customerId)
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
    @DisplayName("t_lead: INSERT/SELECTが動作する")
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
    @DisplayName("t_opportunity: INSERT/SELECTが動作する")
    void opportunityCrud() {
        long customerId = newCustomer("商機検証顧客");

        Opportunity opp = Opportunity.builder()
                .customerId(customerId)
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
        assertEquals(0, new BigDecimal("700000").compareTo(fetched.getUnitPrice()));
    }

    // ======== 主担当一意 (L2): 有効中のprimary=1は1顧客1件 ========

    @Test
    @DisplayName("主担当一意: 有効中のprimary_flag=1を同一顧客へ2件入れるとDB制約が拒否する")
    void activePrimaryIsUniquePerCustomer() {
        long customerId = newCustomer("主担当一意検証顧客");

        customerContactMapper.insert(CustomerContact.builder()
                .customerId(customerId).name("主担当A").email("a@example.com")
                .primaryFlag(1).validFrom(LocalDate.of(2026, 1, 1)).validTo(null)
                .status("有効").version(1).build());

        CustomerContact second = CustomerContact.builder()
                .customerId(customerId).name("主担当B").email("b@example.com")
                .primaryFlag(1).validFrom(LocalDate.of(2026, 6, 1)).validTo(null)
                .status("有効").version(1).build();

        // uk_customer_contact_active_primary (生成列 active_primary_customer_id のUNIQUE) が拒否する
        assertThrows(DataIntegrityViolationException.class,
                () -> customerContactMapper.insert(second),
                "有効中の主担当は1顧客につき1件でなければならない");
    }

    @Test
    @DisplayName("主担当一意: 期間を閉じた旧主担当と新主担当は共存できる（履歴を残す）")
    void closedPrimaryDoesNotBlockNewPrimary() {
        long customerId = newCustomer("主担当交代検証顧客");

        customerContactMapper.insert(CustomerContact.builder()
                .customerId(customerId).name("旧主担当").email("old@example.com")
                .primaryFlag(1)
                .validFrom(LocalDate.of(2025, 1, 1)).validTo(LocalDate.of(2025, 12, 31))
                .status("異動").version(1).build());

        int inserted = customerContactMapper.insert(CustomerContact.builder()
                .customerId(customerId).name("新主担当").email("new@example.com")
                .primaryFlag(1).validFrom(LocalDate.of(2026, 1, 1)).validTo(null)
                .status("有効").version(1).build());

        assertEquals(1, inserted, "期間を閉じた旧主担当は一意制約の対象外（履歴として残る）");
    }

    @Test
    @DisplayName("主担当一意: 顧客が違えば有効中のprimary=1を並存できる")
    void activePrimaryIsScopedByCustomer() {
        long first = newCustomer("主担当並存検証顧客1");
        long second = newCustomer("主担当並存検証顧客2");

        customerContactMapper.insert(CustomerContact.builder()
                .customerId(first).name("顧客1の主担当").primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).status("有効").version(1).build());
        int inserted = customerContactMapper.insert(CustomerContact.builder()
                .customerId(second).name("顧客2の主担当").primaryFlag(1)
                .validFrom(LocalDate.of(2026, 1, 1)).status("有効").version(1).build());

        assertEquals(1, inserted);
    }

    // ======== 0件許容: 主担当未設定でも正常 (L2) ========

    @Test
    @DisplayName("primary_flag=0のみでも顧客担当者を登録できる（0件許容・先頭へfallbackしない）")
    void zeroPrimaryAllowed() {
        long customerId = newCustomer("主担当0件検証顧客");

        customerContactMapper.insert(CustomerContact.builder()
                .customerId(customerId).name("副担当のみ").email("sub@example.com")
                .primaryFlag(0).validFrom(LocalDate.of(2026, 1, 1))
                .status("有効").version(1).build());

        List<CustomerContact> primaries = customerContactMapper.selectList(
                new LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getCustomerId, customerId)
                        .eq(CustomerContact::getPrimaryFlag, 1)
        );
        assertTrue(primaries.isEmpty(), "主担当0件を許容する");
    }

    // ======== 期間重複の境界 (L2): platform-invariants §1.1 の必須fixture ========

    @Test
    @DisplayName("期間重複: 部分重複・完全内包・同日・隣接・未来開始を境界どおり判定できる")
    void periodOverlapBoundaries() {
        long customerId = newCustomer("期間境界検証顧客");

        // 既存区間: 2026-01-01 〜 2026-06-30 (inclusive)
        customerContactMapper.insert(CustomerContact.builder()
                .customerId(customerId).name("担当者1").email("one@example.com")
                .primaryFlag(0)
                .validFrom(LocalDate.of(2026, 1, 1)).validTo(LocalDate.of(2026, 6, 30))
                .status("有効").version(1).build());

        // 部分重複
        assertTrue(overlaps(customerId, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 9, 30)),
                "後半が重なる区間は重複");
        // 完全内包
        assertTrue(overlaps(customerId, LocalDate.of(2026, 2, 1), LocalDate.of(2026, 3, 1)),
                "内包される区間は重複");
        // 同日（既存のvalid_toと新規のvalid_fromが同日 = inclusiveなので重複）
        assertTrue(overlaps(customerId, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 12, 31)),
                "valid_toはinclusiveなので同日は重複");
        // 隣接（翌日開始 = 重複しない。結合もしない）
        assertFalse(overlaps(customerId, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 12, 31)),
                "翌日開始は隣接であって重複ではない");
        // 未来開始
        assertFalse(overlaps(customerId, LocalDate.of(2027, 1, 1), null),
                "未来開始の無期限区間は重複しない");
        // 過去側で隣接（前日終了）
        assertFalse(overlaps(customerId, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)),
                "前日終了は隣接であって重複ではない");
    }

    @Test
    @DisplayName("期間重複: valid_to IS NULL(無期限)の既存区間は以後すべての区間と重複する")
    void openEndedPeriodOverlapsEverythingAfterIt() {
        long customerId = newCustomer("無期限区間検証顧客");

        customerContactMapper.insert(CustomerContact.builder()
                .customerId(customerId).name("無期限担当").email("open@example.com")
                .primaryFlag(0).validFrom(LocalDate.of(2026, 1, 1)).validTo(null)
                .status("有効").version(1).build());

        assertTrue(overlaps(customerId, LocalDate.of(2030, 1, 1), null),
                "無期限区間は将来の区間とも重複する（明示NULL=在籍中）");
        assertFalse(overlaps(customerId, LocalDate.of(2025, 1, 1), LocalDate.of(2025, 12, 31)),
                "開始前に閉じた区間とは重複しない");
    }

    /** design §6.1 / platform-invariants §1: valid_from <= :to AND (valid_to IS NULL OR valid_to >= :from) */
    private boolean overlaps(long customerId, LocalDate from, LocalDate to) {
        LambdaQueryWrapper<CustomerContact> w = new LambdaQueryWrapper<CustomerContact>()
                .eq(CustomerContact::getCustomerId, customerId)
                .eq(CustomerContact::getStatus, "有効")
                .and(inner -> inner
                        .isNull(CustomerContact::getValidTo)
                        .or()
                        .ge(CustomerContact::getValidTo, from));
        if (to != null) {
            w.le(CustomerContact::getValidFrom, to);
        }
        return !customerContactMapper.selectList(w).isEmpty();
    }

    // ======== 退職者の候補除外 (L2 / R1.3) ========

    @Test
    @DisplayName("退職者は新規宛先候補から除外されるが履歴としては残る")
    void retiredExcludedFromCandidates() {
        long customerId = newCustomer("退職者除外検証顧客");

        customerContactMapper.insert(CustomerContact.builder()
                .customerId(customerId).name("現役担当").email("active@example.com")
                .primaryFlag(0).validFrom(LocalDate.of(2026, 1, 1)).validTo(null)
                .status("有効").version(1).build());

        customerContactMapper.insert(CustomerContact.builder()
                .customerId(customerId).name("退職者").email("retired@example.com")
                .primaryFlag(0)
                .validFrom(LocalDate.of(2024, 1, 1)).validTo(LocalDate.of(2025, 12, 31))
                .status("退職").version(1).build());

        LocalDate today = LocalDate.of(2026, 8, 1);
        List<CustomerContact> candidates = customerContactMapper.selectList(
                new LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getCustomerId, customerId)
                        .eq(CustomerContact::getStatus, "有効")
                        .le(CustomerContact::getValidFrom, today)
                        .and(w -> w
                                .isNull(CustomerContact::getValidTo)
                                .or()
                                .ge(CustomerContact::getValidTo, today))
        );
        assertEquals(1, candidates.size(), "退職者は新規宛先候補に出ない");
        assertEquals("現役担当", candidates.get(0).getName());

        // 履歴は残る（論理削除ではない）
        List<CustomerContact> all = customerContactMapper.selectList(
                new LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getCustomerId, customerId));
        assertEquals(2, all.size(), "退職者の行は履歴として残る");
    }

    // ======== Lead / Opportunity 初期状態 (L2) ========

    @Test
    @DisplayName("lead: 初期状態は未対応、converted列とowner_user_idはNULL（未割当）")
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

    @Test
    @DisplayName("opportunity: 初期stageは見込、converted列と失注理由はNULL")
    void opportunityInitialState() {
        long customerId = newCustomer("初期商機検証顧客");

        Opportunity opp = Opportunity.builder()
                .customerId(customerId)
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

    // ======== 冪等変換の受け皿 (L2 / design §6.3) ========

    @Test
    @DisplayName("t_project.source_opportunity_idはUNIQUEで同一商機から2件目を拒否する")
    void projectSourceOpportunityIsUnique() {
        long customerId = newCustomer("冪等変換検証顧客");
        Opportunity opp = Opportunity.builder()
                .customerId(customerId).title("変換元商機").stage("交渉").version(1).build();
        opportunityMapper.insert(opp);

        jdbcTemplate.update(
                "INSERT INTO t_project (project_name, customer_id, status, source_opportunity_id, deleted_flag)"
                        + " VALUES ('変換案件1', ?, '募集中', ?, 0)", customerId, opp.getId());

        assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
                "INSERT INTO t_project (project_name, customer_id, status, source_opportunity_id, deleted_flag)"
                        + " VALUES ('変換案件2', ?, '募集中', ?, 0)", customerId, opp.getId()),
                "同一商機からは案件を1件しか作れない（受注2回実行で1件）");
    }

    // ======== 移行DML (L3 / R1.2): V73本体のSQLを実行して件数と値の一致を確認 ========

    @Test
    @DisplayName("V73の移行DMLを実行すると既存m_customer.contact_*が件数・値とも一致して移行される")
    void migrationDmlCopiesExistingCustomerContacts() {
        long withContact = newCustomerWithContact(
                "移行対象A", "移行 太郎", "migrate-a@example.com", "03-0000-0001",
                LocalDate.of(2024, 5, 20));
        long withContactNoEmail = newCustomerWithContact(
                "移行対象B", "移行 次郎", null, null, LocalDate.of(2023, 1, 2));
        long blankContact = newCustomerWithContact(
                "移行対象外_空文字", "", "blank@example.com", null, LocalDate.of(2024, 1, 1));
        long nullContact = newCustomer("移行対象外_NULL");

        // 移行対象の期待件数 = contact_personが非空かつ未削除の顧客数（V2のseed顧客も含む）
        Integer expected = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_customer"
                        + " WHERE contact_person IS NOT NULL AND contact_person <> '' AND deleted_flag = 0",
                Integer.class);

        int migrated = jdbcTemplate.update(migrationDmlFromV73());

        // 件数の一致: contact_personを持つ顧客が漏れなく、それだけが移行される
        assertEquals(expected, migrated, "contact_personが空/NULLの顧客は移行せず、それ以外は全件移行する");

        Map<String, Object> a = jdbcTemplate.queryForMap(
                "SELECT name, email, phone, primary_flag, valid_from, valid_to, status"
                        + " FROM t_customer_contact WHERE customer_id = ?", withContact);
        assertEquals("移行 太郎", a.get("name"));
        assertEquals("migrate-a@example.com", a.get("email"));
        assertEquals("03-0000-0001", a.get("phone"));
        assertEquals(1, ((Number) a.get("primary_flag")).intValue(), "移行行は主担当");
        assertEquals("有効", a.get("status"));
        assertNull(a.get("valid_to"), "移行行は無期限（在籍中）");
        assertEquals(LocalDate.of(2024, 5, 20), ((java.sql.Date) a.get("valid_from")).toLocalDate(),
                "valid_fromは顧客の登録日（移行実行日にすると過去asOfで0件になる）");

        Map<String, Object> b = jdbcTemplate.queryForMap(
                "SELECT name, email, phone FROM t_customer_contact WHERE customer_id = ?", withContactNoEmail);
        assertEquals("移行 次郎", b.get("name"));
        assertNull(b.get("email"));
        assertNull(b.get("phone"));

        assertEquals(0, countContacts(blankContact), "contact_personが空文字の顧客は移行しない");
        assertEquals(0, countContacts(nullContact), "contact_personがNULLの顧客は移行しない");

        // 再実行しても増えない（NOT EXISTSガード）
        int second = jdbcTemplate.update(migrationDmlFromV73());
        assertEquals(0, second, "移行DMLは再実行しても二重登録しない");
        assertEquals(1, countContacts(withContact));
    }

    @Test
    @DisplayName("V73の移行DMLはcreated_atが無い旧行をsentinel 1900-01-01へ倒す")
    void migrationDmlFallsBackToSentinelWhenCreatedAtIsNull() {
        long legacy = newCustomerWithContact(
                "移行対象_created_at無し", "旧 三郎", "legacy@example.com", null, null);

        jdbcTemplate.update(migrationDmlFromV73());

        java.sql.Date validFrom = jdbcTemplate.queryForObject(
                "SELECT valid_from FROM t_customer_contact WHERE customer_id = ?",
                java.sql.Date.class, legacy);
        assertNotNull(validFrom);
        assertEquals(LocalDate.of(1900, 1, 1), validFrom.toLocalDate());
    }

    private int countContacts(long customerId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_customer_contact WHERE customer_id = ?", Integer.class, customerId);
        return n == null ? 0 : n;
    }

    /**
     * V73の「6. 既存 contact 移行」節のINSERT文をファイル本体から抽出する。
     * H2用に書き直したコピーを実行すると、V73自体は一度も実行されないまま
     * 「移行を検証した」ことになってしまう（Round 1 の CRM-R1-P1-03）。
     */
    private String migrationDmlFromV73() {
        try {
            String sql = new PathMatchingResourcePatternResolver()
                    .getResource("classpath:db/migration/V73__crm_contact_lead_opportunity.sql")
                    .getContentAsString(StandardCharsets.UTF_8);
            int start = sql.indexOf("INSERT INTO t_customer_contact");
            assertTrue(start >= 0, "V73に既存contactの移行INSERTがありません");
            int end = sql.indexOf(';', start);
            assertTrue(end > start, "V73の移行INSERTが終端していません");
            return sql.substring(start, end);
        } catch (Exception e) {
            throw new IllegalStateException("V73を読み込めません", e);
        }
    }
}
