package com.ses.backup.quiesce;

import com.ses.config.SchedulingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.asm.Type;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RB-QUIESCE BWQ-004（design.md §6.5.4）: main の code が業務の接続を backup の書込み静止の包み
 * （{@link BackupQuiesceAwareDataSource}）の外で得ないことを bytecode の走査で固定する。
 *
 * <p>走査する呼出し: {@code DriverManager.getConnection}・{@code Driver.connect}、{@code HikariDataSource}・
 * {@code DriverManagerDataSource}・{@code SimpleDriverDataSource}・{@code SingleConnectionDataSource} の生成（method 参照を含む）、
 * {@code DataSourceBuilder}、{@code unwrap(HikariDataSource.class)}。既知の例外の一覧（理由つき）と突き合わせ、一覧に無い呼出しが
 * 増えたら失敗する。
 */
class BusinessConnectionBypassGuardTest {

    private static final Map<String, String> KNOWN = Map.of(
            "com.ses.backup.quiesce.BackupQuiesceControlConnectionPool:HikariDataSource.<init>",
            "信号表だけに使う制御用・heartbeat 用の pool（静止中も書く。業務の表へは書かない。BackupQuiesceControlPoolUsageTest）",
            "com.ses.privacy.production.retention.hold.PrivacyJournalConnections:HikariDataSource.<init>",
            "NF07 の Journal（application DB と別の MySQL instance。application DB の restore の対象外。design.md §6.3）",
            "com.ses.privacy.production.retention.hold.SanitizingJournalDriverDataSource:Driver.connect",
            "上の Journal の pool の接続（別 instance）",
            "com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier:SingleConnectionDataSource.<init>",
            "Journal の起動時検査が Journal の pool から借りた1接続を JdbcTemplate で使うための包み（別 instance。業務の接続ではない）",
            "com.ses.nf01.preflight.LifecycleAuthorizationPreflight:DriverManager.getConnection",
            "NF01 の配備前の読取り専用の CLI（app の context の外で、表を読むだけ）",
            "com.ses.featureenablement.nf05.IntegrationHubEnablementBootResolver:DriverManager.getConnection",
            "NF05 の起動の前の読取り（feature-enablement-console design §9.1・FE-D02。spec review P3-14 の条件）。"
                    + "EnvironmentPostProcessor で context の refresh の前（DataSource の bean も静止の包みもまだ無く、静止の"
                    + "対象の前）に、pool を作らず接続を1本だけ開き、setReadOnly(true) で SELECT だけを行い書かない。接続・socket・"
                    + "照会の timeout は5秒で、終われば閉じる");

    private static final Set<String> DATA_SOURCE_TYPES = Set.of("com/zaxxer/hikari/HikariDataSource",
            "org/springframework/jdbc/datasource/DriverManagerDataSource",
            "org/springframework/jdbc/datasource/SimpleDriverDataSource",
            "org/springframework/jdbc/datasource/SingleConnectionDataSource");

    @Test
    void mainのcodeは既知の例外の外で業務の接続を包みの外から得ない() throws IOException {
        URL location = SchedulingConfig.class.getProtectionDomain().getCodeSource().getLocation();
        String root = location.toString();
        String pattern = root.endsWith(".jar") ? "jar:" + root + "!/com/ses/**/*.class"
                : (root.endsWith("/") ? root : root + "/") + "com/ses/**/*.class";
        Set<String> found = new TreeSet<>();
        int scanned = 0;
        for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
            if (!resource.isReadable()) {
                continue;
            }
            scanned++;
            try (InputStream in = resource.getInputStream()) {
                scan(in, found);
            }
        }
        assertThat(scanned).as("main の class を走査した").isGreaterThan(500);
        Set<String> unknown = new TreeSet<>(found);
        unknown.removeAll(KNOWN.keySet());
        Set<String> stale = new TreeSet<>(KNOWN.keySet());
        stale.removeAll(found);
        assertThat(unknown).as("業務の接続を包みの外で得る呼出しが増えました。BackupQuiesceAwareDataSource を通すか、理由を"
                + "書いて一覧に足してください: %s", unknown).isEmpty();
        assertThat(stale).as("一覧に在るが見つからない呼出し（一覧から外してください）: %s", stale).isEmpty();
    }

    @Test
    void 走査は一覧に無い呼出しを見つける_試験の非空虚性() throws IOException {
        Set<String> found = new TreeSet<>();
        try (InputStream in = BusinessConnectionBypassGuardTest.class.getResourceAsStream(
                "BusinessConnectionBypassGuardTest$BypassFixture.class")) {
            scan(in, found);
        }
        assertThat(found).contains(
                "com.ses.backup.quiesce.BusinessConnectionBypassGuardTest:DriverManager.getConnection",
                "com.ses.backup.quiesce.BusinessConnectionBypassGuardTest:HikariDataSource.<init>",
                "com.ses.backup.quiesce.BusinessConnectionBypassGuardTest:unwrap(HikariDataSource)");
    }

    private static void scan(InputStream in, Set<String> sink) throws IOException {
        ClassReader reader = new ClassReader(in);
        String className = reader.getClassName().replace('/', '.');
        int nested = className.indexOf('$');
        String topLevel = nested < 0 ? className : className.substring(0, nested);
        reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                             String[] exceptions) {
                return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                    private boolean hikariClassConstant;

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Type type && "com/zaxxer/hikari/HikariDataSource".equals(type.getInternalName())) {
                            hikariClassConstant = true;
                        }
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                        record(owner, method);
                        if ("unwrap".equals(method) && hikariClassConstant) {
                            sink.add(topLevel + ":unwrap(HikariDataSource)");
                        }
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String invoked, String desc, Handle bootstrap, Object... args) {
                        for (Object argument : args) {
                            if (argument instanceof Handle handle) {
                                record(handle.getOwner(), handle.getName());
                            }
                        }
                    }

                    private void record(String owner, String method) {
                        String simple = owner.substring(owner.lastIndexOf('/') + 1);
                        if ("java/sql/DriverManager".equals(owner) && "getConnection".equals(method)) {
                            sink.add(topLevel + ":DriverManager.getConnection");
                        } else if ("java/sql/Driver".equals(owner) && "connect".equals(method)) {
                            sink.add(topLevel + ":Driver.connect");
                        } else if (DATA_SOURCE_TYPES.contains(owner) && "<init>".equals(method)) {
                            sink.add(topLevel + ":" + simple + ".<init>");
                        } else if ("org/springframework/boot/jdbc/DataSourceBuilder".equals(owner)) {
                            sink.add(topLevel + ":DataSourceBuilder." + method);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }

    /** 走査が見つけることを確かめる fixture（実行しない）。 */
    static final class BypassFixture {
        Connection direct(String url) throws SQLException {
            return DriverManager.getConnection(url);
        }

        Object pool() {
            return new com.zaxxer.hikari.HikariDataSource();
        }

        Object unwrap(javax.sql.DataSource dataSource) throws SQLException {
            return dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        }
    }
}
