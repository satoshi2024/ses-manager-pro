package com.ses.privacy.foundation;

import org.testcontainers.containers.MySQLContainer;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Testcontainersの実体とSpring contextだけから合成接続を作るpackage-private capability。 */
final class JournalTestConnectionCapability {
    private final String containerId;
    private final String database;
    private final String host;
    private final int mappedPort;
    private final String manifestHash;
    private final String schemaHash;
    private final String jdbcUrl;
    private final String adminUsername;
    private final String adminPassword;
    private final Environment environment;
    private final java.util.concurrent.atomic.AtomicBoolean w3aStoreIssued = new java.util.concurrent.atomic.AtomicBoolean();

    private JournalTestConnectionCapability(MySQLContainer<?> container, ApplicationContext context) {
        this.environment = context.getEnvironment();
        String[] active = environment.getActiveProfiles();
        String[] defaults = environment.getDefaultProfiles();
        if (!Arrays.asList(active).contains("test") || Arrays.stream(active).anyMatch("prod"::equals)
                || Arrays.stream(defaults).anyMatch("prod"::equals) || active.length == 0) {
            throw new SecurityException("synthetic journal capabilityはactive test context専用です");
        }
        if (!container.isRunning() || container.getContainerId() == null || container.getContainerId().isBlank()) {
            throw new SecurityException("harness所有の起動済みMySQL containerが必要です");
        }
        String jdbcUrl = container.getJdbcUrl();
        String username = container.getUsername();
        String password = container.getPassword();
        String containerHost = container.getHost();
        int containerPort = container.getMappedPort(3306);
        URI jdbcUri = URI.create(jdbcUrl.substring("jdbc:".length()));
        if (!jdbcUrl.contains(container.getDatabaseName()) || username == null || password == null
                || !containerHost.equalsIgnoreCase(jdbcUri.getHost()) || containerPort != jdbcUri.getPort()) {
            throw new SecurityException("container接続識別情報が整合しません");
        }
        this.containerId = container.getContainerId();
        this.database = container.getDatabaseName();
        if (!database.matches("sfj_[a-f0-9]{32}")) {
            throw new SecurityException("harnessが生成したdatabase名ではありません");
        }
        this.host = containerHost;
        this.mappedPort = containerPort;
        try (var schema = JournalTestConnectionCapability.class.getClassLoader()
                .getResourceAsStream("privacy-journal/schema-test.sql")) {
            if (schema == null) throw new SecurityException("synthetic schema resourceがありません");
            this.schemaHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(schema.readAllBytes()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new SecurityException("synthetic schema manifestを作成できません", e);
        }
        this.manifestHash = sha256(containerId + "\n" + database + "\n" + host + "\n" + mappedPort + "\n" + schemaHash);
        this.jdbcUrl = jdbcUrl;
        this.adminUsername = username;
        this.adminPassword = password;
    }

    static JournalTestConnectionCapability fromOwnedContainer(MySQLContainer<?> container, ApplicationContext context) {
        return new JournalTestConnectionCapability(container, context);
    }

    String containerId() { return containerId; }
    String database() { return database; }
    String host() { return host; }
    int mappedPort() { return mappedPort; }
    String manifestHash() { return manifestHash; }
    String schemaHash() { return schemaHash; }
    DataSource adminDataSource() {
        requireTestEnvironment();
        return new DriverManagerDataSource(jdbcUrl, adminUsername, adminPassword);
    }
    W3aSyntheticPreviewStore createW3aPreviewStore() {
        requireTestEnvironment();
        if (!w3aStoreIssued.compareAndSet(false, true)) throw new SecurityException("W3a store capabilityは一度だけ発行できます");
        return W3aSyntheticPreviewStore.fromOwnedContainer(adminDataSource(), containerId, database);
    }
    DataSource createJournalWriterDataSource() {
        requireTestEnvironment();
        String user = "sfw2w_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String password = UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        admin.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + password + "'");
        admin.execute("GRANT SELECT, INSERT, UPDATE ON `" + database + "`.sf_journal_head TO '" + user + "'@'%'");
        for (String table : List.of("sf_journal_event", "sf_journal_command", "sf_journal_action", "sf_journal_lease")) {
            admin.execute("GRANT SELECT, INSERT ON `" + database + "`." + table + " TO '" + user + "'@'%'");
        }
        return new DriverManagerDataSource(jdbcUrl, user, password);
    }
    Environment environment() { requireTestEnvironment(); return environment; }

    private void requireTestEnvironment() {
        String[] active = environment.getActiveProfiles();
        String[] defaults = environment.getDefaultProfiles();
        if (!Arrays.asList(active).contains("test") || Arrays.stream(active).anyMatch("prod"::equals)
                || Arrays.stream(defaults).anyMatch("prod"::equals)) {
            throw new SecurityException("synthetic journal capabilityはactive test context専用です");
        }
    }

    static String randomDatabase() { return "sfj_" + UUID.randomUUID().toString().replace("-", ""); }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256が利用できません", e);
        }
    }
}
