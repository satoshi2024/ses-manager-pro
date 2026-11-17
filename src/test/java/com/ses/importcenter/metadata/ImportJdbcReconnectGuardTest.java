package com.ses.importcenter.metadata;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 透過再接続（autoReconnect）が有効なJDBC設定で起動を中止し、例外文言へURL・認証情報を出さないことを確認する。 */
class ImportJdbcReconnectGuardTest {
    private static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/ses_manager_db?useUnicode=true"
            + "&characterEncoding=utf-8&serverTimezone=Asia/Tokyo";

    @Test
    void defaultAndExplicitlyDisabledReconnectSettingsStart() {
        assertDoesNotThrow(() -> guard(new MockEnvironment().withProperty("spring.datasource.url", DEFAULT_URL))
                .afterPropertiesSet());
        assertDoesNotThrow(() -> guard(new MockEnvironment()
                .withProperty("spring.datasource.url", DEFAULT_URL + "&autoReconnect=false")
                .withProperty("spring.datasource.hikari.data-source-properties.autoReconnect", "false"))
                .afterPropertiesSet());
        assertDoesNotThrow(() -> guard(new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:h2:mem:testdb;MODE=MySQL")).afterPropertiesSet());
        assertDoesNotThrow(() -> guard(new MockEnvironment()).afterPropertiesSet());
    }

    @Test
    void autoReconnectInTheJdbcUrlStopsStartupWithoutEchoingTheUrl() {
        for (String parameter : List.of("autoReconnect=true", "AutoReconnect=TRUE", "autoReconnectForPools=true",
                "autoreconnect=yes")) {
            MockEnvironment environment = new MockEnvironment().withProperty("spring.datasource.url",
                    DEFAULT_URL + "&password=secret-in-url&" + parameter);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> guard(environment).afterPropertiesSet(), parameter);

            assertTrue(failure.getMessage().contains("spring.datasource.url"));
            assertFalse(failure.getMessage().contains("secret-in-url"), "URL・認証情報を例外文言へ出さない");
            assertFalse(failure.getMessage().contains("localhost"));
        }
    }

    @Test
    void autoReconnectViaHikariUrlOrDataSourcePropertiesIsAlsoRejected() {
        assertEquals(List.of("spring.datasource.hikari.jdbc-url"), ImportJdbcReconnectGuard.violations(
                new MockEnvironment().withProperty("spring.datasource.hikari.jdbc-url", DEFAULT_URL
                        + "&autoReconnect=true")));
        assertEquals(1, ImportJdbcReconnectGuard.violations(new MockEnvironment()
                .withProperty("spring.datasource.hikari.data-source-properties.autoReconnect", "true")).size());
        assertEquals(1, ImportJdbcReconnectGuard.violations(new MockEnvironment()
                .withProperty("spring.datasource.hikari.data-source-properties.autoReconnectForPools", "YES")).size());
    }

    @Test
    void placeholderResolvedFromAnEnvironmentVariableStyleValueIsChecked() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("DB_URL", DEFAULT_URL + "&autoReconnect=true")
                .withProperty("spring.datasource.url", "${DB_URL:" + DEFAULT_URL + "}");

        assertEquals(List.of("spring.datasource.url"), ImportJdbcReconnectGuard.violations(environment));
    }

