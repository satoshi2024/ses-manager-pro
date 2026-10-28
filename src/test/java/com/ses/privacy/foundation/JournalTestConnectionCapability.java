package com.ses.privacy.foundation;

import org.testcontainers.containers.MySQLContainer;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
        return new W3aSyntheticPreviewStore(this);
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

    static final class W3aSyntheticPreviewStore implements SyntheticPreviewStore {
    private final DataSource dataSource;
    private final String ownedContainerId;
    private final String ownedDatabase;
    private boolean initialized;
    private volatile boolean loseNextCommitAck;
    enum ReadbackFault { NONE, MISSING, WRONG_HASH, UNREADABLE }
    private volatile ReadbackFault nextReadbackFault=ReadbackFault.NONE;
    private volatile boolean committedForFault;
    private volatile Runnable afterCommitHook=()->{};

    private W3aSyntheticPreviewStore(JournalTestConnectionCapability owner){
        java.util.Objects.requireNonNull(owner);
        this.dataSource=owner.adminDataSource();
        this.ownedContainerId=owner.containerId();this.ownedDatabase=owner.database();
    }
    synchronized void initializeSchema(){
        if(initialized)throw new SecurityException("W3a schema capabilityは一度だけ実行できます");
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement("SELECT DATABASE()")){
            try(ResultSet r=p.executeQuery()){
                if(!r.next()||!ownedDatabase.equals(r.getString(1)))throw new SecurityException("W3a databaseがharness所有databaseと一致しません");
            }
        }catch(SQLException e){throw new IllegalStateException("W3a database bindingを確認できません",e);}
        ClassPathResource schema=new ClassPathResource("privacy-journal/w3a-test-schema.sql");
        final String schemaHash;
        try(var in=schema.getInputStream()){
            schemaHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
        }catch(java.io.IOException|java.security.NoSuchAlgorithmException e){throw new IllegalStateException("W3a schema manifestを読めません",e);}
        new ResourceDatabasePopulator(schema).execute(dataSource);
        initialized=true;
        System.out.printf("W3A_STORE containerId=%s database=%s schemaHash=%s schema=privacy-journal/w3a-test-schema.sql%n",
                ownedContainerId,ownedDatabase,schemaHash);
    }
    void seedScopedCase(String tenant,String caseRef,long revision){
        requireInitialized();
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(
                "INSERT INTO sf_w3a_case(tenant_id,case_ref,revision,state) VALUES(?,?,?,'SCOPED')")){
            p.setString(1,tenant);p.setString(2,caseRef);p.setLong(3,revision);p.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException("synthetic case fixtureを作れません",e);}
    }
    void loseNextCommitAckAfterCommit(){loseNextCommitAck=true;}
    void faultNextReadback(ReadbackFault fault){nextReadbackFault=fault;committedForFault=false;}
    void afterNextCommit(Runnable hook){afterCommitHook=java.util.Objects.requireNonNull(hook);}
    @Override public PreviewSnapshot find(String tenant,String request,String key){
        requireInitialized();
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(
                "SELECT tenant_id,request_id,idempotency_key,request_hash,case_ref,base_case_revision,case_revision,identity_hash,witness_hash,snapshot_hash,witness_json,items_json,observed_at,observed_to FROM sf_w3a_preview WHERE tenant_id=? AND request_id=? AND idempotency_key=?")){
            p.setString(1,tenant);p.setString(2,request);p.setString(3,key);
            try(ResultSet r=p.executeQuery()){
                if(!r.next())return null;
                PreviewSnapshot snapshot=new PreviewSnapshot(r.getString(1),r.getString(2),r.getString(3),r.getString(4),
                    r.getString(5),r.getLong(6),r.getLong(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12),
                    r.getTimestamp(13).toInstant(),r.getTimestamp(14).toInstant());
                if(committedForFault&&nextReadbackFault!=ReadbackFault.NONE){
                    ReadbackFault fault=nextReadbackFault;nextReadbackFault=ReadbackFault.NONE;committedForFault=false;
                    if(fault==ReadbackFault.MISSING)return null;
                    if(fault==ReadbackFault.UNREADABLE)throw new IllegalStateException("test readback unavailable");
                    return new PreviewSnapshot(snapshot.tenantId(),snapshot.requestId(),snapshot.idempotencyKey(),snapshot.requestHash(),
                            snapshot.caseRef(),snapshot.baseCaseRevision(),snapshot.caseRevision(),snapshot.identityHash(),snapshot.witnessHash(),
                            "0".repeat(64),snapshot.witnessJson(),snapshot.itemsJson(),snapshot.observedAt(),snapshot.observedTo());
                }
                return snapshot;
            }
        }catch(SQLException e){throw new IllegalStateException("preview recordを独立readできません",e);}
    }
    @Override public CaseHead readCaseHead(String tenant,String caseRef){
        requireInitialized();
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(
                "SELECT revision,state FROM sf_w3a_case WHERE tenant_id=? AND case_ref=?")){
            p.setString(1,tenant);p.setString(2,caseRef);
            try(ResultSet r=p.executeQuery()){return r.next()?new CaseHead(r.getLong(1),r.getString(2)):null;}
        }catch(SQLException e){throw new IllegalStateException("case revisionを独立readできません",e);}
    }
    @Override public PersistOutcome persist(PreviewDraft d){
        requireInitialized();
        Connection c=null; boolean commitStarted=false;
        try{
            c=dataSource.getConnection();
            c.setAutoCommit(false);
            try(PreparedStatement p=c.prepareStatement(
                    "UPDATE sf_w3a_case SET revision=?,state='PREVIEWED' WHERE tenant_id=? AND case_ref=? AND revision=? AND state='SCOPED'")){
                p.setLong(1,d.caseRevision());p.setString(2,d.tenantId());p.setString(3,d.caseRef());p.setLong(4,d.baseCaseRevision());
                if(p.executeUpdate()!=1){try{c.rollback();return PersistOutcome.CAS_FAILED;}catch(SQLException e){return PersistOutcome.ACK_UNKNOWN;}}
            }
            try(PreparedStatement p=c.prepareStatement(
                    "INSERT INTO sf_w3a_preview(tenant_id,request_id,idempotency_key,request_hash,case_ref,base_case_revision,case_revision,identity_hash,witness_hash,snapshot_hash,witness_json,items_json,observed_at,observed_to) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")){
                p.setString(1,d.tenantId());p.setString(2,d.requestId());p.setString(3,d.idempotencyKey());p.setString(4,d.requestHash());
                p.setString(5,d.caseRef());p.setLong(6,d.baseCaseRevision());p.setLong(7,d.caseRevision());p.setString(8,d.identityHash());
                p.setString(9,d.witnessHash());p.setString(10,d.snapshotHash());p.setString(11,d.witnessJson());p.setString(12,d.itemsJson());
                p.setTimestamp(13,java.sql.Timestamp.from(d.observedAt()));p.setTimestamp(14,java.sql.Timestamp.from(d.observedTo()));
                p.executeUpdate();
            }catch(SQLException e){
                try{c.rollback();}catch(SQLException rollbackUnknown){return PersistOutcome.ACK_UNKNOWN;}
                if(e.getErrorCode()==1062)return PersistOutcome.DUPLICATE;
                return PersistOutcome.FAILED;
            }
            try{
                commitStarted=true;
                c.commit();
                Runnable hook=afterCommitHook;afterCommitHook=()->{};hook.run();
                if(nextReadbackFault!=ReadbackFault.NONE)committedForFault=true;
                if(loseNextCommitAck){loseNextCommitAck=false;return PersistOutcome.ACK_UNKNOWN;}
                return PersistOutcome.COMMITTED;
            }catch(SQLException commitAckUnknown){return PersistOutcome.ACK_UNKNOWN;}
        }catch(SQLException e){
            if(commitStarted)return PersistOutcome.ACK_UNKNOWN;
            return PersistOutcome.FAILED;
        }finally{if(c!=null)try{c.close();}catch(SQLException ignored){}}
    }
    private void requireInitialized(){if(!initialized)throw new SecurityException("W3a test schemaが初期化されていません");}
}

}
