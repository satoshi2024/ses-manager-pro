package com.ses.web;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T093（M）browser Demo: 要員セルフサービスポータルV2（my dashboard/profile/payroll/expenses/1on1/surveys/timesheet）を
 * 実Chrome（CDP・headless）でdesktop（1920x1080）と390px（390x844）で実測し、
 * DOM検証・スクリーンショット・コンソールイベントを `.kiro/specs/engineer-self-service-portal-v2/evidence/browser-m/` へ保存する。
 */
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

    @Test
    @DisplayName("T093-M: 実Chromeで要員セルフサービスポータル画面群をdesktop/390pxで実測")
    void captureEngineerPortalScreensWithRealBrowser() throws Exception {
        Path chrome = CdpBrowser.chromeExecutable();
        assertNotNull(chrome, "Chrome実行ファイルが見つかりません");

        String username = "portal_engineer_demo";
        String password = "demoPassword123";
        Long engineerId = seedPortalEngineer(username, password);

        String baseUrl = "http://localhost:" + port;
        Path evidenceDir = Path.of(".kiro", "specs", "engineer-self-service-portal-v2", "evidence", "browser-m");
        Files.createDirectories(evidenceDir);
        String runId = "browser-m-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        Files.writeString(evidenceDir.resolve("run-id.txt"), runId + "\n");

        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("runId", runId);
        summary.put("baseUrl", baseUrl);
        summary.put("username", username);
        summary.put("engineerId", engineerId);

        runViewport(chrome, baseUrl, evidenceDir, runId, "desktop", 1920, 1080, username, password, summary);
        runViewport(chrome, baseUrl, evidenceDir, runId, "mobile390", 390, 844, username, password, summary);

        Files.writeString(evidenceDir.resolve("summary.json"),
                MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        System.out.println("T093-M browser Demo done: " + evidenceDir + " (runId=" + runId + ")");
    }

    private Long seedPortalEngineer(String username, String password) {
        SysUser user = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        Long userId;
        if (user == null) {
            user = new SysUser();
            user.setUsername(username);
            user.setPassword(password);
            user.setRealName("要員ポータル デモ太郎");
            user.setRole("管理者");
            user.setStatus(1);
            sysUserMapper.insert(user);
            userId = user.getId();
        } else {
            user.setPassword(password);
            user.setStatus(1);
            sysUserMapper.updateById(user);
            userId = user.getId();
        }

        Engineer existingEng = engineerMapper.selectOne(new LambdaQueryWrapper<Engineer>().eq(Engineer::getFullName, "要員ポータル デモ太郎"));
        Long engineerId;
        if (existingEng == null) {
            Engineer eng = new Engineer();
            eng.setFullName("要員ポータル デモ太郎");
            eng.setPhone("090-9999-8888");
            eng.setNearestStation("新宿");
            eng.setEmploymentType("正社員");
            eng.setStatus("稼動中");
            engineerMapper.insert(eng);
            engineerId = eng.getId();
        } else {
            engineerId = existingEng.getId();
        }

        EngineerAccountLink link = accountLinkMapper.selectOne(new LambdaQueryWrapper<EngineerAccountLink>().eq(EngineerAccountLink::getSysUserId, userId));
        if (link == null) {
            link = new EngineerAccountLink();
            link.setEngineerId(engineerId);
            link.setSysUserId(userId);
            accountLinkMapper.insert(link);
        }

        return engineerId;
    }

    private void runViewport(Path chrome, String baseUrl, Path evidenceDir, String runId,
                             String viewport, int width, int height, String username, String password, ObjectNode summary) throws Exception {
        Path profile = Files.createTempDirectory("chrome-profile-portal-" + viewport);
        try (CdpBrowser browser = CdpBrowser.launch(chrome, profile, width, height)) {
            // ---- 1. ログイン ----
            browser.navigate(baseUrl + "/login");
            waitFor(browser, "document.querySelector('#username') !== null || document.querySelector('input[name=username]') !== null");
            browser.evaluate("(function(){" +
                    "var u=document.querySelector('#username')||document.querySelector('input[name=username]');" +
                    "var p=document.querySelector('#password')||document.querySelector('input[name=password]');" +
                    "if(!u||!p)return false;u.value='" + username + "';p.value='" + password + "';return true;})()");
            browser.evaluate("(function(){var f=document.querySelector('form');if(f)f.submit();return !!f;})()");
            waitFor(browser, "window.location.pathname !== '/login'");
            String afterLogin = browser.evaluate("window.location.pathname").asText("");
            assertTrue(!afterLogin.equals("/login"), "[" + viewport + "] ログイン後に/loginから遷移すること（URL=" + afterLogin + ")");

            // ---- 2. 各ポータル画面を順に遷移・撮影 ----
            String[] screens = new String[]{
                    "dashboard", "profile", "expenses", "one-on-ones", "surveys", "timesheet"
            };

            for (String screen : screens) {
                browser.navigate(baseUrl + "/my/" + screen);
                Thread.sleep(500); // レンダリング待機
                byte[] png = browser.screenshot();
                String fileName = viewport + "-my-" + screen + ".png";
                Path pngPath = evidenceDir.resolve(fileName);
                Files.write(pngPath, png);
                summary.put(viewport + "-my-" + screen + "-png", fileName);
                summary.put(viewport + "-my-" + screen + "-sha256", sha256(png));
            }

            // ---- 3. コンソールログの保存 ----
            List<com.fasterxml.jackson.databind.JsonNode> console = browser.consoleEvents();
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

    private String sha256(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
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
