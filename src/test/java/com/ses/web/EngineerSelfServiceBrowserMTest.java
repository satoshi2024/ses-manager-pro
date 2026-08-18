package com.ses.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerSales;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.survey.SurveyService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T093（M）browser Demo: 要員セルフサービスポータルV2（my dashboard/profile/payroll/expenses/1on1/surveys/timesheet）を
 * 実Chrome（CDP・headless）でdesktop（1920x1080）と390px（390x844）で実測し、
 * ページ固有DOMの表示待ち・エラーページ非表示・console error 0・network 4xx/5xx 0・本人BのPII非漏洩を検証する。
 * 証跡は `target/browser-m-evidence/`（.gitignore対象）へ保存し、tracked worktreeを汚さない。
 *
 * <p>Chrome依存のため既定のfast suiteへ無条件追加しない。専用profile（-Pbrowser-tests）とCI gateで実行する。
 */
@Tag("browser")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EngineerSelfServiceBrowserMTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private EngineerAccountLinkMapper accountLinkMapper;
    @Autowired
    private EngineerSalesMapper engineerSalesMapper;
    @Autowired
    private SurveyService surveyService;

    private static final String DEMO_USERNAME = "portal_engineer_demo";
    private static final String DEMO_PASSWORD = "demoPassword123";
    private static final String DEMO_REAL_NAME = "要員ポータル デモ太郎";

    /** 本人BのPII（DOM/APIへ一切現れてはならない。R5）。 */
    private static final String B_PII_NAME = "非表示要員B-ブラウザ検証";
    private static final String B_PII_PHONE = "090-5555-0000";

    /** 画面ごとのページ固有DOM（表示待ち・エラーページ判定の境界）。表示順もこの順に固定する。 */
    private static final List<Map.Entry<String, String>> SCREENS = List.of(
            Map.entry("dashboard", "#my-name"),
            Map.entry("profile", "#profile-body"),
            Map.entry("payroll", "#payrollRows"),
            Map.entry("expenses", "#expense-table-body"),
            Map.entry("one-on-ones", "#oneonone-body"),
            Map.entry("surveys", "#campaign-list"),
            Map.entry("timesheet", "#myTimesheetSummary"));

    @Test
    @DisplayName("T093-M: 実Chromeで要員セルフサービスポータル7画面をdesktop/390pxで実測（DOM/console/network/PII検証）")
    void captureEngineerPortalScreensWithRealBrowser() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが見つかりません");

        Seed seed = seedPortalData();
        String baseUrl = "http://localhost:" + port;
        Path evidenceDir = Path.of("target", "browser-m-evidence");
        Files.createDirectories(evidenceDir);
        String runId = "browser-m-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Files.writeString(evidenceDir.resolve("run-id.txt"), runId + "\n");

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("runId", runId);
        summary.put("baseUrl", baseUrl);
        summary.put("username", DEMO_USERNAME);
        summary.put("engineerId", seed.engineerId());
        summary.put("screens", SCREENS.size());
        summary.put("evidenceDir", evidenceDir.toString());

        try {
            runViewport(chrome, baseUrl, evidenceDir, runId, "desktop", 1920, 1080, seed, summary, true);
            runViewport(chrome, baseUrl, evidenceDir, runId, "mobile390", 390, 844, seed, summary, false);
        } finally {
            SecurityContextHolder.clearContext();
        }

        Files.writeString(evidenceDir.resolve("summary.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        System.out.println("T093-M browser Demo done: " + evidenceDir + " (runId=" + runId + ")");
    }

    // ============================================================
    // シード
    // ============================================================

    private record Seed(long userId, long engineerId, long salesUserId, long surveyCampaignId) {
    }

    private Seed seedPortalData() {
        // H2のsys_user.role ENUMはV32未適用のため'要員'が無い。MySQLのV32相当へ拡張してから要員を登録する。
        try {
            jdbcTemplate.execute("ALTER TABLE sys_user MODIFY role ENUM('管理者','営業','HR','マネージャー','要員') NOT NULL");
        } catch (RuntimeException e) {
            // 既に拡張済みなら無視（shared H2の再初期化後は再適用される）。
        }

        // ---- 本人（要員ロール。既存ユーザー再利用の分岐でも必ず要員へ更新する） ----
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, DEMO_USERNAME));
        Long userId;
        if (user == null) {
            user = SysUser.builder()
                    .username(DEMO_USERNAME)
                    .password(DEMO_PASSWORD)
                    .realName(DEMO_REAL_NAME)
                    .role("要員")
                    .status(1)
                    .build();
            sysUserMapper.insert(user);
            userId = user.getId();
        } else {
            user.setPassword(DEMO_PASSWORD);
            user.setRealName(DEMO_REAL_NAME);
            user.setRole("要員");
            user.setStatus(1);
            sysUserMapper.updateById(user);
            userId = user.getId();
        }

        Engineer existingEng = engineerMapper.selectOne(
                new LambdaQueryWrapper<Engineer>().eq(Engineer::getFullName, DEMO_REAL_NAME));
        Long engineerId;
        if (existingEng == null) {
            Engineer eng = Engineer.builder()
                    .fullName(DEMO_REAL_NAME)
                    .phone("090-9999-8888")
                    .nearestStation("新宿")
                    .employmentType("正社員")
                    .status("稼動中")
                    .build();
            engineerMapper.insert(eng);
            engineerId = eng.getId();
            jdbcTemplate.update("DELETE FROM t_engineer_accounting_history WHERE engineer_id = ?", engineerId);
        } else {
            engineerId = existingEng.getId();
        }

        EngineerAccountLink link = accountLinkMapper.selectOne(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getSysUserId, userId));
        if (link == null) {
            link = new EngineerAccountLink();
            link.setEngineerId(engineerId);
            link.setSysUserId(userId);
            accountLinkMapper.insert(link);
        }

        // ---- 本人B（PII非漏洩検証用。ログインしない） ----
        SysUser userB = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, "portal_engineer_demo_b"));
        Long userBId;
        if (userB == null) {
            userB = SysUser.builder()
                    .username("portal_engineer_demo_b")
                    .password("b-password")
                    .realName("B氏")
                    .role("要員")
                    .status(1)
                    .build();
            sysUserMapper.insert(userB);
            userBId = userB.getId();
        } else {
            userBId = userB.getId();
        }
        Engineer engineerB = engineerMapper.selectOne(
                new LambdaQueryWrapper<Engineer>().eq(Engineer::getFullName, B_PII_NAME));
        Long engineerBId;
        if (engineerB == null) {
            engineerB = Engineer.builder()
                    .fullName(B_PII_NAME)
                    .phone(B_PII_PHONE)
                    .nearestStation("秘密駅")
                    .employmentType("正社員")
                    .status("稼動中")
                    .build();
            engineerMapper.insert(engineerB);
            engineerBId = engineerB.getId();
            jdbcTemplate.update("DELETE FROM t_engineer_accounting_history WHERE engineer_id = ?", engineerBId);
        } else {
            engineerBId = engineerB.getId();
        }
        EngineerAccountLink linkB = accountLinkMapper.selectOne(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getSysUserId, userBId));
        if (linkB == null) {
            linkB = new EngineerAccountLink();
            linkB.setEngineerId(engineerBId);
            linkB.setSysUserId(userBId);
            accountLinkMapper.insert(linkB);
        }

        // ---- 担当営業（1on1相手） ----
        SysUser salesUser = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, "portal_demo_sales"));
        Long salesUserId;
        if (salesUser == null) {
            salesUser = SysUser.builder()
                    .username("portal_demo_sales")
                    .password("x")
                    .realName("ポータルデモ営業")
                    .role("営業")
                    .status(1)
                    .build();
            sysUserMapper.insert(salesUser);
            salesUserId = salesUser.getId();
        } else {
            salesUserId = salesUser.getId();
        }
        EngineerSales existingPrimary = engineerSalesMapper.selectOne(new LambdaQueryWrapper<EngineerSales>()
                .eq(EngineerSales::getEngineerId, engineerId)
                .isNull(EngineerSales::getReleasedAt)
                .last("LIMIT 1"));
        if (existingPrimary == null) {
            EngineerSales es = EngineerSales.builder()
                    .engineerId(engineerId)
                    .salesUserId(salesUserId)
                    .primaryFlag(1)
                    .assignedAt(LocalDate.now())
                    .build();
            engineerSalesMapper.insert(es);
        }

        // ---- 回答対象サーベイキャンペーン（HRとしてJVM側で作成・配信） ----
        Long campaignId = seedSurveyCampaign();

        return new Seed(userId, engineerId, salesUserId, campaignId);
    }

    private Long seedSurveyCampaign() {
        // 共有H2にHR既定ユーザーは居ないため、service層の管理ロール制約を満たすHRを一時認証で立てる。
        // キャンペーンは毎回新規作成する（他テストの残存キャンペーンは設問が異なるため再利用しない）。
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("999999999", "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_HR"))));
        try {
            SurveyService.TemplateDto template = surveyService.createTemplate(
                    "T093-BROWSER-" + System.nanoTime(), "稼働満足度ブラウザ実測",
                    "", List.of(
                            new SurveyService.QuestionDef("q1", "満足度", "SCALE1_5", false),
                            new SurveyService.QuestionDef("q2", "負荷感", "SCALE1_5_COMMENT", true)));
            SurveyService.CampaignDto campaign = surveyService.createCampaign(
                    template.id(), "T093ブラウザ回答キャンペーン",
                    LocalDate.now(), LocalDate.now().plusDays(7));
            surveyService.activateCampaign(campaign.id());
            return campaign.id();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ============================================================
    // ビューポート別実行
    // ============================================================

    private void runViewport(Path chrome, String baseUrl, Path evidenceDir, String runId,
                             String viewport, int width, int height, Seed seed, ObjectNode summary,
                             boolean withOperations) throws Exception {
        Path profile = Files.createTempDirectory("chrome-profile-portal-" + viewport);
        ArrayNode assertions = summary.putArray(viewport + "-assertions");
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, width, height)) {
            // ---- 1. ログイン ----
            browser.navigate(baseUrl + "/login");
            waitFor(browser, "document.querySelector('#username') !== null || document.querySelector('input[name=username]') !== null");
            browser.evaluate("(function(){" +
                    "var u=document.querySelector('#username')||document.querySelector('input[name=username]');" +
                    "var p=document.querySelector('#password')||document.querySelector('input[name=password]');" +
                    "if(!u||!p)return false;u.value='" + DEMO_USERNAME + "';p.value='" + DEMO_PASSWORD + "';return true;})()");
            browser.evaluate("(function(){var f=document.querySelector('form');if(f)f.submit();return !!f;})()");
            waitFor(browser, "window.location.pathname !== '/login'");
            String afterLogin = browser.evaluate("window.location.pathname").asText("");
            assertTrue(!afterLogin.equals("/login"),
                    "[" + viewport + "] ログイン後に/loginから遷移すること（URL=" + afterLogin + "）");

            // ---- 2. 7画面の遷移・DOM待ち・エラーページ判定・撮影 ----
            int errorPages = 0;
            for (Map.Entry<String, String> entry : SCREENS) {
                String screen = entry.getKey();
                String selector = entry.getValue();
                browser.navigate(baseUrl + "/my/" + screen);
                boolean domReady = browser.waitFor(
                        "document.querySelector('" + selector + "') !== null", Duration.ofSeconds(30));
                assertTrue(domReady, "[" + viewport + "] " + screen + " のページ固有DOM(" + selector + ")が表示されること");
                // 統一エラーページ（error.htmlの.error-card）でないこと。403/500ページはassert fail
                JsonNode errorCard = browser.evaluate("document.querySelector('.error-card') !== null");
                if (errorCard.asBoolean(false)) {
                    errorPages++;
                }
                assertFalse(errorCard.asBoolean(false), "[" + viewport + "] " + screen + " がエラーページになっている");
                byte[] png = browser.screenshot();
                String fileName = viewport + "-my-" + screen + ".png";
                Path pngPath = evidenceDir.resolve(fileName);
                Files.write(pngPath, png);
                summary.put(viewport + "-my-" + screen + "-png", fileName);
                summary.put(viewport + "-my-" + screen + "-sha256", sha256(png));
            }
            assertions.add(viewport + ": 7画面DOM表示・エラーページ0（errorPages=" + errorPages + "）");

            // ---- 3. 実操作（desktopのみ。ページのfetchでセッション/Cookieを利用） ----
            if (withOperations) {
                runOperations(browser, baseUrl, seed, assertions);
            }

            // ---- 4. 本人BのPIIがDOMに現れない ----
            String bodyText = browser.evaluate("document.body ? document.body.innerText : ''").asText("");
            assertFalse(bodyText.contains(B_PII_NAME) || bodyText.contains(B_PII_PHONE),
                    "[" + viewport + "] 本人BのPIIがDOMに現れている");
            assertions.add(viewport + ": 本人BのPIIがDOMに現れない");

            // ---- 5. console error 0件 ----
            List<JsonNode> console = browser.consoleEvents();
            long consoleErrors = console.stream()
                    .filter(e -> "Runtime.consoleAPICalled".equals(e.path("method").asText())
                            && "error".equals(e.path("params").path("type").asText()))
                    .count();
            assertTrue(consoleErrors == 0,
                    "[" + viewport + "] console errorが存在する: " + consoleErrors);
            assertions.add(viewport + ": console error 0件");

            // ---- 6. network response 4xx/5xx 0件 ----
            List<JsonNode> responses = browser.networkResponses();
            long badResponses = responses.stream()
                    .map(r -> r.path("params").path("response").path("status").asInt(0))
                    .filter(s -> s >= 400)
                    .count();
            assertTrue(badResponses == 0,
                    "[" + viewport + "] network response 4xx/5xxが存在する: " + badResponses);
            assertions.add(viewport + ": network 4xx/5xx 0件");

            // ---- 7. 証跡（console） ----
            StringBuilder consoleLog = new StringBuilder();
            for (var event : console) {
                consoleLog.append(event.toString()).append('\n');
            }
            Files.writeString(evidenceDir.resolve(viewport + "-console.txt"), consoleLog.toString());
            summary.put(viewport + "-consoleCount", console.size());
        } finally {
            deleteRecursively(profile);
        }
    }

    /** desktopのみの実操作: プロフィール変更申請・給与再認証/明細一覧・経費作成/領収書・1on1申請・survey回答・勤怠既存導線。 */
    private void runOperations(CdpBrowser browser, String baseUrl, Seed seed, ArrayNode assertions) throws Exception {
        // CSRFヘッダーをCookieから組み立てるfetchヘルパー（common.jsのSES.csrfと同等の仕様）
        String csrfExpr = "((function(){var m=document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/);" +
                "return m?decodeURIComponent(m[1]):'';})())";
        String csrf = browser.evaluate(csrfExpr).asText("");

        // ---- 3.1 プロフィール変更申請（本人の変更申請一気通貫） ----
        JsonNode cr = browser.evaluate("(async function(){const t='" + csrf + "';" +
                "const r=await fetch('/api/my/change-requests',{method:'POST'," +
                "headers:{'Content-Type':'application/json','X-XSRF-TOKEN':t}," +
                "body:JSON.stringify({requestType:'profile.change',payload:{nearestStation:'ブラウザ実測駅'},reason:'T093実ブラウザ操作'})});" +
                "return await r.text();})()");
        assertTrue(cr.asText("").contains("\"code\":200"),
                "profile変更申請が200でない: " + truncate(cr.asText("")));
        String crList = browser.evaluate("(async function(){" +
                "const r=await fetch('/api/my/change-requests?current=1&size=100');return await r.text();})()").asText("");
        assertTrue(crList.contains("ブラウザ実測駅"), "本人の変更申請一覧に作成内容が反映される");
        assertFalse(crList.contains(B_PII_NAME) || crList.contains(B_PII_PHONE), "変更申請一覧に本人BのPIIが現れない");
        assertions.add("desktop: profile変更申請の作成と一覧反映・B PIIなし");

        // ---- 3.2 給与: 再認証と本人明細一覧（未連携は金額0でなくlinked=false。design §6.1） ----
        JsonNode reauth = browser.evaluate("(async function(){const t='" + csrf + "';" +
                "const r=await fetch('/api/my/payroll/reauthenticate',{method:'POST'," +
                "headers:{'Content-Type':'application/json','X-XSRF-TOKEN':t}," +
                "body:JSON.stringify({password:'" + DEMO_PASSWORD + "'})});return await r.text();})()");
        assertTrue(reauth.asText("").contains("\"code\":200"),
                "payroll再認証が200でない: " + truncate(reauth.asText("")));
        String statements = browser.evaluate("(async function(){" +
                "const r=await fetch('/api/my/payroll/statements?year=2026&month=8&type=salary');return await r.text();})()").asText("");
        assertTrue(statements.contains("\"code\":200") && statements.contains("\"linked\":false"),
                "給与明細一覧が200（未連携レスポンス）でない: " + truncate(statements));
        assertFalse(statements.contains("grossAmount") || statements.contains("netAmount"),
                "給与一覧に金額が露出しない（R2.2）");
        assertions.add("desktop: payroll再認証200・明細一覧200・金額非露出");

        // ---- 3.3 経費: 作成と領収書添付 ----
        JsonNode expense = browser.evaluate("(async function(){const t='" + csrf + "';" +
                "const r=await fetch('/api/my/expenses',{method:'POST'," +
                "headers:{'Content-Type':'application/json','X-XSRF-TOKEN':t}," +
                "body:JSON.stringify({expenseDate:'2026-08-10',category:'交通費',amount:980,description:'T093ブラウザ実測経費'})});" +
                "return await r.text();})()");
        assertTrue(expense.asText("").contains("\"code\":200"),
                "経費作成が200でない: " + truncate(expense.asText("")));
        long expenseId = jsonLong(expense.asText(""), "/data/id");
        String expenseList = browser.evaluate("(async function(){" +
                "const r=await fetch('/api/my/expenses?current=1&size=100');return await r.text();})()").asText("");
        assertTrue(expenseList.contains("T093ブラウザ実測経費"), "経費一覧に作成内容が反映される");
        assertFalse(expenseList.contains(B_PII_NAME) || expenseList.contains(B_PII_PHONE), "経費一覧に本人BのPIIが現れない");
        assertions.add("desktop: expense作成・一覧反映・B PIIなし");

        // 領収書アップロード（FormData。scan=CLEANで登録される）
        JsonNode receipt = browser.evaluate("(async function(){const t='" + csrf + "';" +
                "const fd=new FormData();fd.append('file',new Blob(['%PDF-1.4 browser demo receipt'],{type:'application/pdf'}),'receipt.pdf');" +
                "const r=await fetch('/api/my/expenses/" + expenseId + "/receipt',{method:'POST',headers:{'X-XSRF-TOKEN':t},body:fd});" +
                "return await r.text();})()");
        assertTrue(receipt.asText("").contains("\"code\":200"),
                "領収書添付が200でない: " + truncate(receipt.asText("")));
        assertions.add("desktop: expense領収書添付200（CLEAN登録）");

        // ---- 3.4 1on1申請（明日以降の候補日。decision table §6.1） ----
        String tomorrow = LocalDate.now().plusDays(1).toString();
        String dayAfter = LocalDate.now().plusDays(2).toString();
        JsonNode oneOnOne = browser.evaluate("(async function(){const t='" + csrf + "';" +
                "const r=await fetch('/api/my/one-on-ones',{method:'POST'," +
                "headers:{'Content-Type':'application/json','X-XSRF-TOKEN':t}," +
                "body:JSON.stringify({counterpartUserId:" + seed.salesUserId() + ",candidateDates:['" + tomorrow + "','" + dayAfter + "']})});" +
                "return await r.text();})()");
        assertTrue(oneOnOne.asText("").contains("\"code\":200"),
                "1on1申請が200でない: " + truncate(oneOnOne.asText("")));
        String oneOnOneList = browser.evaluate("(async function(){" +
                "const r=await fetch('/api/my/one-on-ones?current=1&size=50');return await r.text();})()").asText("");
        assertTrue(oneOnOneList.contains(tomorrow), "1on1一覧に申請した候補日が反映される");
        assertFalse(oneOnOneList.contains(B_PII_NAME) || oneOnOneList.contains(B_PII_PHONE), "1on1一覧に本人BのPIIが現れない");
        assertions.add("desktop: 1on1申請と一覧反映・B PIIなし");

        // ---- 3.5 survey回答 ----
        JsonNode answers = browser.evaluate("(async function(){const t='" + csrf + "';" +
                "const r=await fetch('/api/my/surveys/" + seed.surveyCampaignId() + "/answers',{method:'POST'," +
                "headers:{'Content-Type':'application/json','X-XSRF-TOKEN':t}," +
                "body:JSON.stringify({consent:true,answers:[{questionKey:'q1',answerValue:4,comment:null,commentVisibility:'PUBLIC'},{questionKey:'q2',answerValue:2,comment:'ブラウザ実測コメント',commentVisibility:'CONFIDENTIAL'}]})});" +
                "return await r.text();})()");
        assertTrue(answers.asText("").contains("\"code\":200"),
                "survey回答が200でない: " + truncate(answers.asText("")));
        assertions.add("desktop: survey回答200");

        // ---- 3.6 勤怠既存導線（my timesheet API） ----
        JsonNode timesheetText = browser.evaluate("(async function(){" +
                "const r=await fetch('/api/my/timesheet?month=2026-08');return await r.text();})()");
        JsonNode timesheet = MAPPER.readTree(timesheetText.asText("{}"));
        assertTrue(timesheet.path("code").asInt(-1) == 200,
                "my timesheet APIが200でない: " + truncate(timesheetText.asText("")));
        assertions.add("desktop: my timesheet既存導線200");

        // ---- 3.7 本人APIレスポンスにBのPIIが現れない（profile） ----
        String profile = browser.evaluate("(async function(){" +
                "const r=await fetch('/api/my/profile');return await r.text();})()").asText("");
        assertTrue(profile.contains("要員ポータル デモ太郎"), "本人プロフィールが取得できる");
        assertFalse(profile.contains(B_PII_NAME) || profile.contains(B_PII_PHONE), "profileに本人BのPIIが現れない");
        assertions.add("desktop: profileにB PIIなし");
    }

    // ============================================================
    // ヘルパー
    // ============================================================

    private void waitFor(CdpBrowser browser, String jsExpression) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(40).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (browser.evaluate(jsExpression).asBoolean(false)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("waitFor timeout: " + jsExpression);
    }

    private long jsonLong(String json, String pointer) throws Exception {
        JsonNode node = MAPPER.readTree(json).at(pointer);
        if (node.isMissingNode() || node.isNull()) {
            throw new AssertionError("JSON path not found: " + pointer + " body=" + truncate(json));
        }
        return node.asLong();
    }

    private String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() > 300 ? value.substring(0, 300) + "..." : value;
    }

    private String sha256(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) return;
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        } catch (Exception ignored) {
        }
    }
}
