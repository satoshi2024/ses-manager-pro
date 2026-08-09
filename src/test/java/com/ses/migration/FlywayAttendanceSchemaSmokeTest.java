package com.ses.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T068のMySQL smoke。V1 fresh DBとV83 legacy追加shapeを同じDDL契約で検証する。 */
@Testcontainers(disabledWithoutDocker = true)
class FlywayAttendanceSchemaSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_attendance_v83")
            .withUsername("ses")
            .withPassword("ses");

    @Test
    void V83の勤怠休暇協定shapeと境界制約がMySQLで成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("83")
                .load()
                .migrate();

        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String table : new String[]{
                    "m_work_calendar", "m_work_calendar_day", "t_employee_attendance",
                    "t_attendance_month", "t_leave_request", "m_overtime_agreement",
                    "t_overtime_followup"}) {
                assertTableExists(statement, table);
            }
            assertColumnExists(statement, "t_engineer", "overtime_exempt_flag");
            assertIndexExists(statement, "t_employee_attendance", "uk_employee_attendance_source");
            assertIndexExists(statement, "t_attendance_month", "uk_attendance_month_engineer");
            assertIndexExists(statement, "m_overtime_agreement", "uk_overtime_agreement_period");
            assertCheckExists(statement, "m_overtime_agreement", "chk_overtime_agreement_month_start");
            assertEquals(9, queryInt(statement,
                    "SELECT COUNT(*) FROM m_system_config WHERE config_key LIKE 'overtime.%'"));

            statement.executeUpdate("INSERT INTO t_engineer "
                    + "(full_name, employment_type, status) VALUES ('T068-mysql-engineer', '正社員', 'Bench')");
            long engineerId = queryLong(statement,
                    "SELECT id FROM t_engineer WHERE full_name='T068-mysql-engineer'");
            statement.executeUpdate("INSERT INTO t_employee_attendance "
                    + "(engineer_id, work_date, source, source_external_id) VALUES (" + engineerId
                    + ", '2026-08-03', 'freee', 'T068-mysql-external')");
            boolean duplicateRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_employee_attendance "
                        + "(engineer_id, work_date, source, source_external_id) VALUES (" + engineerId
                        + ", '2026-08-04', 'freee', 'T068-mysql-external')");
            } catch (SQLException expected) {
                duplicateRejected = true;
            }
            assertTrue(duplicateRejected, "source/external IDの重複を拒否するはず");

            statement.executeUpdate("INSERT INTO m_work_calendar "
                    + "(legal_entity_id, engineer_id, name, valid_from) VALUES (7001, " + engineerId
                    + ", 'T068-calendar', '2026-08-01')");
            long calendarId = queryLong(statement,
                    "SELECT id FROM m_work_calendar WHERE name='T068-calendar'");
            statement.executeUpdate("INSERT INTO m_work_calendar_day "
                    + "(calendar_id, calendar_date, day_type, scheduled_minutes) VALUES (" + calendarId
                    + ", '2026-08-08', '法定休日', NULL), (" + calendarId
                    + ", '2026-08-09', '所定日', 0)");
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_work_calendar_day WHERE calendar_id=" + calendarId
                            + " AND scheduled_minutes IS NULL"));
            assertEquals(1, queryInt(statement,
                    "SELECT COUNT(*) FROM m_work_calendar_day WHERE calendar_id=" + calendarId
                            + " AND scheduled_minutes=0"));

            boolean invalidMonthRejected = false;
            try {
                statement.executeUpdate("INSERT INTO m_overtime_agreement "
                        + "(legal_entity_id, valid_from) VALUES (7001, '2026-08-02')");
            } catch (SQLException expected) {
                invalidMonthRejected = true;
            }
            assertTrue(invalidMonthRejected, "協定のvalid_from月初制約が必要");
        }
    }

    /** R2-P1-02方式Aの追補V91（t_employee_attendance_break）をfresh/legacy共通shapeで検証する。 */
    @Test
    void V91の休憩区間shapeと境界制約がMySQLで成立する() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .target("83")
                .load()
                .migrate();

        // V91追補DDLを実MySQLへ適用してshapeを検証する。V84（S10/dispatch）は同laneの
        // FlywayDispatchComplianceSchemaSmokeTestが検証するため、本testの検証対象でない。
        // V91のDDLは単純なCREATE TABLEのみで、Flyway適用順も番号順（V84の後）に定まる。
        String v91 = new String(getClass().getClassLoader()
                .getResourceAsStream("db/migration/V91__attendance_break_intervals.sql").readAllBytes(),
                StandardCharsets.UTF_8);
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            for (String ddl : v91.split(";")) {
                if (!ddl.trim().isEmpty()) {
                    statement.execute(ddl);
                }
            }
            assertTableExists(statement, "t_employee_attendance_break");
            assertIndexExists(statement, "t_employee_attendance_break", "uk_employee_attendance_break");
            assertCheckExists(statement, "t_employee_attendance_break", "chk_employee_attendance_break_offset");

            statement.executeUpdate("INSERT INTO t_engineer "
                    + "(full_name, employment_type, status) VALUES ('T091-mysql-engineer', '正社員', 'Bench')");
            long engineerId = queryLong(statement,
                    "SELECT id FROM t_engineer WHERE full_name='T091-mysql-engineer'");
            statement.executeUpdate("INSERT INTO t_employee_attendance "
                    + "(engineer_id, work_date, source) VALUES (" + engineerId + ", '2026-08-03', 'manual')");
            long attendanceId = queryLong(statement,
                    "SELECT id FROM t_employee_attendance WHERE engineer_id=" + engineerId
                            + " AND work_date='2026-08-03'");
            statement.executeUpdate("INSERT INTO t_employee_attendance_break "
                    + "(attendance_id, sequence_no, start_offset_minutes, end_offset_minutes) "
                    + "VALUES (" + attendanceId + ", 1, 180, 240), (" + attendanceId + ", 2, 360, 375)");
            assertEquals(2, queryInt(statement,
                    "SELECT COUNT(*) FROM t_employee_attendance_break WHERE attendance_id=" + attendanceId));

            boolean reversedRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_employee_attendance_break "
                        + "(attendance_id, sequence_no, start_offset_minutes, end_offset_minutes) "
                        + "VALUES (" + attendanceId + ", 3, 120, 60)");
            } catch (SQLException expected) {
                reversedRejected = true;
            }
            assertTrue(reversedRejected, "開始≧終了の休憩区間を拒否するはず");

            boolean duplicateRejected = false;
            try {
                statement.executeUpdate("INSERT INTO t_employee_attendance_break "
                        + "(attendance_id, sequence_no, start_offset_minutes, end_offset_minutes) "
                        + "VALUES (" + attendanceId + ", 1, 60, 90)");
            } catch (SQLException expected) {
                duplicateRejected = true;
            }
            assertTrue(duplicateRejected, "同一attendance内のsequence重複を拒否するはず");
        }
    }

    private void assertTableExists(Statement statement, String table) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "'") == 1,
                table + "が存在するはず");
    }

    private void assertColumnExists(Statement statement, String table, String column) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND column_name='" + column + "'") == 1,
                table + "." + column + "が存在するはず");
    }

    private void assertIndexExists(Statement statement, String table, String index) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='" + table + "' AND index_name='" + index + "'") > 0,
                table + "." + index + "が存在するはず");
    }

    private void assertCheckExists(Statement statement, String table, String constraint) throws Exception {
        assertTrue(queryInt(statement, "SELECT COUNT(*) FROM information_schema.table_constraints "
                + "WHERE constraint_schema=DATABASE() AND table_name='" + table
                + "' AND constraint_name='" + constraint + "' AND constraint_type='CHECK'") == 1,
                table + "." + constraint + "がCHECK制約として存在するはず");
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private long queryLong(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }
}
