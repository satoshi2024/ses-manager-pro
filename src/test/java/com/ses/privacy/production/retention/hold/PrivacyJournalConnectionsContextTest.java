package com.ses.privacy.production.retention.hold;

import com.ses.mapper.SysUserMapper;
import com.ses.privacy.production.PrivacyFeatureProperties;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.Availability;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Journal 接続の holder（{@link PrivacyJournalConnections}）が application の DataSource 自動構成に影響しないことを、
 * journal 設定ありの完全な context（H2 の application DB）で固定する（NF07-A1J、journal-witness-grant-design.md §2.1）。
 *
 * <ul>
 *   <li>{@code DataSource} 型の Bean は application の1つだけで、{@code JdbcTemplate}・mapper は application DB のまま。</li>
 *   <li>holder は設定がある場合だけ内部 pool を作り、{@code DataSource} を public に出さない。</li>
 *   <li>Journal に届かなければ起動時検査は固定 code の {@code UNAVAILABLE} になり、prod 以外（test profile は flag ON）と
 *       prod の flag OFF では起動を止めない。prod の flag ON では起動しない。</li>
 *   <li>journal 設定が無ければ pool を作らず、接続も application DB の照会も試みない。prod の flag ON で設定が無ければ
 *       起動しない（prod 以外では起動する）。</li>
 * </ul>
 * Journal の接続先は到達しない loopback の port（実 MySQL は不要）。実 MySQL への検査は shard-2 の MySQL test が行う。
 */
@SpringBootTest(properties = {
        "privacy.journal.datasource.url=" + PrivacyJournalConnectionsContextTest.UNREACHABLE_URL,
        "privacy.journal.datasource.username=" + PrivacyJournalConnectionsContextTest.JOURNAL_USER,
        "privacy.journal.datasource.password=" + PrivacyJournalConnectionsContextTest.JOURNAL_SECRET,
        "privacy.journal.datasource.connection-timeout=PT1S",
        "privacy.journal.journal-id=journal-context-01"
})
@ActiveProfiles("test")
@Transactional
class PrivacyJournalConnectionsContextTest {

    static final String UNREACHABLE_URL = "jdbc:mysql://127.0.0.1:9/privacy_journal?sslMode=VERIFY_IDENTITY";
    static final String JOURNAL_USER = "nf07_journal_reader";
    static final String JOURNAL_SECRET = "context-test-only-credential";

    @Autowired
    private ApplicationContext context;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private PrivacyJournalConnections connections;
    @Autowired
    private PrivacyJournalStartupVerifier verifier;

    @Test
    void journal設定があってもDataSource型のBeanはapplicationの1つだけ() {
        assertThat(context.getBeanNamesForType(DataSource.class)).hasSize(1);
        assertThat(jdbcTemplate.getDataSource()).isSameAs(dataSource);
        assertThat(context.getBeanNamesForType(PrivacyJournalConnections.class)).hasSize(1);
        assertThat(connections).isNotInstanceOf(DataSource.class);
        assertThat(connections.isConfigured()).isTrue();
        assertThat(connections.contractViolations()).isEmpty();
        assertThat(connections.isPoolCreated()).as("設定がある場合だけ内部 pool を作る").isTrue();
        assertThat(Arrays.stream(PrivacyJournalConnections.class.getMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getReturnType))
                .as("holder は内部 pool を public な method で出さない")
                .noneMatch(DataSource.class::isAssignableFrom);
    }

