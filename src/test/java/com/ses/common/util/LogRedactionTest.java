package com.ses.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LogRedaction ユーティリティ単体テスト")
class LogRedactionTest {

    @Test
    @DisplayName("パスワードおよび秘密情報のマスキング")
    void パスワードと秘密情報を秘匿する() {
        assertEquals("db password=***", LogRedaction.redact("db password=secret"));
        assertEquals("database_password: ***", LogRedaction.redact("database_password: MySecretPassword123"));
        assertEquals("password=***", LogRedaction.redact("password=topsecret"));
        assertEquals("passwd: ***", LogRedaction.redact("passwd: secretpass"));
        assertEquals("pwd=***", LogRedaction.redact("pwd=short"));
        assertEquals("client_secret=***", LogRedaction.redact("client_secret=cs_live_123456789"));
        assertEquals("api_key: ***", LogRedaction.redact("api_key: ak_test_abcdefg"));
        assertEquals("secret_key=***", LogRedaction.redact("secret_key=sk_live_xyz"));
        assertEquals("private_key: ***", LogRedaction.redact("private_key: pk_sec_987654"));
        assertEquals("password=\"***\"", LogRedaction.redact("password=\"quotedSecret\""));
        assertEquals("password='***'", LogRedaction.redact("password='singleQuotedSecret'"));
    }

    @Test
    @DisplayName("Bearer Tokenおよび各種トークンのマスキング")
    void Bearerと各種トークンを秘匿する() {
        assertEquals("Bearer ***", LogRedaction.redact("Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0"));
        assertEquals("Bearer ***", LogRedaction.redact("Bearer test-token-12345_abc.def~ghi"));
        assertEquals("token=***", LogRedaction.redact("token=token-value-123"));
        assertEquals("access_token: ***", LogRedaction.redact("access_token: acc_tok_999"));
        assertEquals("refresh_token=***", LogRedaction.redact("refresh_token=ref_tok_888"));
    }

    @Test
    @DisplayName("メールアドレスのマスキング")
    void メールアドレスを秘匿する() {
        assertEquals("***@***", LogRedaction.redact("victim@example.com"));
        assertEquals("User ***@*** registered", LogRedaction.redact("User admin.user+test@company.co.jp registered"));
    }

    @Test
    @DisplayName("SQL文のマスキング")
    void SQL文を秘匿する() {
        assertEquals("Error: [REDACTED_SQL]",
                LogRedaction.redact("Error: SELECT id, username, password FROM sys_user WHERE username = 'admin'"));
        assertEquals("[REDACTED_SQL]",
                LogRedaction.redact("INSERT INTO t_digital_invoice (id, status) VALUES (1, 'QUEUED')"));
        assertEquals("[REDACTED_SQL]",
                LogRedaction.redact("UPDATE t_invoice SET status = 'PAID' WHERE id = 10"));
        assertEquals("[REDACTED_SQL]",
                LogRedaction.redact("DELETE FROM sys_user WHERE id = 99"));
    }

    @Test
    @DisplayName("JDBC接続文字列のマスキング")
    void JDBC接続文字列を秘匿する() {
        assertEquals("jdbc:***",
                LogRedaction.redact("jdbc:mysql://localhost:3306/ses_manager_db?user=root&password=secretPassword"));
    }

    @Test
    @DisplayName("複合メッセージのマスキング")
    void 複合メッセージを秘匿する() {
        String msg = "Connection error to jdbc:mysql://db.internal:3306/prod?user=app with db password=secretPass and Bearer token123 for contact victim@ses.co.jp while executing SELECT * FROM accounts";
        String redacted = LogRedaction.redact(msg);
        assertFalse(redacted.contains("secretPass"));
        assertFalse(redacted.contains("token123"));
        assertFalse(redacted.contains("victim@ses.co.jp"));
        assertFalse(redacted.contains("SELECT * FROM accounts"));
        assertFalse(redacted.contains("db.internal:3306"));
    }

