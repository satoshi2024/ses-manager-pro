package com.ses.service.security.epoch;

import com.ses.entity.SystemConfig;
import com.ses.service.RoleMenuService;
import com.ses.service.SystemConfigService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * M2（NF01細粒度epoch計画 §6.1・§7.3 Demo (1)(2)、W3-15）: 実MySQL 8で、role menuの置換と認可キーの更新が
 * 全体epoch行（{@code t_authorization_global_epoch}）を最初にX lockし、CONFIRMのreader（{@code FOR SHARE}）と
 * 両順序で直列化されることを確かめる。
 *
 * <ul>
 *   <li>writer先行: readerはwriterのcommitまで待ち、commit後の新しい値（+1）を読む。writerは全体epoch行の共有lockを持たない
 *       （S→X昇格なし）。</li>
 *   <li>reader先行: writerはreaderの解放まで待ち、解放後に1だけ進める。</li>
 *   <li>同じ内容の再保存・非認可キーの変更・認可キーの同じ値での再保存では進めない。</li>
 * </ul>
 *
 * <p>本classは専用のMySQL containerを使い、全体epochはcommitした値の差分で比べる（試験の実行順に依存しない）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class GlobalEpochFenceMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_nf01_global_epoch")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    private static final String GLOBAL_TABLE = "t_authorization_global_epoch";
    private static final String ROLE = "マネージャー";
    private static final Runnable NO_SETUP = () -> { };

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RoleMenuService roleMenuService;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private AuthorizationEpochReader reader;

    @Test
    void role_menuのwriter先行ではreaderがcommitを待ち1進んだ全体epochを読む() throws Exception {
        long before = globalEpoch();
        List<Long> next = alternateMenus();

        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, GLOBAL_TABLE, NO_SETUP,
                () -> roleMenuService.replaceMenus(ROLE, next), reader::readGlobal, "role menu writer先行");

        assertEquals(before + 1, read);
        assertEquals(before + 1, globalEpoch());
    }

    @Test
    void role_menuのreader先行ではwriterが解放を待ってから1だけ進める() throws Exception {
        long before = globalEpoch();
        List<Long> next = alternateMenus();

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, GLOBAL_TABLE, NO_SETUP,
                reader::readGlobal, () -> {
                    roleMenuService.replaceMenus(ROLE, next);
                    return null;
                }, "role menu reader先行");

        assertEquals(before + 1, globalEpoch());
        assertEquals(new TreeSet<>(next), currentMenus());
    }

    @Test
    void 認可キーのwriter先行ではreaderがcommitを待ち1進んだ全体epochを読む() throws Exception {
        long before = globalEpoch();
        String flipped = flippedScopeValue();

        long read = EpochFenceMySqlSupport.assertReaderWaitsForWriter(jdbc, transactionManager, GLOBAL_TABLE, NO_SETUP,
                () -> systemConfigService.updateAll(List.of(scope(flipped))), reader::readGlobal, "認可キー writer先行");

        assertEquals(before + 1, read);
        assertEquals(flipped, scopeValue());
    }

    @Test
    void role_menuを外側transactionなしで呼ぶと宣言どおりREAD_COMMITTEDでreaderの解放を待つ() throws Exception {
        long before = globalEpoch();
        List<Long> next = alternateMenus();

        String isolation = EpochFenceMySqlSupport.assertDirectWriterWaitsForReaderAndReadIsolation(jdbc,
                transactionManager, GLOBAL_TABLE, NO_SETUP, reader::readGlobal,
                () -> roleMenuService.replaceMenus(ROLE, next), "role menu 外側transactionなし");

        assertEquals("READ-COMMITTED", isolation, "replaceMenusはREAD_COMMITTEDで実行される（W3-15 review P3-3）");
        assertEquals(before + 1, globalEpoch());
    }

    @Test
    void 認可キーのreader先行ではwriterが解放を待ってから1だけ進める() throws Exception {
        long before = globalEpoch();
        String flipped = flippedScopeValue();

        EpochFenceMySqlSupport.assertWriterWaitsForReader(jdbc, transactionManager, GLOBAL_TABLE, NO_SETUP,
                reader::readGlobal, () -> {
                    systemConfigService.updateAll(List.of(scope(flipped)));
                    return null;
                }, "認可キー reader先行");

        assertEquals(before + 1, globalEpoch());
    }

    @Test
    void 同じ内容の再保存と非認可キーと同じ値の認可キーでは全体epochを進めない() {
        // 依存menuの自動追加が起きない集合へ先に置き換えてから測る（この置換自体は別のcommitで1進む）。
        roleMenuService.replaceMenus(ROLE, alternateMenus());
        long before = globalEpoch();

        roleMenuService.replaceMenus(ROLE, List.copyOf(currentMenus()));
        String benchDays = jdbc.queryForList("SELECT config_value FROM m_system_config WHERE config_key = ?",
                String.class, "notice.bench-warn-days").stream().findFirst().orElse(null);
        systemConfigService.updateAll(List.of(new SystemConfig("notice.bench-warn-days",
                "61".equals(benchDays) ? "62" : "61", "ベンチ警告日数")));
        systemConfigService.updateAll(List.of(scope(scopeValue())));

        assertEquals(before, globalEpoch(), "集合・値が変わらない更新と非認可キーでは全体epochを進めない");
    }

    // ===== helper =====

    private long globalEpoch() {
        return jdbc.queryForObject("SELECT epoch FROM t_authorization_global_epoch WHERE singleton_id = 1", Long.class);
    }

    private Set<Long> currentMenus() {
        return new TreeSet<>(jdbc.queryForList("SELECT menu_id FROM t_role_menu WHERE role = ?", Long.class, ROLE));
    }

    /** 現在の集合と必ず異なり、依存menuの自動追加が起きない集合（dashboard だけ ⇔ dashboard＋customer）。 */
    private List<Long> alternateMenus() {
        Long dashboard = jdbc.queryForObject("SELECT id FROM m_menu WHERE menu_key = 'dashboard'", Long.class);
        Long customer = jdbc.queryForObject("SELECT id FROM m_menu WHERE menu_key = 'customer'", Long.class);
        return currentMenus().equals(Set.of(dashboard)) ? List.of(dashboard, customer) : List.of(dashboard);
    }

    private String scopeValue() {
        return jdbc.queryForList("SELECT config_value FROM m_system_config WHERE config_key = ?", String.class,
                AuthorizationConfigKeys.SALES_OWN_DATA_ONLY).stream().findFirst().orElse(null);
    }

    private String flippedScopeValue() {
        return "true".equals(scopeValue()) ? "false" : "true";
    }

    private static SystemConfig scope(String value) {
        return new SystemConfig(AuthorizationConfigKeys.SALES_OWN_DATA_ONLY, value, "営業の担当データ限定");
    }
}
