package com.ses.backup.quiesce;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-003（design.md §3.4・§3.10・§6.5.4）: 信号表と制御用・heartbeat 用の接続を使うのが
 * {@code com.ses.backup.quiesce} の中だけで、MyBatis-Plus の mapper・controller が信号表の名前を含まず、信号表の SQL が
 * {@code NOW(}・{@code CURRENT_TIMESTAMP}・{@code LOCALTIMESTAMP} を使わず、制御用の pool が {@code DataSource} の bean に
 * ならないことを静的に固定する。
 */
class BackupQuiesceControlPoolUsageTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path PACKAGE = MAIN.resolve("com/ses/backup/quiesce");
    private static final List<String> PACKAGE_ONLY_TYPES = List.of("BackupQuiesceControlConnectionPool",
            "BackupQuiesceSignalRepository", "JdbcBackupQuiesceSignalRepository");
    private static final List<String> SIGNAL_TABLES = List.of("sys_backup_quiesce_request", "sys_backup_quiesce_instance",
            "sys_backup_quiesce_ack");
    private static final Pattern LOCAL_CLOCK_SQL = Pattern.compile(
            "\\bNOW\\s*\\(|CURRENT_TIMESTAMP|LOCALTIMESTAMP|\\bSYSDATE\\s*\\(|\\bCURDATE\\s*\\(|\\bCURTIME\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    @Test
    void 制御用の接続とrepositoryを参照するのはcom_ses_backup_quiesceの中だけ() throws IOException {
        Map<String, String> offenders = new TreeMap<>();
        for (Path source : mainSources()) {
            if (source.startsWith(PACKAGE)) {
                continue;
            }
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (String type : PACKAGE_ONLY_TYPES) {
                if (Pattern.compile("\\b" + type + "\\b").matcher(text).find()) {
                    offenders.put(MAIN.relativize(source).toString(), type);
                }
            }
            for (String table : SIGNAL_TABLES) {
                if (text.toLowerCase(Locale.ROOT).contains(table)) {
                    offenders.put(MAIN.relativize(source).toString(), table);
                }
            }
        }
        assertThat(offenders).as("信号表・制御用の接続・repository を package の外から使っている").isEmpty();
    }

    @Test
    void mapperとcontrollerは信号表の名前を含まない() throws IOException {
        for (Path root : List.of(MAIN.resolve("com/ses/mapper"), MAIN.resolve("com/ses/controller"),
                Path.of("src/main/resources/mapper"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    String text = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                    assertThat(text).as(file.toString()).doesNotContain("sys_backup_quiesce_");
                }
            }
        }
    }

    @Test
    void 信号表のSQLはUTC_TIMESTAMPだけで時刻を扱う() throws IOException {
        String repository = Files.readString(PACKAGE.resolve("JdbcBackupQuiesceSignalRepository.java"),
                StandardCharsets.UTF_8);
        assertThat(repository).contains("UTC_TIMESTAMP(6)");
        try (Stream<Path> files = Files.list(PACKAGE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String code = withoutComments(Files.readString(file, StandardCharsets.UTF_8));
                assertThat(LOCAL_CLOCK_SQL.matcher(code).find())
                        .as("%s は NOW(・CURRENT_TIMESTAMP・LOCALTIMESTAMP 等を使わない", file.getFileName()).isFalse();
            }
        }
    }

    @Test
    void 制御用の接続poolはDataSourceのbeanにしない() {
        assertThat(Modifier.isPublic(BackupQuiesceControlConnectionPool.class.getModifiers())).isFalse();
        assertThat(DataSource.class.isAssignableFrom(BackupQuiesceControlConnectionPool.class)).isFalse();
        assertThat(Modifier.isPublic(JdbcBackupQuiesceSignalRepository.class.getModifiers())).isFalse();
        for (Method method : BackupQuiesceConfiguration.class.getDeclaredMethods()) {
            if (method.isAnnotationPresent(Bean.class)) {
                assertThat(DataSource.class.isAssignableFrom(method.getReturnType())).as(method.getName()).isFalse();
                assertThat(method.getReturnType()).isNotEqualTo(BackupQuiesceControlConnectionPool.class);
            }
        }
    }

    @Test
    void 制御用の接続は短いtimeoutを持ち業務のpoolとは別の名前で作る() {
        org.springframework.boot.autoconfigure.jdbc.DataSourceProperties properties =
                new org.springframework.boot.autoconfigure.jdbc.DataSourceProperties();
        properties.setUrl("jdbc:mysql://db.invalid:3306/ses_manager_db?serverTimezone=Asia/Tokyo");
        properties.setUsername("ses_app");
        properties.setPassword("not-used");
        com.zaxxer.hikari.HikariConfig control = BackupQuiesceControlConnectionPool.config(properties,
                BackupQuiesceControlConnectionPool.CONTROL_POOL_NAME, 2);
        assertThat(control.getPoolName()).isEqualTo("ses-backup-quiesce-control");
        assertThat(control.getMaximumPoolSize()).isEqualTo(2);
        assertThat(control.getMinimumIdle()).isZero();
        assertThat(control.getConnectionTimeout()).isEqualTo(3000);
        assertThat(control.getValidationTimeout()).isEqualTo(2000);
        assertThat(control.getConnectionInitSql()).isEqualTo("SET SESSION innodb_lock_wait_timeout = 5");
        assertThat(control.getDataSourceProperties()).containsEntry("connectTimeout", "5000")
                .containsEntry("socketTimeout", "10000");
        assertThat(control.isAutoCommit()).isTrue();

        properties.setUrl("jdbc:h2:mem:control");
        assertThat(BackupQuiesceControlConnectionPool.config(properties,
                BackupQuiesceControlConnectionPool.HEARTBEAT_POOL_NAME, 1).getConnectionInitSql())
                .as("MySQL 以外（試験の H2）では MySQL の session 変数を設定しない").isNull();
    }

    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(path -> path.toString().endsWith(".java")).toList();
        }
    }

    private static String withoutComments(String code) {
        String noBlock = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL).matcher(code).replaceAll(" ");
        return Pattern.compile("//[^\\n]*").matcher(noBlock).replaceAll("");
    }
}