    @Test
    void applicationのmapperとJdbcTemplateはapplication_DBへ接続したまま() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getURL()).as("application DB（test は H2）").startsWith("jdbc:h2:");
            assertThat(jdbcTemplate.queryForObject("SELECT DATABASE()", String.class))
                    .as("JdbcTemplate の接続先は application schema").isEqualToIgnoringCase(connection.getCatalog());
        }
        assertThat(sysUserMapper.selectCount(null)).as("mapper は application DB の表を読める").isNotNull();
    }

    @Test
    void prod以外ではJournalに届かなくても起動を止めず理由は固定codeだけ() {
        // test profile は既存 test の意味を保つため privacy.retention.enabled=true（application-test.yml）
        PrivacyJournalStartupVerifier.Result result = verifier.startupResult();

        assertThat(verifier.holdStartRequired()).isTrue();
        assertThat(result.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(result.violations()).containsExactly("journal.unreachable");
        assertThat(String.join(",", result.violations()))
                .doesNotContain("127.0.0.1", JOURNAL_USER, JOURNAL_SECRET);
    }

    @Test
    void journal設定が無ければpoolを作らず接続もapplication_DBの照会もしない() {
        AtomicInteger attempts = new AtomicInteger();
        new ApplicationContextRunner()
                .withUserConfiguration(PrivacyJournalConfiguration.class)
                .withBean(PrivacyFeatureProperties.class)
                .withBean(JdbcTemplate.class, () -> new JdbcTemplate(tripwire(attempts)))
                .run(runner -> {
                    assertThat(runner).hasNotFailed();
                    PrivacyJournalConnections unconfigured = runner.getBean(PrivacyJournalConnections.class);
                    assertThat(unconfigured.isConfigured()).isFalse();
                    assertThat(unconfigured.isPoolCreated()).isFalse();
                    assertThat(runner.getBean(PrivacyJournalStartupVerifier.class).startupResult().availability())
                            .isEqualTo(Availability.NOT_CONFIGURED);
                    assertThat(runner.getBeanNamesForType(DataSource.class)).isEmpty();
                });
        assertThat(attempts).as("application DB への接続も試みない").hasValue(0);
    }

    @Test
    void prodでflag_ONなのにjournal設定が無ければ起動しない() {
        new ApplicationContextRunner()
                .withInitializer(runner -> runner.getEnvironment().setActiveProfiles("prod"))
                .withUserConfiguration(PrivacyJournalConfiguration.class)
                .withBean(PrivacyFeatureProperties.class, () -> features(true))
                .run(runner -> {
                    assertThat(runner).hasFailed();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(runner.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("journal.notConfigured");
                });
    }

    @Test
    void prod以外ではflag_ONでjournal設定が無くても起動する() {
        new ApplicationContextRunner()
                .withInitializer(runner -> runner.getEnvironment().setActiveProfiles("test"))
                .withUserConfiguration(PrivacyJournalConfiguration.class)
                .withBean(PrivacyFeatureProperties.class, () -> features(true))
                .run(runner -> {
                    assertThat(runner).hasNotFailed();
                    assertThat(runner.getBean(PrivacyJournalStartupVerifier.class).startupResult().availability())
                            .isEqualTo(Availability.NOT_CONFIGURED);
                });
    }

    @Test
    void prodでflag_OFFならJournalに届かなくても起動しUNAVAILABLEになる() {
        new ApplicationContextRunner()
                .withInitializer(runner -> runner.getEnvironment().setActiveProfiles("prod"))
                .withUserConfiguration(PrivacyJournalConfiguration.class)
                .withPropertyValues(unreachableJournal("journal-context-03"))
                .withBean(PrivacyFeatureProperties.class, () -> features(false))
                .run(runner -> {
                    assertThat(runner).hasNotFailed();
                    assertThat(runner.getBean(PrivacyJournalStartupVerifier.class).startupResult().violations())
                            .containsExactly("journal.unreachable");
                    assertThat(runner.getBeanNamesForType(DataSource.class)).isEmpty();
                });
    }

    @Test
    void prodでflag_ONなのにJournalに届かなければ起動しない() {
        new ApplicationContextRunner()
                .withInitializer(runner -> runner.getEnvironment().setActiveProfiles("prod"))
                .withUserConfiguration(PrivacyJournalConfiguration.class)
                .withPropertyValues(unreachableJournal("journal-context-02"))
                .withBean(PrivacyFeatureProperties.class, () -> features(true))
                .run(runner -> {
                    assertThat(runner).hasFailed();
                    assertThat(NestedExceptionUtils.getMostSpecificCause(runner.getStartupFailure()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("journal.unreachable")
                            .hasMessageNotContaining("127.0.0.1")
                            .hasMessageNotContaining(JOURNAL_USER)
                            .hasMessageNotContaining(JOURNAL_SECRET);
                });
    }

    private static String[] unreachableJournal(String journalId) {
        return new String[]{"privacy.journal.datasource.url=" + UNREACHABLE_URL,
                "privacy.journal.datasource.username=" + JOURNAL_USER,
                "privacy.journal.datasource.password=" + JOURNAL_SECRET,
                "privacy.journal.datasource.connection-timeout=PT1S",
                "privacy.journal.journal-id=" + journalId};
    }

    private static PrivacyFeatureProperties features(boolean retention) {
        PrivacyFeatureProperties properties = new PrivacyFeatureProperties();
        properties.getRetention().setEnabled(retention);
        return properties;
    }

    private static DataSource tripwire(AtomicInteger attempts) {
        return (DataSource) Proxy.newProxyInstance(PrivacyJournalConnectionsContextTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        attempts.incrementAndGet();
                        throw new AssertionError("journal 設定が無い間は application DB も照会しない");
                    }
                    if ("toString".equals(method.getName())) {
                        return "tripwire";
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
