package com.ses.config;

import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Component
public class LegacyDatabaseFlywayCallback implements Callback {

    private static final Logger log = LoggerFactory.getLogger(LegacyDatabaseFlywayCallback.class);

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.BEFORE_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public String getCallbackName() {
        return "LegacyDatabaseFlywayCallback";
    }

    @Override
    public void handle(Event event, Context context) {
        if (event == Event.BEFORE_MIGRATE) {
            try {
                Connection connection = context.getConnection();
                repairLegacyDatabase(connection);
            } catch (Exception e) {
                log.error("Failed to execute legacy database repair callback", e);
                throw new RuntimeException("Flyway beforeMigrate callback failed", e);
            }
        }
    }

    private void repairLegacyDatabase(Connection conn) throws Exception {
        String dbName = conn.getCatalog();
        if (dbName == null) {
            // MySQL fallback
            try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT DATABASE()")) {
                if (rs.next()) {
                    dbName = rs.getString(1);
                }
            }
        }

        if (dbName == null) {
            log.warn("Could not determine database name, skipping legacy callback");
            return;
        }

        // 1. Check if t_bp_payment exists
        boolean hasBpPayment = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.tables WHERE table_schema = '" + dbName + "' AND table_name = 't_bp_payment'")) {
            hasBpPayment = rs.next();
        }

        if (!hasBpPayment) {
            log.info("t_bp_payment does not exist, skipping legacy repair");
            return;
        }



        // 3. Compensate missing columns
        addColumnIfNotExists(conn, dbName, "t_bp_payment", "layer_order", "INT NOT NULL DEFAULT 1 COMMENT '階層番号(1=技術者に最も近い一次請)'");
        addColumnIfNotExists(conn, dbName, "t_bp_payment", "payee_company_name", "VARCHAR(200) COMMENT '支払先協力会社名'");
        addColumnIfNotExists(conn, dbName, "t_bp_payment", "parent_payment_id", "BIGINT COMMENT '上位階層への自己参照(同一work_record_id内)'");
        addColumnIfNotExists(conn, dbName, "t_bp_payment", "deleted_flag", "TINYINT NOT NULL DEFAULT 0");

        // 4. Create fk_bp_payment_parent
        if (!constraintExists(conn, dbName, "t_bp_payment", "fk_bp_payment_parent")) {
            log.info("Creating foreign key fk_bp_payment_parent");
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE t_bp_payment ADD CONSTRAINT fk_bp_payment_parent FOREIGN KEY (parent_payment_id) REFERENCES t_bp_payment(id)");
            }
        }

        // 5. Check if this DB should have V10 index state or pre-V10 index state.
        boolean shouldHaveV10Indexes = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM flyway_schema_history WHERE version = '10' AND success = 1 AND description = 'fix bp payment unique key'")) {
            if (rs.next()) {
                shouldHaveV10Indexes = true;
            }
        } catch (Exception e) {}

        if (shouldHaveV10Indexes) {
            // State: idx_bp_payment_work_record should exist, uk_work_record_layer should NOT exist.
            if (!indexExists(conn, dbName, "t_bp_payment", "idx_bp_payment_work_record")) {
                log.info("Creating idx_bp_payment_work_record BEFORE dropping uk_work_record_layer to preserve FK");
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE INDEX idx_bp_payment_work_record ON t_bp_payment(work_record_id)");
                }
            }
            if (indexExists(conn, dbName, "t_bp_payment", "uk_work_record_layer")) {
                log.info("Dropping uk_work_record_layer");
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE t_bp_payment DROP INDEX uk_work_record_layer");
                }
            }
        } else {
            // State: uk_work_record_layer SHOULD exist.
            if (!indexExists(conn, dbName, "t_bp_payment", "uk_work_record_layer")) {
                log.info("Creating unique index uk_work_record_layer");
                try (Statement st = conn.createStatement()) {
                    st.execute("ALTER TABLE t_bp_payment ADD UNIQUE KEY uk_work_record_layer (work_record_id, layer_order)");
                }
            }
        }

        // 6. Drop old non-unique or single-column unique indexes on work_record_id
        List<String> indexesToDrop = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT s.index_name " +
                     "FROM information_schema.statistics s " +
                     "WHERE s.table_schema = '" + dbName + "' " +
                     "  AND s.table_name = 't_bp_payment' " +
                     "  AND s.index_name != 'PRIMARY' " +
                     "  AND s.index_name != 'uk_work_record_layer' " +
                     "  AND s.index_name != 'idx_bp_payment_work_record' " +
                     "GROUP BY s.index_name " +
                     "HAVING COUNT(*) = 1 AND MAX(s.column_name) = 'work_record_id'")) {
            while (rs.next()) {
                indexesToDrop.add(rs.getString(1));
            }
        }

        for (String idx : indexesToDrop) {
            log.info("Dropping legacy index: {}", idx);
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE t_bp_payment DROP INDEX `" + idx + "`");
            }
        }
        
        log.info("Legacy DB pre-migration compensation completed.");
    }

    private void addColumnIfNotExists(Connection conn, String dbName, String table, String column, String definition) throws Exception {
        boolean exists = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.columns WHERE table_schema = '" + dbName + "' AND table_name = '" + table + "' AND column_name = '" + column + "'")) {
            exists = rs.next();
        }
        if (!exists) {
            log.info("Adding column {} to {}", column, table);
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    }

    private boolean indexExists(Connection conn, String dbName, String table, String indexName) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.statistics WHERE table_schema = '" + dbName + "' AND table_name = '" + table + "' AND index_name = '" + indexName + "'")) {
            return rs.next();
        }
    }

    private boolean constraintExists(Connection conn, String dbName, String table, String constraintName) throws Exception {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM information_schema.table_constraints WHERE table_schema = '" + dbName + "' AND table_name = '" + table + "' AND constraint_name = '" + constraintName + "'")) {
            return rs.next();
        }
    }
}