    @Test
    @DisplayName("Basic認証・JSON camelCaseトークン・SQLバインド値を秘匿する")
    void Basic認証とJsonTokenとSqlBindingsを秘匿する() {
        String input = "Authorization: Basic dXNlcjpwYXNz accessToken=access-secret "
                + "{\"refreshToken\":\"refresh-secret\",\"password\":\"json-password\"} "
                + "SQL Parameters: [bind-secret] SQL Bindings: {email=user@example.com}";

        String redacted = LogRedaction.redact(input);

        assertFalse(redacted.contains("dXNlcjpwYXNz"));
        assertFalse(redacted.contains("access-secret"));
        assertFalse(redacted.contains("refresh-secret"));
        assertFalse(redacted.contains("json-password"));
        assertFalse(redacted.contains("bind-secret"));
        assertFalse(redacted.contains("user@example.com"));
    }

    @Test
    @DisplayName("超長入力と大量のSELECTを短時間で安全に処理する")
    void 超長Sqlの脱敏は時間内に完了する() {
        String repeatedSelect = "SELECT password FROM t WHERE token='select-secret';".repeat(150);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
            String result = LogRedaction.redact(repeatedSelect);
            assertFalse(result.contains("select-secret"));
            assertFalse(result.contains("password FROM t"));
        });
        assertEquals("機密情報を含むため詳細を省略しました", LogRedaction.redact("x".repeat(9000)));
    }

    @Test
    @DisplayName("循環するcause・suppressedと深いスタックを上限内で処理する")
    void 循環例外グラフを上限内で処理する() {
        RuntimeException first = new RuntimeException("first password=cycle-secret");
        RuntimeException second = new RuntimeException("second token=cycle-token");
        first.initCause(second);
        second.initCause(first);
        IntStream.range(0, 40).forEach(i -> first.addSuppressed(new RuntimeException("password=suppressed-secret-" + i)));
        first.setStackTrace(IntStream.range(0, 300)
                .mapToObj(i -> new StackTraceElement("Example", "method", "Example.java", i))
                .toArray(StackTraceElement[]::new));

        Throwable sanitized = LogRedaction.sanitizeThrowable(first);
        String summary = LogRedaction.safeThrowableSummary(first);

        assertTrue(sanitized.getStackTrace().length <= 128);
        assertTrue(sanitized.getSuppressed().length <= 32);
        assertTrue(summary.contains("circular=true"));
        assertFalse(summary.contains("cycle-secret"));
        assertFalse(sanitized.toString().contains("cycle-secret"));
    }

    @Test
    @DisplayName("maskEmail メソッドの既存動作")
    void メール局所マスクの既存動作を維持する() {
        assertEquals("te***@example.com", LogRedaction.maskEmail("test@example.com"));
        assertEquals("a***@example.com", LogRedaction.maskEmail("a@example.com"));
        assertEquals("ab***@example.com", LogRedaction.maskEmail("abcd@example.com"));
        assertEquals("***", LogRedaction.maskEmail("invalid-email"));
        assertEquals("***", LogRedaction.maskEmail(null));
    }

    @Test
    @DisplayName("sanitizeThrowable による例外メッセージおよびCauseチェーンの秘匿化")
    void 例外causeチェーンを秘匿する() {
        java.sql.SQLException sqlEx = new java.sql.SQLException("Syntax error in SQL: SELECT * FROM users WHERE password = 'mySecretPassword'", "42000");
        RuntimeException runtimeEx = new RuntimeException("Failed with token=secretToken and email=admin@test.com", sqlEx);

        Throwable sanitized = LogRedaction.sanitizeThrowable(runtimeEx);
        assertNotNull(sanitized);
        assertTrue(sanitized instanceof LogRedaction.SanitizedException);
        assertEquals("java.lang.RuntimeException", ((LogRedaction.SanitizedException) sanitized).getOriginalClassName());

        String msg = sanitized.getMessage();
        assertFalse(msg.contains("secretToken"));
        assertFalse(msg.contains("admin@test.com"));
        assertTrue(msg.contains("token=***"));
        assertTrue(msg.contains("***@***"));

        Throwable cause = sanitized.getCause();
        assertNotNull(cause);
        assertTrue(cause instanceof LogRedaction.SanitizedException);
        assertEquals("java.sql.SQLException", ((LogRedaction.SanitizedException) cause).getOriginalClassName());

        String causeMsg = cause.getMessage();
        assertFalse(causeMsg.contains("mySecretPassword"));
        assertFalse(causeMsg.contains("SELECT * FROM users"));
        assertTrue(causeMsg.contains("[REDACTED_SQL]"));

        // スタックトレースが保持されていること
        assertNotNull(sanitized.getStackTrace());
        assertTrue(sanitized.getStackTrace().length > 0);
    }

    @Test
    @DisplayName("SQL脱敏判定: 通常の英単語(update/select/delete)が過度に脱敏されず安全な診断フィールドが残る")
    void 通常の業務メッセージは過度に脱敏されず診断情報が保持される() {
        assertEquals("Provider update failed requestId=req-1",
                LogRedaction.redact("Provider update failed requestId=req-1"));
        assertEquals("Service select completed recordCount=42 correlationId=corr-99",
                LogRedaction.redact("Service select completed recordCount=42 correlationId=corr-99"));
        assertEquals("User delete failed reason=not_found userId=u-123",
                LogRedaction.redact("User delete failed reason=not_found userId=u-123"));

        // 実SQL文が含まれる場合は安全に脱敏され、後続の診断識別子が残る
        assertEquals("SQL error: [REDACTED_SQL]| requestId=req-1",
                LogRedaction.redact("SQL error: UPDATE t_digital_invoice SET status = 'SENT' WHERE id = 1 | requestId=req-1"));
        assertEquals("SQL error: [REDACTED_SQL]",
                LogRedaction.redact("SQL error: UPDATE t_digital_invoice SET status = 'SENT' WHERE id = 1"));
        assertEquals("[REDACTED_SQL]",
                LogRedaction.redact("SELECT id, email, password FROM sys_user WHERE username = 'admin'"));
        assertEquals("[REDACTED_SQL]",
                LogRedaction.redact("INSERT INTO t_digital_invoice (id, status) VALUES (1, 'QUEUED')"));
        assertEquals("[REDACTED_SQL]",
                LogRedaction.redact("DELETE FROM sys_user WHERE id = 99"));
    }

    @Test
    @DisplayName("Throwableの各getterがAssertionErrorを投げても二次例外を出さず固定fallbackへ安全に変換する")
    void getterのAssertionErrorに対しても二次例外を出さず機密を秘匿する() {
        // 1. getMessage が AssertionError を投げる場合
        Throwable msgAdversary = new Throwable() {
            @Override
            public String getMessage() {
                throw new AssertionError("db password=msg-secret-leak");
            }
        };
        assertEquals("機密情報を含むため詳細を省略しました", LogRedaction.safeMessage(msgAdversary));
        Throwable sanitized1 = LogRedaction.sanitizeThrowable(msgAdversary);
        assertNotNull(sanitized1);
        assertFalse(sanitized1.getMessage().contains("msg-secret-leak"));
        String summary1 = LogRedaction.safeThrowableSummary(msgAdversary);
        assertFalse(summary1.contains("msg-secret-leak"));

        // 2. getCause が AssertionError を投げる場合
        Throwable causeAdversary = new Throwable("safe message") {
            @Override
            public Throwable getCause() {
                throw new AssertionError("Bearer cause-token-secret");
            }
        };
        assertNull(LogRedaction.safeCause(causeAdversary));
        Throwable sanitized2 = LogRedaction.sanitizeThrowable(causeAdversary);
        assertNotNull(sanitized2);
        assertNull(sanitized2.getCause());
        String summary2 = LogRedaction.safeThrowableSummary(causeAdversary);
        assertFalse(summary2.contains("cause-token-secret"));

        // 3. getSuppressed / suppressed 例外の getMessage が AssertionError を投げる場合
        assertEquals(0, LogRedaction.safeSuppressed(null).length);
        Throwable suppressedAdversaryHolder = new Throwable("safe holder");
        suppressedAdversaryHolder.addSuppressed(new Throwable() {
            @Override
            public String getMessage() {
                throw new AssertionError("victim-suppressed@example.com");
            }
        });
        assertEquals(1, LogRedaction.safeSuppressed(suppressedAdversaryHolder).length);
        Throwable sanitized3 = LogRedaction.sanitizeThrowable(suppressedAdversaryHolder);
        assertNotNull(sanitized3);
        String summary3 = LogRedaction.safeThrowableSummary(suppressedAdversaryHolder);
        assertFalse(summary3.contains("victim-suppressed@example.com"));

        // 4. getStackTrace が AssertionError を投げる場合
        Throwable stackAdversary = new Throwable("safe message") {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new AssertionError("SELECT password FROM secret_table");
            }
        };
        assertEquals(0, LogRedaction.safeStackTrace(stackAdversary).length);
        Throwable sanitized4 = LogRedaction.sanitizeThrowable(stackAdversary);
        assertNotNull(sanitized4);
        assertEquals(0, sanitized4.getStackTrace().length);
        String summary4 = LogRedaction.safeThrowableSummary(stackAdversary);
        assertFalse(summary4.contains("secret_table"));

        // 5. 非finalの全getterが不正なAssertionErrorを投げる複合対抗例外
        Throwable fullAdversary = new Throwable() {
            @Override
            public String getMessage() {
                throw new AssertionError("db password=all-secret-1");
            }
            @Override
            public Throwable getCause() {
                throw new AssertionError("Authorization: Bearer all-secret-2");
            }
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new AssertionError("DELETE FROM accounts WHERE secret = 'leak'");
            }
        };
        fullAdversary.addSuppressed(new Throwable() {
            @Override
            public String getMessage() {
                throw new AssertionError("leak@corp.internal");
            }
        });
        String fullSummary = LogRedaction.safeThrowableSummary(fullAdversary);
        assertFalse(fullSummary.contains("all-secret"));
        assertFalse(fullSummary.contains("corp.internal"));
        assertFalse(fullSummary.contains("accounts"));

        Throwable fullSanitized = LogRedaction.sanitizeThrowable(fullAdversary);
        assertNotNull(fullSanitized);
        assertFalse(fullSanitized.toString().contains("all-secret"));
        assertFalse(fullSanitized.toString().contains("corp.internal"));
    }

    @Test
    @DisplayName("JVM致命的エラー(VirtualMachineError)は方針に従って再送出される")
    void 致命的エラーは再送出する() {
        Throwable oomMsg = new Throwable() {
            @Override
            public String getMessage() {
                throw new OutOfMemoryError("OOM message");
            }
        };
        assertThrows(OutOfMemoryError.class, () -> LogRedaction.safeMessage(oomMsg));
        assertThrows(OutOfMemoryError.class, () -> LogRedaction.sanitizeThrowable(oomMsg));

        Throwable oomCause = new Throwable() {
            @Override
            public Throwable getCause() {
                throw new OutOfMemoryError("OOM cause");
            }
        };
        assertThrows(OutOfMemoryError.class, () -> LogRedaction.safeCause(oomCause));
        assertThrows(OutOfMemoryError.class, () -> LogRedaction.safeThrowableSummary(oomCause));

        Throwable oomStack = new Throwable() {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new OutOfMemoryError("OOM stack");
            }
        };
        assertThrows(OutOfMemoryError.class, () -> LogRedaction.safeStackTrace(oomStack));
        assertThrows(OutOfMemoryError.class, () -> LogRedaction.safeThrowableSummary(oomStack));
    }
}
