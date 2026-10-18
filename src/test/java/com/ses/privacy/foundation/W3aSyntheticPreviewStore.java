package com.ses.privacy.foundation;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;

/** 一時container capabilityから一度だけ生成されるW3a専用のtest persistence。 */
final class W3aSyntheticPreviewStore implements SyntheticPreviewStore {
    private final DataSource dataSource;
    private final String ownedContainerId;
    private final String ownedDatabase;
    private boolean initialized;
    private volatile boolean loseNextCommitAck;

    private W3aSyntheticPreviewStore(DataSource dataSource,String containerId,String database){
        this.dataSource=java.util.Objects.requireNonNull(dataSource);
        if(containerId==null||containerId.isBlank()||database==null||!database.matches("sfj_[a-f0-9]{32}"))
            throw new SecurityException("一時MySQL container/database bindingが必要です");
        this.ownedContainerId=containerId;this.ownedDatabase=database;
    }
    static W3aSyntheticPreviewStore fromOwnedContainer(DataSource dataSource,String containerId,String database){
        return new W3aSyntheticPreviewStore(dataSource,containerId,database);
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
    @Override public PreviewSnapshot find(String tenant,String request,String key){
        requireInitialized();
        try(Connection c=dataSource.getConnection();PreparedStatement p=c.prepareStatement(
                "SELECT tenant_id,request_id,idempotency_key,request_hash,case_ref,base_case_revision,case_revision,identity_hash,witness_hash,snapshot_hash,witness_json,items_json,observed_at,observed_to FROM sf_w3a_preview WHERE tenant_id=? AND request_id=? AND idempotency_key=?")){
            p.setString(1,tenant);p.setString(2,request);p.setString(3,key);
            try(ResultSet r=p.executeQuery()){return r.next()?new PreviewSnapshot(r.getString(1),r.getString(2),r.getString(3),r.getString(4),
                    r.getString(5),r.getLong(6),r.getLong(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12),
                    r.getTimestamp(13).toInstant(),r.getTimestamp(14).toInstant()):null;}
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
