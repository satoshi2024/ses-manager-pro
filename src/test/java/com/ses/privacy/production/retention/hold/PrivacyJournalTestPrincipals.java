package com.ses.privacy.production.retention.hold;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 実MySQLのtestが、journal-witness-grant-design.md §3.2 の GRANT 草案どおりの principal を Testcontainers の
 * Journal に作るためのhelper（role を使わず user に直接 GRANT する）。container と user は test が生成したものだけを使う。
 * 別 package の test からも使う。
 */
public final class PrivacyJournalTestPrincipals {

    public static final String SCHEMA = "privacy_journal";
    public static final List<String> APPEND_ONLY_TABLES = List.of("pj_event", "pj_command", "pj_action", "pj_lease");
    public static final String HISTORY_TABLE = "flyway_schema_history_privacy_journal";

    private PrivacyJournalTestPrincipals() {
    }

    /** test 用の一意な user 名（MySQL の上限32文字以内）。 */
    public static String uniqueName(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static String randomPassword() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static void createUser(JdbcTemplate root, String user, String password) {
        root.execute("CREATE USER '" + user + "'@'%' IDENTIFIED BY '" + password + "'");
    }

    /** appender の許可集合（{@code pj_head} に SELECT/INSERT/UPDATE、他の4表に SELECT/INSERT、履歴表に SELECT）。 */
    public static void grantAppender(JdbcTemplate root, String grantee) {
        root.execute("GRANT SELECT, INSERT, UPDATE ON " + SCHEMA + ".pj_head TO " + grantee);
        for (String table : APPEND_ONLY_TABLES) {
            root.execute("GRANT SELECT, INSERT ON " + SCHEMA + "." + table + " TO " + grantee);
        }
        root.execute("GRANT SELECT ON " + SCHEMA + "." + HISTORY_TABLE + " TO " + grantee);
    }

    /** reader の許可集合（schema 単位の SELECT だけ）。 */
    public static void grantReader(JdbcTemplate root, String grantee) {
        root.execute("GRANT SELECT ON " + SCHEMA + ".* TO " + grantee);
    }

    public static String grantee(String user) {
        return "'" + user + "'@'%'";
    }

    public static void createAppender(JdbcTemplate root, String user, String password) {
        createUser(root, user, password);
        grantAppender(root, grantee(user));
    }

    public static void createReader(JdbcTemplate root, String user, String password) {
        createUser(root, user, password);
        grantReader(root, grantee(user));
    }

    public static void dropUser(JdbcTemplate root, String user) {
        root.execute("DROP USER IF EXISTS '" + user + "'@'%'");
    }
}