    /**
     * b-14（W2-NF06 review P3-2）: 通信異常時に別の接続へ切り替える multi-host・failover・loadbalance・replication・SRV の
     * URL も、透過再接続と同じく結果不明のCOMMITの分類を崩すため起動を中止する。例外文言へURL・host名を出さない。
     */
    @Test
    void multiHostとfailoverとloadbalanceとreplicationとSRVのURLは起動を中止する() {
        for (String url : List.of(
                "jdbc:mysql://db-a:3306,db-b:3306/ses_manager_db",
                "jdbc:mysql://[db-a:3306,db-b:3306]/ses_manager_db?useUnicode=true",
                "jdbc:mysql://address=(host=db-a)(port=3306),address=(host=db-b)(port=3306)/ses_manager_db",
                "jdbc:mysql://(host=db-a,port=3306)/ses_manager_db",
                "jdbc:mysql://app:secret-in-url@db-a:3306,db-b:3306/ses_manager_db",
                "jdbc:mysql:loadbalance://db-a:3306,db-b:3306/ses_manager_db",
                "jdbc:mysql:loadbalance://db-a:3306/ses_manager_db",
                "jdbc:mysql:replication://db-a:3306,db-b:3306/ses_manager_db",
                "JDBC:MYSQL:REPLICATION://db-a/ses_manager_db",
                "jdbc:mysql+srv://_mysql._tcp.db.example/ses_manager_db",
                "jdbc:mysql+srv:loadbalance://_mysql._tcp.db.example/ses_manager_db",
                "jdbc:mysqlx://db-a:33060/ses_manager_db",
                "jdbc:mariadb:failover://db-a,db-b/ses_manager_db",
                "jdbc:mariadb:sequential://db-a,db-b/ses_manager_db",
                "jdbc:mariadb:aurora://cluster.example/ses_manager_db")) {
            MockEnvironment environment = new MockEnvironment().withProperty("spring.datasource.url", url);

            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> guard(environment).afterPropertiesSet(), url);

            assertTrue(failure.getMessage().contains("spring.datasource.url(multiHost)"), url);
            assertFalse(failure.getMessage().contains("db-a") || failure.getMessage().contains("secret-in-url")
                    || failure.getMessage().contains("db.example"), "URL・host名・認証情報を例外文言へ出さない");
        }
        assertEquals(List.of("spring.datasource.hikari.jdbc-url(multiHost)"), ImportJdbcReconnectGuard.violations(
                new MockEnvironment().withProperty("spring.datasource.hikari.jdbc-url",
                        "jdbc:mysql:replication://db-a,db-b/ses_manager_db")));
    }

    /**
     * 通す URL は、Connector/J 自身が単一接続・host 1件と解析するもの（またはConnector/Jが扱わない他DBのURL）だけ。
     *
     * <p>RB-NF05 delta review N-P3-1 による期待値の訂正: 以前ここで「通す」としていた
     * {@code jdbc:mysql://app:p%40ss,word@db-a:3306/...} は、Connector/J が authority を {@code ,} で先に分けてから user 情報を
     * 取るため、{@code app:p%40ss} と {@code word@db-a:3306} の2つの host になる（8.3.0 では解析に失敗する）。単一 host という
     * 期待値そのものが誤りだったため、拒否の側（下の試験）へ移した。
     * 断言を緩めたのではない。
     */
    @Test
    void 単一hostのURLはuser情報とIPv6とqueryがあっても通しMySQL以外のURLは対象にしない() {
        for (String url : List.of(DEFAULT_URL,
                "jdbc:mysql://app:pw@db-a:3306/ses_manager_db?sessionVariables=a=1,b=2",
                "jdbc:mysql://[2001:db8::10]:3306/ses_manager_db",
                "jdbc:mysql://db-a/ses_manager_db",
                "jdbc:mysql://db-a")) {
            assertEquals("SINGLE", driver(url), "前提: Connector/J は単一接続・host 1件と解析する: " + url);
            assertEquals(List.of(), ImportJdbcReconnectGuard.violations(
                    new MockEnvironment().withProperty("spring.datasource.url", url)), url);
        }
        for (String url : List.of("jdbc:mariadb://db-a:3306/ses_manager_db",
                "jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "jdbc:postgresql://db-a,db-b/other")) {
            assertEquals("NOT_ACCEPTED", driver(url), "前提: Connector/J が扱わない URL: " + url);
            assertEquals(List.of(), ImportJdbcReconnectGuard.violations(
                    new MockEnvironment().withProperty("spring.datasource.url", url)), url);
        }
    }

    /**
     * RB-NF05 delta review N-P3-1: 自前の文字列判定だけでは通っていたが、Connector/J 8.3.0 が複数 host・load-balance として
     * 解析する（または解析できない）URL。判定の正本は Connector/J の解析結果であり、各 URL の前提として Connector/J 自身の
     * 解析結果も断言する（Connector/J を上げて解析が変われば、この試験が気付く）。
     */
    @Test
    void user情報のカンマとschemeの空白と百分率符号化もdriverの解析に合わせて拒否する() {
        java.util.Map<String, String> driverVerdicts = new java.util.LinkedHashMap<>();
        // (a) scheme と // の間の空白（Connector/J の正規表現は \s* を許す）
        driverVerdicts.put("jdbc:mysql: //db-a,db-b/ses_manager_db", "FAILOVER_CONNECTION");
        driverVerdicts.put("jdbc:mysql:\t//db-a,db-b/ses_manager_db", "FAILOVER_CONNECTION");
        // (b) 百分率符号化した scheme（Connector/J は scheme を戻してから型を決める）
        driverVerdicts.put("jdbc:%6Dysql://db-a,db-b/ses_manager_db", "FAILOVER_CONNECTION");
        driverVerdicts.put("jdbc:%6Dysql:loadbalance://db-a:3306/ses_manager_db", "LOADBALANCE_CONNECTION");
        driverVerdicts.put("jdbc:mysql%3Aloadbalance://db-a/ses_manager_db", "LOADBALANCE_CONNECTION");
        driverVerdicts.put("jdbc:%6Dysql+srv://_mysql._tcp.db.example/ses_manager_db", "FAILOVER_DNS_SRV_CONNECTION");
        // (c) user 情報の ,（Connector/J は , で分けてから user 情報を取る）。password が数字なら本当の failover になる。
        //     旧試験の「通す」は誤りだった（上の試験の注釈）。
        driverVerdicts.put("jdbc:mysql://app:3306,x@db-a/ses_manager_db", "FAILOVER_CONNECTION");
        driverVerdicts.put("jdbc:mysql://app:p%40ss,word@db-a:3306/ses_manager_db?sessionVariables=a=1,b=2", "ERROR");
        driverVerdicts.put("jdbc:mysql://app:p;w,x@db-a:3306/ses_manager_db", "ERROR");
        driverVerdicts.forEach((url, verdict) -> {
            assertEquals(verdict, driver(url), "前提: Connector/J 8.3.0 の解析結果: " + url);
            List<String> violations = ImportJdbcReconnectGuard.violations(
                    new MockEnvironment().withProperty("spring.datasource.url", url));
            assertEquals(1, violations.size(), url + " " + violations);
            assertTrue(violations.get(0).equals("spring.datasource.url(multiHost)")
                    || violations.get(0).equals("spring.datasource.url(unparsable)"), url + " " + violations);
            assertEquals(violations, ImportJdbcReconnectGuard.violations(new MockEnvironment().withProperty(
                    "spring.datasource.hikari.data-source-properties.url", url)).stream()
                    .map(violation -> violation.replace("spring.datasource.hikari.data-source-properties.url",
                            "spring.datasource.url")).toList(), "data source の url も同じ判定: " + url);
        });
        // 同じ表記でも単一 host なら、Connector/J の解析どおり通す（判定の正本は Connector/J）。
        for (String url : List.of("jdbc:mysql: //db-a/ses_manager_db", "jdbc:%6dysql://db-a/ses_manager_db",
                "jdbc:mysql://app:p;w@db-a/ses_manager_db")) {
            assertEquals("SINGLE", driver(url), url);
            assertEquals(List.of(), ImportJdbcReconnectGuard.violations(
                    new MockEnvironment().withProperty("spring.datasource.url", url)), url);
        }
    }

    /**
     * 判定の不変条件（oracle）: Connector/J が単一接続・host 1件と解析しない URL は、必ず拒否する。逆に、自前の前段判定は
     * Connector/J より厳しくてよい（{@code ;}・{@code (}・{@code =} を含む host 部、空の host 部は Connector/J が1 host と
     * 解析しても拒否する）。ここに並べた全ての URL で、通した URL が Connector/J の単一接続であることを断言する。
     */
    @Test
    void 通すURLは全てConnectorJの単一接続で前段判定はそれより厳しい() {
        List<String> urls = List.of(DEFAULT_URL, DEFAULT_URL + "&autoReconnect=%74rue",
                "jdbc:mysql://db-a:3306,db-b:3306/ses_manager_db", "jdbc:mysql://[db-a:3306,db-b:3306]/ses_manager_db",
                "jdbc:mysql://address=(host=db-a)(port=3306)/ses_manager_db", "jdbc:mysql://(host=db-a,port=3306)/ses_manager_db",
                "jdbc:mysql:loadbalance://db-a:3306/ses_manager_db", "jdbc:mysql:replication://db-a,db-b/ses_manager_db",
                "jdbc:mysql+srv://_mysql._tcp.db.example/ses_manager_db", "jdbc:mysql://db-a;x,db-b:3306/ses_manager_db",
                "jdbc:mysql://db-a;db-b/ses_manager_db", "jdbc:mysql://db-a;x", "jdbc:mysql://db-a:3306;failover=true/ses_manager_db",
                "jdbc:mysql://app:p;w@db-a;x,db-b/ses_manager_db", "jdbc:mysql://db-a,db-b#fragment", "jdbc:mysql://",
                "jdbc:mysql://db-a:3306/ses_manager_db;x,y", "jdbc:mysql://db-a:3306?sessionVariables=a=1;b=2",
                "jdbc:mysql://db-a:3306#a,b;c", "jdbc:mysql: //db-a,db-b/ses_manager_db",
                "jdbc:%6Dysql:loadbalance://db-a:3306/ses_manager_db", "jdbc:mysql://app:3306,x@db-a/ses_manager_db",
                "jdbc:mysql://app:p;w,x@db-a:3306/ses_manager_db", "jdbc:mysql://app:p;w@db-a/ses_manager_db",
                "jdbc:mysql://[2001:db8::10]:3306/ses_manager_db");
        for (String url : urls) {
            boolean allowed = ImportJdbcReconnectGuard.connectionTargetViolation(url) == null;
            if (allowed) {
                assertEquals("SINGLE", driver(url), "通した URL は Connector/J の単一接続でなければならない: " + url);
            }
            if (!"SINGLE".equals(driver(url))) {
                assertFalse(allowed, "Connector/J が単一接続と解析しない URL は拒否する: " + url);
            }
        }
        // 前段判定が Connector/J より厳しい例（Connector/J は1 host と解析するが拒否する）。
        for (String stricter : List.of("jdbc:mysql://db-a;db-b/ses_manager_db", "jdbc:mysql://db-a;x",
                "jdbc:mysql://address=(host=db-a)(port=3306)/ses_manager_db", "jdbc:mysql://")) {
            assertEquals("SINGLE", driver(stricter), stricter);
            assertEquals(ImportJdbcReconnectGuard.MULTI_HOST, ImportJdbcReconnectGuard.connectionTargetViolation(stricter),
                    stricter);
        }
    }

    /** Connector/J 8.3.0 自身の解析結果（判定の正本）。{@code SINGLE} は単一接続かつ host 1件。DNS の照会はしない。 */
    private static String driver(String url) {
        return driver(url, new Properties());
    }

    /** {@link #driver(String)} を、Hikari が driver へ渡すのと同じ Properties（info）付きで解析する（D-P3-1）。 */
    private static String driver(String url, Properties info) {
        if (!com.mysql.cj.conf.ConnectionUrl.acceptsUrl(url)) return "NOT_ACCEPTED";
        try {
            com.mysql.cj.conf.ConnectionUrl parsed = com.mysql.cj.conf.ConnectionUrl.getConnectionUrlInstance(url, info);
            if (parsed.getType() != com.mysql.cj.conf.ConnectionUrl.Type.SINGLE_CONNECTION) return parsed.getType().name();
            return parsed.getHostsList().size() == 1 ? "SINGLE" : "SINGLE_CONNECTION_WITH_" + parsed.getHostsList().size();
        } catch (RuntimeException unparsable) {
            return "ERROR";
        }
    }

    /**
     * RB-NF05 review P3-1: Connector/J の authority は {@code [^/?#]*} で {@code ;} を含む。{@code ;} を host 部の終わりと
     * みなすと {@code jdbc:mysql://h1;x,h2/db} が通り、実際は failover になる。host 部の {@code ;} は全て拒否する。
     */
    @Test
    void host部のセミコロンは区切りとみなさず拒否しdata_sourceのurlも同じ規則で検査する() {
        for (String url : List.of(
                "jdbc:mysql://db-a;x,db-b:3306/ses_manager_db",
                "jdbc:mysql://db-a;db-b/ses_manager_db",
                "jdbc:mysql://db-a:3306;failover=true/ses_manager_db",
                "jdbc:mysql://app:p;w@db-a;x,db-b/ses_manager_db",
                "jdbc:mysql://db-a;x",
                "jdbc:mysql://db-a,db-b#fragment",
                "jdbc:mysql://db-a,db-b",
                "jdbc:mariadb://db-a;db-b/ses_manager_db")) {
            assertEquals(List.of("spring.datasource.url(multiHost)"), ImportJdbcReconnectGuard.violations(
                    new MockEnvironment().withProperty("spring.datasource.url", url)), url);
        }
        // user 情報（@ より前）の ; と、path・query・fragment の後ろの ; や , は host 部ではない。
        // 期待値の訂正（delta review N-P3-1）: 以前ここで通していた jdbc:mysql://app:p;w,x@db-a:3306/... は、Connector/J が
        // authority を , で先に分けるため2つの host になる（8.3.0 では解析に失敗する）。誤った期待値だったため拒否の試験へ移した。
        for (String url : List.of("jdbc:mysql://app:p;w@db-a:3306/ses_manager_db",
                "jdbc:mysql://db-a:3306/ses_manager_db;x,y",
                "jdbc:mysql://db-a:3306?sessionVariables=a=1;b=2",
                "jdbc:mysql://db-a:3306#a,b;c")) {
            assertEquals(List.of(), ImportJdbcReconnectGuard.violations(
                    new MockEnvironment().withProperty("spring.datasource.url", url)), url);
        }
        assertEquals(List.of("spring.datasource.hikari.data-source-properties.url(multiHost)"),
                ImportJdbcReconnectGuard.violations(new MockEnvironment().withProperty(
                        "spring.datasource.hikari.data-source-properties.url", "jdbc:mysql://db-a;x,db-b/ses_manager_db")));
        assertEquals(List.of("spring.datasource.hikari.data-source-properties.url"),
                ImportJdbcReconnectGuard.violations(new MockEnvironment().withProperty(
                        "spring.datasource.hikari.data-source-properties.url", DEFAULT_URL + "&autoReconnect=true")));
    }

    /** Connector/J は URL の値を百分率符号化から戻して読むため、符号化した autoReconnect も拒否する（同 P3-2 の3）。 */
    @Test
    void 百分率符号化したautoReconnectも拒否する() {
        for (String parameter : List.of("autoReconnect=%74rue", "auto%52econnect=true", "autoReconnectForPools=%59ES")) {
            assertEquals(List.of("spring.datasource.url"), ImportJdbcReconnectGuard.violations(new MockEnvironment()
                    .withProperty("spring.datasource.url", DEFAULT_URL + "&" + parameter)), parameter);
        }
        assertEquals(List.of(), ImportJdbcReconnectGuard.violations(new MockEnvironment()
                .withProperty("spring.datasource.url", DEFAULT_URL + "&autoReconnect=%66alse")));
        // 期待値の訂正（delta review N-P3-1）: 不正な百分率符号化は Connector/J 8.3.0 自身が解析できない
        // （IllegalArgumentException）。driver が解析できない URL は接続もできないため、判定の正本に合わせて拒否する。
        assertEquals("ERROR", driver(DEFAULT_URL + "&note=%ZZ"));
        assertEquals(List.of("spring.datasource.url(unparsable)"), ImportJdbcReconnectGuard.violations(new MockEnvironment()
                .withProperty("spring.datasource.url", DEFAULT_URL + "&note=%ZZ")), "driver が解析できない URL は拒否する");
    }

    private static final String DSP = "spring.datasource.hikari.data-source-properties";
    private static final String SRV_URL = "jdbc:mysql://_mysql._tcp.db.example/ses_manager_db";

    /**
     * RB-NF05 delta review D-P3-1: Hikari は data-source-properties を driver の Properties（info）として渡し、Connector/J は
     * info の {@code dnsSrv} を URL より先に見て接続の型を決める。以前は解析に空の Properties を渡していたため、
     * 単一 host の URL に {@code dnsSrv=true} を足すだけで DNS SRV の failover が検査を通った。
     * 解析に同じ Properties を渡す（a）ことと、key そのものを拒否する（b）ことを、それぞれ独立に確かめる。
     */
    @Test
    void dnsSrvをdata_source_propertiesに置くと単一hostのURLでもSRVのfailoverになるため解析に渡して拒否する() {
        Properties dnsSrv = info("dnsSrv", "true");
        String hostWithPort = "jdbc:mysql://db-a:3306/ses_manager_db";
        assertEquals("SINGLE", driver(SRV_URL), "前提: URL だけなら Connector/J は単一接続と解析する");
        assertEquals("FAILOVER_DNS_SRV_CONNECTION", driver(SRV_URL, dnsSrv), "前提: info の dnsSrv=true で DNS SRV の failover になる");
        assertEquals("LOADBALANCE_DNS_SRV_CONNECTION",
                driver("jdbc:mysql:loadbalance://_mysql._tcp.db.example/ses_manager_db", dnsSrv), "前提: loadbalance も SRV 型になる");
        assertEquals("ERROR", driver(hostWithPort, dnsSrv), "前提: port 付きの host に dnsSrv=true を足すと driver は解析に失敗する");

        // (a) 解析に info を渡す。key の拒否とは独立に効く。
        assertNull(ImportJdbcReconnectGuard.connectionTargetViolation(SRV_URL));
        assertEquals(ImportJdbcReconnectGuard.MULTI_HOST, ImportJdbcReconnectGuard.connectionTargetViolation(SRV_URL, dnsSrv));
        assertEquals(ImportJdbcReconnectGuard.UNPARSABLE,
                ImportJdbcReconnectGuard.connectionTargetViolation(hostWithPort, dnsSrv));
        assertNull(ImportJdbcReconnectGuard.connectionTargetViolation(SRV_URL, new Properties()));

        // (a)+(b) 設定から: URL の違反と key の違反の両方を出す。
        String keyViolation = DSP + ".dnsSrv(" + ImportJdbcReconnectGuard.CONNECTION_TOPOLOGY + ")";
        for (String urlProperty : List.of("spring.datasource.url", "spring.datasource.hikari.jdbc-url")) {
            assertEquals(List.of(urlProperty + "(multiHost)", keyViolation), ImportJdbcReconnectGuard.violations(
                    environment(urlProperty, SRV_URL, DSP + ".dnsSrv", "true")), urlProperty);
            assertEquals(List.of(urlProperty + "(unparsable)", keyViolation), ImportJdbcReconnectGuard.violations(
                    environment(urlProperty, hostWithPort, DSP + ".dnsSrv", "true")), urlProperty);
        }
        assertEquals(Set.of(DSP + ".url(multiHost)", keyViolation), Set.copyOf(ImportJdbcReconnectGuard.violations(
                environment(DSP + ".url", SRV_URL, DSP + ".dnsSrv", "true"))), "data source の url も同じ info で解析する");

        // (b) 大文字小文字の違う key と false の値は driver が型を変えないが、値と大文字小文字に関係なく拒否する。
        for (String[] entry : List.of(new String[]{"dnsSrv", "false"}, new String[]{"DNSSRV", "TRUE"},
                new String[]{"dnssrv", "true"}, new String[]{"DnsSrv", "yes"})) {
            assertEquals("SINGLE", driver(SRV_URL, info(entry[0], entry[1])), "前提: driver は型を変えない: " + entry[0]);
            assertEquals(List.of(DSP + "." + entry[0] + "(" + ImportJdbcReconnectGuard.CONNECTION_TOPOLOGY + ")"),
                    ImportJdbcReconnectGuard.violations(environment("spring.datasource.url", SRV_URL,
                            DSP + "." + entry[0], entry[1])), entry[0] + "=" + entry[1]);
        }

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> guard(environment(
                "spring.datasource.url", SRV_URL, DSP + ".dnsSrv", "true")).afterPropertiesSet());
        assertTrue(failure.getMessage().contains(keyViolation), failure.getMessage());
        assertFalse(failure.getMessage().contains("db.example"), "host 名を例外文言へ出さない");

        // Spring の username・password は Hikari が driver の user・password として足すが、接続の型・host を変えない。
        assertEquals("SINGLE", driver(DEFAULT_URL, info("user", "app", "password", "pw")));
        assertEquals(List.of(), ImportJdbcReconnectGuard.violations(environment("spring.datasource.url", DEFAULT_URL,
                "spring.datasource.username", "app", "spring.datasource.password", "pw")));
    }

    /**
     * RB-NF05 delta review D-P3-1 (b): data-source-properties に置くと接続の型・接続先・failover／load-balance／replication の
     * 動作を変える key を、値と大文字小文字に関係なく拒否する。全ての key について、前提として Connector/J 8.3.0 の key
     * （{@code PropertyKey} の key 名か旧名、または {@code MysqlDataSource} の setter）であることと、その Properties での
     * driver 自身の解析結果を断言してから拒否を断言する。{@code dnsSrv} 以外は driver の解析では単一接続のままで、
     * 型の判定だけでは見えないため key で拒否する。
     */
    @Test
    void data_source_propertiesの接続の型と接続先と切替えを変えるkeyは値と大文字小文字に関係なく拒否する() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("dnsSrv", "true");
        keys.put("xdevapi.dns-srv", "true");
        keys.put("host", "db-b");
        keys.put("port", "3307");
        keys.put("protocol", "tcp");
        keys.put("path", "x");
        keys.put("address", "(host=db-b)");
        keys.put("priority", "1");
        keys.put("type", "x");
        keys.put("socketFactory", "com.mysql.cj.protocol.StandardSocketFactory");
        keys.put("failOverReadOnly", "false");
        keys.put("retriesAllDown", "1");
        keys.put("secondsBeforeRetrySource", "1");
        keys.put("queriesBeforeRetrySource", "1");
        keys.put("allowSourceDownConnections", "true");
        keys.put("allowReplicaDownConnections", "true");
        keys.put("readFromSourceWhenNoReplicas", "true");
        keys.put("secondsBeforeRetryMaster", "1");
        keys.put("queriesBeforeRetryMaster", "1");
        keys.put("allowMasterDownConnections", "true");
        keys.put("allowSlaveDownConnections", "true");
        keys.put("readFromMasterWhenNoSlaves", "true");
        keys.put("ha.loadBalanceStrategy", "random");
        keys.put("ha.enableJMX", "true");
        keys.put("loadBalanceAutoCommitStatementRegex", "x");
        keys.put("loadBalanceAutoCommitStatementThreshold", "1");
        keys.put("loadBalanceBlocklistTimeout", "1");
        keys.put("loadBalanceBlacklistTimeout", "1");
        keys.put("loadBalanceConnectionGroup", "g");
        keys.put("loadBalanceExceptionChecker", "x");
        keys.put("loadBalanceHostRemovalGracePeriod", "1");
        keys.put("loadBalancePingTimeout", "1");
        keys.put("loadBalanceSQLExceptionSubclassFailover", "x");
        keys.put("loadBalanceSQLStateFailover", "x");
        keys.put("loadBalanceValidateConnectionOnSwapServer", "true");
        keys.put("replicationConnectionGroup", "g");
        keys.put("serverAffinityOrder", "db-a");
        keys.put("maxReconnects", "1");
        keys.put("reconnectAtTxEnd", "true");
        keys.put("serverName", "db-b");
        keys.put("portNumber", "3307");
        assertEquals(ImportJdbcReconnectGuard.CONNECTION_TOPOLOGY_KEYS, keys.keySet().stream()
                .map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet()), "拒否する key の全てを試す");
        Set<String> dataSourceSetters = java.util.Arrays.stream(com.mysql.cj.jdbc.MysqlDataSource.class.getMethods())
                .map(java.lang.reflect.Method::getName).collect(Collectors.toSet());

        keys.forEach((name, value) -> {
            if (Set.of("serverName", "portNumber").contains(name)) {
                assertNull(com.mysql.cj.conf.PropertyKey.fromValue(name), name);
                assertTrue(dataSourceSetters.contains("set" + Character.toUpperCase(name.charAt(0)) + name.substring(1)),
                        "前提: MysqlDataSource の bean property: " + name);
            } else {
                assertNotNull(com.mysql.cj.conf.PropertyKey.fromValue(name), "前提: Connector/J 8.3.0 の key（旧名を含む）: " + name);
            }
            boolean typeChanging = "dnsSrv".equals(name);
            assertEquals(typeChanging ? "ERROR" : "SINGLE", driver(DEFAULT_URL, info(name, value)),
                    "前提: この Properties での Connector/J 自身の解析結果: " + name);
            List<String> expected = new java.util.ArrayList<>();
            if (typeChanging) expected.add("spring.datasource.url(unparsable)");
            expected.add(DSP + "." + name + "(" + ImportJdbcReconnectGuard.CONNECTION_TOPOLOGY + ")");
            assertEquals(expected, ImportJdbcReconnectGuard.violations(
                    environment("spring.datasource.url", DEFAULT_URL, DSP + "." + name, value)), name);
            for (String variant : List.of(name.toUpperCase(Locale.ROOT), name.toLowerCase(Locale.ROOT))) {
                if (variant.equals(name)) continue;
                assertEquals("SINGLE", driver(DEFAULT_URL, info(variant, value)), "前提: driver は別の大文字小文字を読まない: " + variant);
                assertEquals(List.of(DSP + "." + variant + "(" + ImportJdbcReconnectGuard.CONNECTION_TOPOLOGY + ")"),
                        ImportJdbcReconnectGuard.violations(environment("spring.datasource.url", DEFAULT_URL,
                                DSP + "." + variant, value)), variant);
            }
        });
    }

    /**
     * D-P3-1: autoReconnect 系の key は data-source-properties に置いても拒否する（URL の query だけでなく）。driver の解析では
     * 単一接続のままのため、値（true/yes）で判定する。key の大文字小文字と値の前後の空白は問わず、false・no は通す。
     */
    @Test
    void data_source_propertiesのautoReconnectは大文字小文字と空白に関係なく拒否しfalseは通す() {
        assertEquals("SINGLE", driver(DEFAULT_URL, info("autoReconnect", "true")), "前提: 型の判定では見えない");
        assertEquals("SINGLE", driver(DEFAULT_URL, info("autoReconnectForPools", "true")), "前提: 型の判定では見えない");
        for (String[] entry : List.of(new String[]{"autoReconnect", "true"}, new String[]{"AUTORECONNECT", "Yes"},
                new String[]{"autoreconnectforpools", " TRUE "}, new String[]{"AutoReconnectForPools", "yes"})) {
            assertEquals(List.of(DSP + "." + entry[0]), ImportJdbcReconnectGuard.violations(environment(
                    "spring.datasource.url", DEFAULT_URL, DSP + "." + entry[0], entry[1])), entry[0] + "=" + entry[1]);
        }
        for (String[] entry : List.of(new String[]{"autoReconnect", "false"}, new String[]{"autoReconnectForPools", "no"})) {
            assertEquals(List.of(), ImportJdbcReconnectGuard.violations(environment(
                    "spring.datasource.url", DEFAULT_URL, DSP + "." + entry[0], entry[1])), entry[0] + "=" + entry[1]);
        }
    }

    private static Properties info(String... nameValues) {
        Properties info = new Properties();
        for (int i = 0; i < nameValues.length; i += 2) info.setProperty(nameValues[i], nameValues[i + 1]);
        return info;
    }

    private static MockEnvironment environment(String... nameValues) {
        MockEnvironment environment = new MockEnvironment();
        for (int i = 0; i < nameValues.length; i += 2) environment.setProperty(nameValues[i], nameValues[i + 1]);
        return environment;
    }

    private static ImportJdbcReconnectGuard guard(MockEnvironment environment) {
        return new ImportJdbcReconnectGuard(environment);
    }
}
