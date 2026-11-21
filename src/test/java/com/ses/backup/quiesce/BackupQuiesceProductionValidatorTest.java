package com.ses.backup.quiesce;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.config.AsyncConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * RB-QUIESCE BWQ-007（BWQ-AC-008-01・design.md §12.2）: prod の起動時検査。停止口（{@code enabled=false}）は形式の正しい変更
 * ticket と組でだけ許し、業務の DataSource の {@code readOnlyPropagatesToServer} を無効にする JDBC 設定（URL の query・
 * data-source-properties・Connector/J の展開後の属性）は起動を止める。prod 以外の {@code enabled=false} は何も検査しない。
 */
class BackupQuiesceProductionValidatorTest {

    private static final String MYSQL_URL = "jdbc:mysql://db.internal:3306/ses_manager_db?useUnicode=true"
            + "&characterEncoding=utf-8&serverTimezone=Asia/Tokyo";
    private static final String DSP = "spring.datasource.hikari.data-source-properties";

    // ---------------------------------------------------------------- 停止口と ticket

    @Test
    void prodでticketの無いenabled_falseは起動を中止する() {
        BackupQuiesceProductionValidator validator = validator(prod(), disabled(""));

        assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BACKUP_QUIESCE_DISABLED_TICKET").hasMessageContaining("停止口")
                .hasMessageContaining("deploy-runbook §1.0");
        assertThat(validator.isValidated()).isFalse();
        // 1回目で止まった検査は、もう一度呼んでも通さない
        assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "ticket=[{0}]")
    @ValueSource(strings = {"chg-1234", "CHG1234", "CHG-12a", "C-1", "CHG-", "-1234", "1CHG-1", "CHG_1234", "CHG-1234 X",
            "CHG--1", "ＣＨＧ-1234", "CHG-１２３"})
    void prodで形式の違うticketのenabled_falseは起動を中止し値を文言に出さない(String ticket) {
        BackupQuiesceProductionValidator validator = validator(prod(), disabled(ticket));
        // 別の形式違いの ticket と同じ文言になる＝値を文言に入れていない（「CHG-」等は文言の例の一部なので含みは見ない）
        String reference = catchThrowable(validator(prod(), disabled("x_x"))::validate).getMessage();

        assertThatThrownBy(validator::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BACKUP_QUIESCE_DISABLED_TICKET の形式が違います")
                .hasMessage(reference);
        assertThat(validator.isValidated()).isFalse();
    }

    @ParameterizedTest(name = "ticket=[{0}]")
    @ValueSource(strings = {"CHG-1234", "INC1-7", "OPS-20261121", "  CHG-1234  "})
    void prodで形式の正しいticketのenabled_falseは検査を通る(String ticket) {
        BackupQuiesceProductionValidator validator = validator(prod().withProperty("spring.datasource.url", MYSQL_URL),
                disabled(ticket));

        validator.validate();

        assertThat(validator.isValidated()).isTrue();
    }

    @Test
    void prod以外のenabled_falseはticketもJDBC設定も検査しない() {
        MockEnvironment test = new MockEnvironment().withProperty("spring.datasource.url",
                MYSQL_URL + "&readOnlyPropagatesToServer=false");
        test.setActiveProfiles("test");

        BackupQuiesceProductionValidator validator = validator(test, disabled(""));
        validator.validate();

        assertThat(validator.isValidated()).isTrue();
    }

    @Test
    void prodの判定はspring_profiles_activeのpropertyでなく有効なprofileで行う() {
        // @ActiveProfiles("prod") のように property を持たない prod でも検査が走る（CLAUDE.md の規則）
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        assertThat(environment.getProperty("spring.profiles.active")).isNull();

        assertThatThrownBy(validator(environment, disabled(""))::validate).isInstanceOf(IllegalStateException.class);
    }

    // ---------------------------------------------------------------- readOnlyPropagatesToServer

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"readOnlyPropagatesToServer=false", "readOnlyPropagatesToServer=FALSE",
            "readOnlyPropagatesToServer=no", "readOnlyPropagatesToServer=%66alse", "readOnlyPropagatesToServer=maybe"})
    void URLのqueryでreadOnlyPropagatesToServerを無効にすると起動を中止しURLを文言に出さない(String parameter) {
        MockEnvironment environment = prod().withProperty("spring.datasource.url",
                MYSQL_URL + "&password=secret-in-url&" + parameter);

        assertThatThrownBy(validator(environment, enabled())::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readOnlyPropagatesToServer").hasMessageContaining("[spring.datasource.url]")
                .satisfies(failure -> assertThat(failure.getMessage()).doesNotContain("secret-in-url", "db.internal"));
    }

    @Test
    void hikariのjdbc_urlとdata_source_propertiesのurlも同じ規則で見る() {
        assertThat(BackupQuiesceProductionValidator.readOnlyPropagationViolations(prod()
                .withProperty("spring.datasource.hikari.jdbc-url", MYSQL_URL + "&readOnlyPropagatesToServer=false")))
                .containsExactly("spring.datasource.hikari.jdbc-url");
        assertThat(BackupQuiesceProductionValidator.readOnlyPropagationViolations(prod()
                .withProperty(DSP + ".url", MYSQL_URL + "&readOnlyPropagatesToServer=false")))
                .containsExactly(DSP + ".url");
    }

    @Test
    void data_source_propertiesで無効にすると直接の指定と展開後の属性の両方で拒否する() {
        // Hikari は data-source-properties を driver の Properties として渡し、Connector/J は URL の属性に合わせる
        for (String key : List.of("readOnlyPropagatesToServer", "readonlypropagatestoserver", "READONLYPROPAGATESTOSERVER")) {
            List<String> violations = BackupQuiesceProductionValidator.readOnlyPropagationViolations(prod()
                    .withProperty("spring.datasource.url", MYSQL_URL).withProperty(DSP + "." + key, "false"));
            assertThat(violations).as(key).anySatisfy(name -> assertThat(name).isEqualToIgnoringCase(DSP + "." + key));
        }
        assertThat(BackupQuiesceProductionValidator.readOnlyPropagationViolations(prod()
                .withProperty("spring.datasource.url", MYSQL_URL).withProperty(DSP + ".readOnlyPropagatesToServer", "false")))
                .containsExactly(DSP + ".readOnlyPropagatesToServer", "spring.datasource.url");
    }

    @Test
    void Connector_Jが展開した後の属性で無効になる設定も拒否する() {
        // propertiesTransform（classpath の変換 class）は URL の文字列にも data-source-properties の key にも現れない値を書き込む
        String transform = MYSQL_URL + "&propertiesTransform=" + DisableReadOnlyPropagationTransform.class.getName();
        assertThat(effective(transform).getProperty("readOnlyPropagatesToServer")).isEqualTo("false");

        assertThat(BackupQuiesceProductionValidator.readOnlyPropagationViolations(prod()
                .withProperty("spring.datasource.url", transform))).containsExactly("spring.datasource.url");
        assertThat(BackupQuiesceProductionValidator.readOnlyPropagationViolations(prod()
                .withProperty("spring.datasource.url", MYSQL_URL)
                .withProperty(DSP + ".propertiesTransform", DisableReadOnlyPropagationTransform.class.getName())))
                .containsExactly("spring.datasource.url");
    }

    @Test
    void 有効の値と既定のURLとConnector_J以外のURLは通り確かめられないURLは拒否する() {
        for (String url : List.of(MYSQL_URL, MYSQL_URL + "&readOnlyPropagatesToServer=true",
                MYSQL_URL + "&readOnlyPropagatesToServer=yes", MYSQL_URL + "&useConfigs=maxPerformance",
                "jdbc:h2:mem:testdb;MODE=MySQL")) {
            assertThat(BackupQuiesceProductionValidator.readOnlyPropagationViolations(prod()
                    .withProperty("spring.datasource.url", url))).as(url).isEmpty();
        }
        assertThat(BackupQuiesceProductionValidator.readOnlyPropagationViolations(prod())).isEmpty();
        // 複数 host・解析できない URL は展開後の属性を確かめられない（fail-closed）
        for (String url : List.of("jdbc:mysql://h1:3306,h2:3306/ses", "jdbc:mysql:loadbalance://h1,h2/ses",
                MYSQL_URL + "&propertiesTransform=com.example.NoSuchTransform")) {
            assertThat(BackupQuiesceProductionValidator.readOnlyPropagationViolations(prod()
                    .withProperty("spring.datasource.url", url))).as(url)
                    .containsExactly("spring.datasource.url(unparsable)");
        }
    }

    @Test
    void prod以外でもenabled_trueならJDBC設定を検査する() {
        MockEnvironment dev = new MockEnvironment().withProperty("spring.datasource.url",
                MYSQL_URL + "&readOnlyPropagatesToServer=false");
        dev.setActiveProfiles("dev");

        assertThatThrownBy(validator(dev, enabled())::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("readOnlyPropagatesToServer");
    }

    @Test
    void prodの停止口でもJDBC設定を検査する() {
        MockEnvironment environment = prod().withProperty("spring.datasource.url",
                MYSQL_URL + "&readOnlyPropagatesToServer=false");

        assertThatThrownBy(validator(environment, disabled("CHG-1234"))::validate)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("readOnlyPropagatesToServer");
    }

    // ---------------------------------------------------------------- context（登録の前に止まる・停止口の動き）

    @Test
    void ticketの無い停止口のcontextはinstanceを登録する前に起動を中止する() throws SQLException {
        String url = h2Url();
        prodRunner(url, "app.backup-quiesce.enabled=false").run(context -> {
            assertThat(context).hasFailed();
            assertThat(causes(context.getStartupFailure()))
                    .anySatisfy(message -> assertThat(message).contains("BACKUP_QUIESCE_DISABLED_TICKET"));
        });
        assertThat(instanceRows(url)).as("検査は静止応答の登録より前に動く").isZero();
    }

    @Test
    void 形式の正しいticketの停止口はDISABLEDで登録しERRORとgaugeを出す() {
        Logger logger = (Logger) LoggerFactory.getLogger(BackupQuiesceResponder.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            prodRunner(h2Url(), "app.backup-quiesce.enabled=false", "app.backup-quiesce.disabled-ticket=CHG-1234")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context.getBean(BackupQuiesceProductionValidator.class).isValidated()).isTrue();
                        BackupQuiesceLifecycleAnchor anchor = context.getBean(BackupQuiesceLifecycleAnchor.class);
                        assertThat(anchor.gate().state()).isEqualTo(BackupWriteGate.State.DISABLED);
                        assertThat(new org.springframework.jdbc.core.JdbcTemplate(
                                context.getBean(javax.sql.DataSource.class)).queryForObject(
                                "SELECT gate_state FROM sys_backup_quiesce_instance WHERE instance_id = ?", String.class,
                                anchor.instanceId())).isEqualTo("DISABLED");

                        SimpleMeterRegistry registry = new SimpleMeterRegistry();
                        context.getBean(BackupQuiesceMetrics.class).bindTo(registry);
                        assertThat(registry.get("ses.backup.quiesce.disabled").gauge().value()).isEqualTo(1.0);
                    });
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).startsWith("errorCode=BACKUP_QUIESCE_DISABLED").contains("CHG-1234");
        });
    }

    // ---------------------------------------------------------------- helper

    /** 試験用の変換 class: 解析の中で readOnlyPropagatesToServer を無効にする（driver が classpath から読み込む）。 */
    public static final class DisableReadOnlyPropagationTransform implements com.mysql.cj.conf.ConnectionPropertiesTransform {
        @Override
        public Properties transformProperties(Properties properties) {
            properties.setProperty("readOnlyPropagatesToServer", "false");
            return properties;
        }
    }

    private static Properties effective(String url) {
        Properties properties = new Properties();
        properties.putAll(com.mysql.cj.conf.ConnectionUrl.getConnectionUrlInstance(url, new Properties()).getMainHost()
                .getHostProperties());
        return properties;
    }

    private static MockEnvironment prod() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static BackupQuiesceProperties enabled() {
        return new BackupQuiesceProperties();
    }

    private static BackupQuiesceProperties disabled(String ticket) {
        BackupQuiesceProperties properties = new BackupQuiesceProperties();
        properties.setEnabled(false);
        properties.setDisabledTicket(ticket);
        return properties;
    }

    private static BackupQuiesceProductionValidator validator(MockEnvironment environment,
                                                              BackupQuiesceProperties properties) {
        return new BackupQuiesceProductionValidator(environment, properties);
    }

    private static String h2Url() {
        return "jdbc:h2:mem:bq_validator_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:sql/schema-backup-quiesce-h2.sql'";
    }

    private static ApplicationContextRunner prodRunner(String url, String... properties) {
        return new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class,
                        DataSourceAutoConfiguration.class))
                .withUserConfiguration(AsyncConfig.class, BackupQuiesceConfiguration.class)
                .withPropertyValues("spring.datasource.url=" + url, "spring.datasource.username=sa",
                        "spring.datasource.password=", "app.scheduling.enabled=false")
                .withPropertyValues(properties);
    }

    private static long instanceRows(String url) throws SQLException {
        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM sys_backup_quiesce_instance")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static List<String> causes(Throwable failure) {
        List<String> messages = new java.util.ArrayList<>();
        for (Throwable cause = failure; cause != null && messages.size() < 32; cause = cause.getCause()) {
            messages.add(String.valueOf(cause.getMessage()));
        }
        return messages;
    }
}
