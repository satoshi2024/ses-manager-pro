package com.ses.privacy.foundation;

import org.testcontainers.containers.MySQLContainer;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** 3台のharness所有MySQLからだけW2b用test接続とschemaを作るcapability。 */
final class W2bTestConnectionCapability {
    enum Store { JOURNAL, APPLICATION, WITNESS }

    private final ApplicationContext context;
    private final Store store;
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String database;
    private final String schemaResource;
    private final String containerId;

    private W2bTestConnectionCapability(MySQLContainer<?> container, ApplicationContext context, Store store) {
        W2bTestEnvironment.requireTest(context);
        if (container == null || !container.isRunning() || container.getContainerId() == null
                || container.getContainerId().isBlank()) {
            throw new SecurityException("起動済みharness所有MySQLが必要です");
        }
        this.context = context;
        this.store = store;
        this.jdbcUrl = container.getJdbcUrl();
        this.username = container.getUsername();
        this.password = container.getPassword();
        this.database = container.getDatabaseName();
        this.containerId = container.getContainerId();
        String prefix = switch (store) {
            case JOURNAL -> "sfj_";
            case APPLICATION -> "sfwa_";
            case WITNESS -> "sfww_";
        };
        this.schemaResource = switch (store) {
            case JOURNAL -> "privacy-journal/schema-test.sql";
            case APPLICATION -> "privacy-journal/w2b-app-schema.sql";
            case WITNESS -> "privacy-journal/w2b-witness-schema.sql";
        };
        if (database == null || !database.matches(prefix + "[a-f0-9]{32}") || username == null || password == null) {
            throw new SecurityException("W2b harnessが生成したDB識別情報ではありません");
        }
        URI uri = URI.create(jdbcUrl.substring("jdbc:".length()));
        if (!jdbcUrl.contains(database) || !container.getHost().equalsIgnoreCase(uri.getHost())
                || container.getMappedPort(3306) != uri.getPort()) {
            throw new SecurityException("container接続識別情報が一致しません");
        }
    }

    static W2bTestConnectionCapability fromOwnedContainer(MySQLContainer<?> container,
                                                           ApplicationContext context, Store store) {
        return new W2bTestConnectionCapability(container, context, store);
    }

    String containerId() { W2bTestEnvironment.requireTest(context); return containerId; }
    String database() { W2bTestEnvironment.requireTest(context); return database; }
    String schemaHash() {
        W2bTestEnvironment.requireTest(context);
        try (var input = getClass().getClassLoader().getResourceAsStream(schemaResource)) {
            if (input == null) throw new SecurityException("W2b test schemaがありません");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            throw new SecurityException("W2b schema hashを計算できません", e);
        }
    }

    DataSource adminDataSource() {
        W2bTestEnvironment.requireTest(context);
        return new ProfileGuardedDataSource(new DriverManagerDataSource(jdbcUrl, username, password), context);
    }

    void initializeSchema() {
        W2bTestEnvironment.requireTest(context);
        new ResourceDatabasePopulator(new ClassPathResource(schemaResource)).execute(adminDataSource());
    }

    DataSource createApplicationProjectionDataSource() {
        requireStore(Store.APPLICATION);
        String user = newUser("sfw2bp_");
        String secret = newSecret();
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        admin.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + secret + "'");
        for (String table : java.util.List.of("sf_w2b_projection", "sf_w2b_partition_fence")) {
            admin.execute("GRANT SELECT, INSERT, UPDATE ON `" + database + "`." + table + " TO '" + user + "'@'%'");
        }
        return new ProfileGuardedDataSource(new DriverManagerDataSource(jdbcUrl, user, secret), context);
    }

    DataSource createJournalWriterDataSource() {
        requireStore(Store.JOURNAL);
        String user = newUser("sfw2jw_");
        String secret = newSecret();
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        admin.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + secret + "'");
        for (String table : java.util.List.of("sf_journal_head", "sf_journal_event", "sf_journal_command",
                "sf_journal_action", "sf_journal_lease")) {
            admin.execute("GRANT SELECT, INSERT, UPDATE ON `" + database + "`." + table + " TO '" + user + "'@'%'");
        }
        return new ProfileGuardedDataSource(new DriverManagerDataSource(jdbcUrl, user, secret), context);
    }

    DataSource createWitnessAttestorDataSource() {
        requireStore(Store.WITNESS);
        String user = newUser("sfw2bt_");
        String secret = newSecret();
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        admin.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + secret + "'");
        admin.execute("GRANT SELECT, INSERT ON `" + database + "`.sf_w2b_checkpoint_log TO '" + user + "'@'%'");
        admin.execute("GRANT SELECT, INSERT, UPDATE ON `" + database + "`.sf_w2b_latest_checkpoint TO '" + user + "'@'%'");
        return new ProfileGuardedDataSource(new DriverManagerDataSource(jdbcUrl, user, secret), context);
    }

    DataSource createWitnessReaderDataSource() {
        requireStore(Store.WITNESS);
        String user = newUser("sfw2br_");
        String secret = newSecret();
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        admin.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + secret + "'");
        admin.execute("GRANT SELECT ON `" + database + "`.sf_w2b_checkpoint_log TO '" + user + "'@'%'");
        admin.execute("GRANT SELECT ON `" + database + "`.sf_w2b_latest_checkpoint TO '" + user + "'@'%'");
        return new ProfileGuardedDataSource(new DriverManagerDataSource(jdbcUrl, user, secret), context);
    }

    DataSource createUnprivilegedJournalDataSourceOnWitness() {
        requireStore(Store.WITNESS);
        return createUnprivilegedWitnessPrincipal("sfw2bj_");
    }

    DataSource createUnprivilegedApplicationDataSourceOnWitness() {
        requireStore(Store.WITNESS);
        return createUnprivilegedWitnessPrincipal("sfw2bp_");
    }

    private DataSource createUnprivilegedWitnessPrincipal(String prefix) {
        W2bTestEnvironment.requireTest(context);
        String user = newUser(prefix);
        String secret = newSecret();
        JdbcTemplate admin = new JdbcTemplate(adminDataSource());
        admin.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + secret + "'");
        return new ProfileGuardedDataSource(new DriverManagerDataSource(jdbcUrl, user, secret), context);
    }

    void requireAvailable() { W2bTestEnvironment.requireTest(context); }

    private void requireStore(Store expected) {
        W2bTestEnvironment.requireTest(context);
        if (store != expected) throw new SecurityException("異なるstore capabilityの利用です");
    }

    private static String newUser(String prefix) { return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16); }
    private static String newSecret() { return UUID.randomUUID().toString().replace("-", ""); }
}
