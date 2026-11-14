package com.ses.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 実ChromeのIndexedDB・cookieで、NF04 W3-09（P1-N4-1 方案A、P2-N4-2、P2-N4-3）の境界を検証する。
 *
 * <ul>
 *   <li>OIDC session（server応答 {@code restoreAvailable=false}）: 復元入口・パスワード欄を隠し、パスワードを送らない。
 *       現在documentに未送信の下書きがある間だけ離脱前に確認する。</li>
 *   <li>ローカルパスワードsession: 従来どおり復元入口を出し、離脱確認は出さない。撤回epoch cookieの期限を延長する。</li>
 *   <li>IndexedDB v1→v2の実upgrade、skill/career競合の投影、応答喪失後の同一ID再送とledger replay。</li>
 * </ul>
 *
 * <p>serverはloopbackのJDK HttpServer stubである（実Spring認証の証明ではない。OIDCの拒否は
 * {@code PwaRestoreOidcSessionIntegrationTest} 等のSpring testが担う）。</p>
 */
@Tag("browser")
class PwaQueueRestoreAvailabilityBrowserTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Set<String> STATIC_ALLOWLIST = Set.of("/js/pwa-queue.js", "/offline.html");
    private static final Duration WAIT = Duration.ofSeconds(20);

    private static final String READY = "(()=>{const q=window.SES&&SES.pwaQueue;"
            + "if(!q||typeof q.whenReady!=='function')return false;const r=q.whenReady();"
            + "return r&&typeof r.then==='function'?r.then(()=>q.getScope()==='scope-A'):false})()";
    private static final String READ_QUEUE = "(async()=>{const db=await new Promise((ok,no)=>{"
            + "const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});"
            + "const rows=await new Promise((ok,no)=>{const q=db.transaction('queue').objectStore('queue').getAll();"
            + "q.onsuccess=()=>ok(q.result);q.onerror=()=>no(q.error)});db.close();return JSON.stringify(rows)})()";
    private static final String BEFORE_UNLOAD_PREVENTED = "(()=>{const e=new Event('beforeunload',{cancelable:true});"
            + "window.dispatchEvent(e);return e.defaultPrevented})()";
    /** 現在scope・現在epochのPENDING recordを、現在documentの外で作られたもの（ロック中）として直接書き込む。 */
    private static final String INSERT_LOCKED_DRAFT = "(async()=>{const db=await new Promise((ok,no)=>{"
            + "const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});"
            + "await new Promise((ok,no)=>{const tx=db.transaction('queue','readwrite');tx.objectStore('queue').put({"
            + "clientRequestId:'locked-draft-from-previous-document',userScope:SES.pwaQueue.getScope(),"
            + "revocationEpoch:localStorage.getItem('ses_pwa_revocation_epoch'),status:'PENDING',screen:'attendance',"
            + "month:'2026-10',method:'POST',path:'/api/my/pwa/attendance/daily',resourceKey:'attendance:2026-10',"
            + "baseVersion:1,payloadHash:'locked-hash',createdAt:Date.now(),expiresAt:Date.now()+86400000,"
            + "payload:{workDate:'2026-10-02',clockIn:'09:00',clockOut:'18:00',breaks:[],breakMinutes:60,"
            + "workType:'出勤',workplaceType:'客先',remarks:'locked'}});tx.oncomplete=ok;tx.onerror=()=>no(tx.error)});"
            + "db.close();return true})()";

    @Test
    void oidcSessionでは復元入口と再認証欄を隠し未送信の下書きがある間だけ離脱前に確認する() throws Exception {
        Stub stub = new Stub();
        stub.restoreAvailable.set(false);
        try (Harness harness = Harness.start(stub, "ses-nf04-oidc-")) {
            CdpBrowser browser = harness.browser;
            browser.navigate(harness.base + "/test.html");
            assertTrue(browser.waitForStrict(READY, WAIT), "queueのscope bootstrapが完了する");
            assertEquals("false", browser.evaluateStrict("String(SES.pwaQueue.restoreAvailability())").asText(),
                    "server応答のrestoreAvailable=falseを保持する");

            browser.evaluateStrict("(async()=>{window.__setOnline(false);await SES.pwaQueue.request("
                    + attendance("2026-10-01", 0, "oidc-unsent") + ");return true})()");
            assertTrue(browser.waitForStrict("document.getElementById('pwa-queue-status').textContent"
                    + ".includes('離れると復元できません')", WAIT), "未送信の下書きを離れると失うことを表示する");
            assertTrue(browser.evaluateStrict(BEFORE_UNLOAD_PREVENTED).asBoolean(), "未送信の間は離脱前に確認する");
            assertEquals(1, browser.evaluateStrict("SES.pwaQueue.draftLossSummary().unsent").asInt(),
                    "logout確認へ未送信件数を渡す");

            browser.evaluateStrict("(async()=>{window.__setOnline(true);await SES.pwaQueue.flush();return true})()");
            assertTrue(browser.waitForStrict("SES.pwaQueue.draftLossSummary().unsent===0", WAIT), "送信後は未送信0件");
            assertFalse(browser.evaluateStrict(BEFORE_UNLOAD_PREVENTED).asBoolean(), "送信が終われば離脱確認を外す");
            assertEquals(1, stub.attendanceLog.size(), "未送信の下書きはonline復帰で1回だけ送る");

            assertTrue(browser.evaluateStrict(INSERT_LOCKED_DRAFT).asBoolean());
            browser.evaluateStrict("SES.pwaQueue.resume().then(()=>true)");
            assertTrue(browser.waitForStrict("SES.pwaQueue.draftLossSummary().locked===1", WAIT),
                    "前documentの下書きをロック中として数える");
            JsonNode panel = JSON.readTree(browser.evaluateStrict("JSON.stringify({"
                    + "panelHidden:document.getElementById('pwa-queue-panel').hidden,"
                    + "linkHidden:document.getElementById('pwa-restore-link').hidden,"
                    + "status:document.getElementById('pwa-queue-status').textContent})").asText());
            assertFalse(panel.path("panelHidden").asBoolean(), "ロック中の下書きがあることは表示する");
            assertTrue(panel.path("linkHidden").asBoolean(), "OIDC sessionでは「下書きを復元」入口を出さない");
            assertTrue(panel.path("status").asText().contains("復元できません"), panel.path("status").asText());
            assertFalse(browser.evaluateStrict(BEFORE_UNLOAD_PREVENTED).asBoolean(),
                    "ロック中だけ（現在documentの未送信なし）では離脱確認を出さない");

            browser.navigate(harness.base + "/offline.html");
            assertTrue(browser.waitForStrict("!!(document.getElementById('offline-restore-unavailable')"
                    + "&&!document.getElementById('offline-restore-unavailable').hidden)", WAIT),
                    "offline.htmlは復元できないことを表示する");
            JsonNode offline = JSON.readTree(browser.evaluateStrict("JSON.stringify({"
                    + "authHidden:document.getElementById('offline-restore-auth').hidden,"
                    + "passwordDisabled:document.getElementById('offline-restore-password').disabled,"
                    + "reauthDisabled:document.getElementById('offline-reauth').disabled,"
                    + "note:document.getElementById('offline-restore-unavailable').textContent})").asText());
            assertTrue(offline.path("authHidden").asBoolean(), "パスワード欄を隠す");
            assertTrue(offline.path("passwordDisabled").asBoolean() && offline.path("reauthDisabled").asBoolean());
            assertTrue(offline.path("note").asText().contains("この下書きは復元できません"));
            assertEquals("unavailable", browser.evaluateStrict("SES.pwaQueue.prepareRestore('idp-password')"
                    + ".then(()=>'resolved',e=>e&&e.restoreUnavailable?'unavailable':'other:'+(e&&e.message))").asText());
            assertEquals("unavailable", browser.evaluateStrict("SES.pwaQueue.confirmRestore()"
                    + ".then(()=>'resolved',e=>e&&e.restoreUnavailable?'unavailable':'other:'+(e&&e.message))").asText());
            assertEquals(0, stub.reauthCalls.get(), "OIDC sessionではパスワードを再認証APIへ送らない");
            assertEquals(0, stub.confirmCalls.get());
        }
    }

    @Test
    void ローカルパスワードsessionは復元入口を維持し撤回epochのcookie期限を同じ値で延長する() throws Exception {
        Stub stub = new Stub();
        try (Harness harness = Harness.start(stub, "ses-nf04-local-")) {
            CdpBrowser browser = harness.browser;
            browser.navigate(harness.base + "/test.html");
            assertTrue(browser.waitForStrict(READY, WAIT));
            assertEquals("true", browser.evaluateStrict("String(SES.pwaQueue.restoreAvailability())").asText());

            browser.evaluateStrict("(async()=>{window.__setOnline(false);await SES.pwaQueue.request("
                    + attendance("2026-10-01", 0, "local-unsent") + ");return true})()");
            assertTrue(browser.waitForStrict("document.getElementById('pwa-queue-status').textContent.includes('未送信')", WAIT));
            assertFalse(browser.evaluateStrict("document.getElementById('pwa-queue-status').textContent"
                    + ".includes('離れると復元できません')").asBoolean(), "復元できるsessionの表示は従来どおり");
            assertFalse(browser.evaluateStrict(BEFORE_UNLOAD_PREVENTED).asBoolean(),
                    "ローカルパスワードsessionは画面移動後も復元できるため離脱確認を出さない");
            browser.evaluateStrict("(async()=>{window.__setOnline(true);await SES.pwaQueue.flush();return true})()");
            assertTrue(browser.waitForStrict("SES.pwaQueue.draftLossSummary().unsent===0", WAIT));

            assertTrue(browser.evaluateStrict(INSERT_LOCKED_DRAFT).asBoolean());
            browser.evaluateStrict("SES.pwaQueue.resume().then(()=>true)");
            assertTrue(browser.waitForStrict("SES.pwaQueue.draftLossSummary().locked===1"
                    + "&&!document.getElementById('pwa-restore-link').hidden", WAIT),
                    "ローカルパスワードsessionでは「下書きを復元」入口を出す");
            assertTrue(browser.evaluateStrict("document.getElementById('pwa-queue-status').textContent"
                    + ".includes('再認証後に明示的に復元できます')").asBoolean());

            // P2-N4-2: scriptで書いたcookieの期限（Safari ITPでは7日上限）をonline bootstrapのたびに延ばす。
            String epoch = browser.evaluateStrict("localStorage.getItem('ses_pwa_revocation_epoch')").asText();
            assertFalse(epoch.isBlank());
            assertTrue(browser.evaluateStrict("document.cookie='ses_pwa_revocation_epoch='"
                    + "+encodeURIComponent(localStorage.getItem('ses_pwa_revocation_epoch'))"
                    + "+'; Max-Age=120; Path=/; SameSite=Strict';true").asBoolean());
            double nowSeconds = System.currentTimeMillis() / 1000.0;
            JsonNode shortCookie = epochCookie(browser, harness.base);
            assertEquals(epoch, java.net.URLDecoder.decode(shortCookie.path("value").asText(), StandardCharsets.UTF_8));
            assertTrue(shortCookie.path("expires").asDouble() < nowSeconds + 600, "短い期限のcookieを用意する");
            browser.evaluateStrict("SES.pwaQueue.resume().then(()=>true)");
            JsonNode refreshed = epochCookie(browser, harness.base);
            assertEquals(epoch, java.net.URLDecoder.decode(refreshed.path("value").asText(), StandardCharsets.UTF_8),
                    "撤回epochの値は変えない");
            assertTrue(refreshed.path("expires").asDouble() > nowSeconds + 300L * 24 * 60 * 60,
                    "online bootstrapでcookieの期限を延ばす: " + refreshed);
            assertEquals(epoch, browser.evaluateStrict("localStorage.getItem('ses_pwa_revocation_epoch')").asText());

            browser.navigate(harness.base + "/offline.html");
            assertTrue(browser.waitForStrict("!!(document.getElementById('offline-reauth')"
                    + "&&!document.getElementById('offline-reauth').disabled"
                    + "&&!document.getElementById('offline-restore-auth').hidden"
                    + "&&document.getElementById('offline-restore-unavailable').hidden)", WAIT),
                    "ローカルパスワードsessionでは従来どおりパスワード欄で再認証できる");
        }
    }

    @Test
    void IndexedDBのv1からv2への実upgradeで過大conflictを除去しepoch未設定recordを隔離する() throws Exception {
        Stub stub = new Stub();
        try (Harness harness = Harness.start(stub, "ses-nf04-upgrade-")) {
            CdpBrowser browser = harness.browser;
            browser.navigate(harness.base + "/upgrade.html");
            assertTrue(browser.waitForStrict("!!window.__v1Ready", WAIT));
            assertTrue(browser.evaluateStrict("window.__v1Ready.then(v=>v===1)").asBoolean(), "v1のDBを作成する");
            assertTrue(browser.evaluateStrict("(async()=>{await new Promise((ok,no)=>{const s=document.createElement('script');"
                    + "s.src='/js/pwa-queue.js';s.onload=ok;s.onerror=()=>no(new Error('pwa-queue.jsを読めません'));"
                    + "document.body.appendChild(s)});return true})()").asBoolean());
            assertTrue(browser.waitForStrict(READY, WAIT), "v2 upgrade後にqueueが初期化される");

            JsonNode db = JSON.readTree(browser.evaluateStrict("(async()=>{const db=await new Promise((ok,no)=>{"
                    + "const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});"
                    + "const version=db.version;const stores=Array.from(db.objectStoreNames);db.close();"
                    + "return JSON.stringify({version,stores})})()").asText());
            assertEquals(2, db.path("version").asInt());
            assertTrue(db.path("stores").toString().contains("meta"), db.toString());
            String rowsJson = browser.evaluateStrict(READ_QUEUE).asText();
            assertFalse(rowsJson.contains("v1-secret-contract"), "旧conflictの第三者情報を残さない");
            assertFalse(rowsJson.contains("x".repeat(1000)), "過大payloadを残さない");
            Map<String, JsonNode> rows = byId(rowsJson);
            JsonNode conflict = rows.get("v1-conflict");
            assertEquals("ERROR", conflict.path("status").asText());
            assertTrue(conflict.path("payload").isNull() && conflict.path("payloadHash").isNull());
            assertFalse(conflict.has("conflict"), "conflict属性自体を削除する");
            assertEquals("legacy-revoked", conflict.path("revocationEpoch").asText());
            JsonNode oversized = rows.get("v1-oversized");
            assertEquals("ERROR", oversized.path("status").asText());
            assertTrue(oversized.path("payload").isNull());
            JsonNode small = rows.get("v1-small");
            assertEquals("PENDING", small.path("status").asText(), "小さい通常recordの状態は変えない");
            assertEquals("2026-10-03", small.path("payload").path("workDate").asText());
            assertEquals("legacy-revoked", small.path("revocationEpoch").asText(),
                    "epoch契約前のrecordには撤回済みepochを保存する");
            assertEquals(0, browser.evaluateStrict("SES.pwaQueue.draftLossSummary().locked").asInt(),
                    "撤回済みepochのrecordを復元可能なロック中として数えない（P3-N4-4）");
            assertEquals(0, browser.evaluateStrict("SES.pwaQueue.getOfflineDrafts().then(x=>x.length)").asInt());
        }
    }

    @Test
    void skillとcareerの競合投影は入れ子の第三者項目と契約情報をIndexedDBへ保存しない() throws Exception {
        Stub stub = new Stub();
        try (Harness harness = Harness.start(stub, "ses-nf04-projection-")) {
            CdpBrowser browser = harness.browser;
            browser.navigate(harness.base + "/test.html");
            assertTrue(browser.waitForStrict(READY, WAIT));

            stub.nextChangeRequestConflict.set("{\"code\":409,\"data\":{\"type\":\"CONFLICT\",\"serverVersion\":5,"
                    + "\"server\":{\"skills\":[{\"skillId\":11,\"proficiency\":\"中級\",\"experienceYears\":2,"
                    + "\"skillSecretNote\":\"secret-skill-note\"}],\"primarySalesUserName\":\"営業秘密氏名\"},"
                    + "\"fields\":[{\"name\":\"skills\",\"serverValue\":[{\"skillId\":11,\"skillSecretNote\":\"secret-skill-note\"}],"
                    + "\"clientValue\":[{\"skillId\":11}]},{\"name\":\"primarySalesUserName\",\"serverValue\":\"営業秘密氏名\"}]}}");
            browser.evaluateStrict("(async()=>JSON.stringify(await SES.pwaQueue.request({screen:'change-request',month:null,"
                    + "method:'POST',path:'/api/my/pwa/change-requests/drafts',baseVersion:0,resourceKey:'change-request:skill',"
                    + "payload:{requestType:'skill.change',reason:'skill-conflict',payload:{skills:[{skillId:11,"
                    + "proficiency:'上級',experienceYears:3}]}}})))()");
            assertTrue(browser.waitForStrict("!!document.querySelector('[data-pwa-refresh]')", WAIT));
            String skillConflict = browser.evaluateStrict(READ_QUEUE).asText();
            assertNoThirdPartyData(skillConflict);
            assertTrue(skillConflict.contains("proficiency"), "requestTypeの対象項目は残す");

            browser.evaluateStrict("document.querySelector('[data-pwa-refresh]').click();true");
            assertTrue(browser.waitForStrict(READ_QUEUE.replace("return JSON.stringify(rows)",
                    "return rows.some(x=>x.conflict&&x.conflict.serverRefreshAt)"), WAIT), "server版を再取得する");
            String skillRefreshed = browser.evaluateStrict(READ_QUEUE).asText();
            assertNoThirdPartyData(skillRefreshed);
            JsonNode skillRecord = onlyConflict(skillRefreshed);
            JsonNode skillField = field(skillRecord, "skills");
            assertEquals(11, skillField.path("serverValue").path(0).path("skillId").asInt());
            assertEquals(1, skillField.path("clientValue").size(), "差分のclient値は申請内容（payload.payload）と比べる");
            assertEquals(3, skillField.path("clientValue").path(0).path("experienceYears").asInt());

            browser.evaluateStrict("document.querySelector('[data-pwa-discard]').click();true");
            assertTrue(browser.waitForStrict("!document.querySelector('[data-pwa-refresh]')", WAIT));

            stub.nextChangeRequestConflict.set("{\"code\":409,\"data\":{\"type\":\"CONFLICT\",\"serverVersion\":5,"
                    + "\"server\":{\"careers\":[{\"projectName\":\"PWA案件\",\"role\":\"PG\","
                    + "\"careerSecretNote\":\"secret-career-note\"}],\"contracts\":[{\"projectName\":\"機密案件名\","
                    + "\"contractName\":\"secret-contract\"}]},\"fields\":[{\"name\":\"contracts\","
                    + "\"serverValue\":[{\"projectName\":\"機密案件名\"}]}]}}");
            browser.evaluateStrict("(async()=>JSON.stringify(await SES.pwaQueue.request({screen:'change-request',month:null,"
                    + "method:'POST',path:'/api/my/pwa/change-requests/drafts',baseVersion:0,resourceKey:'change-request:career',"
                    + "payload:{requestType:'career.change',reason:'career-conflict',payload:{careers:[{periodFrom:'2025-04',"
                    + "periodTo:'2026-03',projectName:'PWA案件',clientIndustry:'製造',role:'PG',description:'開発',"
                    + "techStack:'Java',teamSize:5}]}}})))()");
            assertTrue(browser.waitForStrict("!!document.querySelector('[data-pwa-refresh]')", WAIT));
            assertNoThirdPartyData(browser.evaluateStrict(READ_QUEUE).asText());
            browser.evaluateStrict("document.querySelector('[data-pwa-refresh]').click();true");
            assertTrue(browser.waitForStrict(READ_QUEUE.replace("return JSON.stringify(rows)",
                    "return rows.some(x=>x.conflict&&x.conflict.serverRefreshAt)"), WAIT));
            String careerRefreshed = browser.evaluateStrict(READ_QUEUE).asText();
            assertNoThirdPartyData(careerRefreshed);
            JsonNode careerField = field(onlyConflict(careerRefreshed), "careers");
            assertEquals("PWA案件", careerField.path("serverValue").path(0).path("projectName").asText(),
                    "careers[]内のprojectNameだけを投影する");
            assertEquals("PWA案件", careerField.path("clientValue").path(0).path("projectName").asText());
        }
    }

    @Test
    void 応答喪失後の同一ID再送はledger_replayで後続baseVersionを進め再起動後も二重登録しない() throws Exception {
        Stub stub = new Stub();
        try (Harness harness = Harness.start(stub, "ses-nf04-replay-")) {
            CdpBrowser browser = harness.browser;
            browser.navigate(harness.base + "/test.html");
            assertTrue(browser.waitForStrict(READY, WAIT));
            JsonNode ids = JSON.readTree(browser.evaluateStrict("(async()=>{window.__setOnline(false);const ids=[];"
                    + "for(let d=1;d<=3;d++){const r=await SES.pwaQueue.request(" + attendance("2026-10-0'+d+'", 0, "replay")
                    + ");ids.push(r.clientRequestId);await new Promise(ok=>setTimeout(ok,5))}return JSON.stringify(ids)})()")
                    .asText());
            assertEquals(3, ids.size());
            String first = ids.path(0).asText();

            // serverがcommitした後、clientだけが応答を失う（TypeError）状況を1回だけ作る。
            assertTrue(browser.evaluateStrict("window.__nativeFetch=window.fetch.bind(window);window.__dropOnce=true;"
                    + "window.fetch=async(input,init)=>{const response=await window.__nativeFetch(input,init);"
                    + "if(window.__dropOnce&&String(input).includes('/api/my/pwa/attendance/daily')){window.__dropOnce=false;"
                    + "await response.text();throw new TypeError('response lost after server commit')}return response};true")
                    .asBoolean());
            browser.evaluateStrict("(async()=>{window.__setOnline(true);await SES.pwaQueue.flush();return true})()");
            assertEquals(List.of("commit:" + first + ":0"), new ArrayList<>(stub.attendanceLog),
                    "応答を失った後、同じaggregateの後続を旧baseVersionで送らない");
            Map<String, JsonNode> pending = byId(browser.evaluateStrict(READ_QUEUE).asText());
            assertEquals(3, pending.size());
            pending.values().forEach(row -> assertEquals("PENDING", row.path("status").asText()));
            assertEquals(0, pending.get(first).path("baseVersion").asInt());

            // 再起動相当: 新documentでは前documentの下書きはロックされ、明示復元後に同じIDで再送する。
            browser.navigate(harness.base + "/test.html");
            assertTrue(browser.waitForStrict(READY, WAIT));
            assertTrue(browser.waitForStrict("SES.pwaQueue.draftLossSummary().locked===3", WAIT));
            assertTrue(browser.evaluateStrict("SES.pwaQueue.prepareRestore('local-password').then(()=>true)").asBoolean());
            assertTrue(browser.evaluateStrict("SES.pwaQueue.confirmRestore().then(()=>true)").asBoolean());
            assertTrue(browser.waitForStrict(READ_QUEUE.replace("return JSON.stringify(rows)", "return rows.length===0"),
                    WAIT), "replayと後続の送信が終わる");
            assertEquals(List.of("commit:" + first + ":0", "replay:" + first + ":0",
                    "commit:" + ids.path(1).asText() + ":1", "commit:" + ids.path(2).asText() + ":2"),
                    new ArrayList<>(stub.attendanceLog), "同一IDはledger replayで1件扱い、後続は進んだversionで送る");
            assertEquals(3, stub.committedVersion(), "serverの登録は3件だけ");
            assertEquals(0, stub.staleConflicts.get(), "自己生成の409を起こさない");
            assertEquals(1, stub.reauthCalls.get());
        }
    }

    private static String attendance(String workDate, int baseVersion, String remarks) {
        return "{screen:'attendance',month:'2026-10',method:'POST',path:'/api/my/pwa/attendance/daily',baseVersion:"
                + baseVersion + ",resourceKey:'attendance:2026-10',payload:{workDate:'" + workDate
                + "',clockIn:'09:00',clockOut:'18:00',breaks:[],breakMinutes:60,workType:'出勤',workplaceType:'客先',remarks:'"
                + remarks + "'}}";
    }

    private static void assertNoThirdPartyData(String stored) {
        for (String secret : List.of("secret-skill-note", "secret-career-note", "secret-contract", "営業秘密氏名",
                "機密案件名", "primarySalesUserName", "primarySalesUserId", "contracts")) {
            assertFalse(stored.contains(secret), "IndexedDBへ保存しない: " + secret);
        }
    }

    private static JsonNode onlyConflict(String rowsJson) throws Exception {
        List<JsonNode> conflicts = new ArrayList<>();
        for (JsonNode row : JSON.readTree(rowsJson)) {
            if ("CONFLICT".equals(row.path("status").asText())) conflicts.add(row);
        }
        assertEquals(1, conflicts.size(), rowsJson);
        return conflicts.get(0);
    }

    private static JsonNode field(JsonNode record, String name) {
        for (JsonNode field : record.path("conflict").path("fields")) {
            if (name.equals(field.path("name").asText())) return field;
        }
        throw new AssertionError("差分項目がありません: " + name + " / " + record);
    }

    private static Map<String, JsonNode> byId(String rowsJson) throws Exception {
        Map<String, JsonNode> rows = new java.util.HashMap<>();
        for (JsonNode row : JSON.readTree(rowsJson)) rows.put(row.path("clientRequestId").asText(), row);
        return rows;
    }

    private static JsonNode epochCookie(CdpBrowser browser, String base) throws Exception {
        JsonNode cookies = browser.send("Network.getCookies", Map.of("urls", List.of(base + "/"))).path("cookies");
        for (JsonNode cookie : cookies) {
            if ("ses_pwa_revocation_epoch".equals(cookie.path("name").asText())) return cookie;
        }
        throw new AssertionError("撤回epoch cookieがありません: " + cookies);
    }

    /** JDK HttpServerによるloopback stub。実Spring認証ではなく、PWA queueのHTTP契約だけを合成する。 */
    private static final class Stub {
        final AtomicBoolean restoreAvailable = new AtomicBoolean(true);
        final AtomicInteger reauthCalls = new AtomicInteger();
        final AtomicInteger confirmCalls = new AtomicInteger();
        final AtomicInteger staleConflicts = new AtomicInteger();
        final AtomicReference<String> nextChangeRequestConflict = new AtomicReference<>();
        final List<String> attendanceLog = new CopyOnWriteArrayList<>();
        private final Map<String, String> ledgerHashes = new ConcurrentHashMap<>();
        private final Map<String, Integer> ledgerVersions = new ConcurrentHashMap<>();
        private int attendanceVersion;

        synchronized int committedVersion() { return attendanceVersion; }

        void handle(HttpExchange exchange) throws java.io.IOException {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/test.html")) {
                reply(exchange, 200, "text/html; charset=utf-8", """
                        <!doctype html><html lang="ja"><head><meta charset="utf-8"></head><body>
                        <div id="pwa-queue-panel" hidden><a id="pwa-restore-link" href="/offline.html" hidden>下書きを復元</a>
                        <div id="pwa-queue-status"></div><div id="pwa-conflicts"></div></div>
                        <script>
                        localStorage.setItem('ses_pwa_user_scope','scope-A');
                        localStorage.setItem('ses_pwa_scope_epoch',localStorage.getItem('ses_pwa_revocation_epoch')||'0');
                        let online=true;
                        Object.defineProperty(navigator,'onLine',{configurable:true,get:()=>online});window.__setOnline=v=>online=v;
                        window.SES={pwa:{setStatus:()=>{},sessionExpired:()=>{}}};
                        </script><script src="/js/pwa-queue.js"></script>
                        </body></html>
                        """);
                return;
            }
            if (path.equals("/upgrade.html")) {
                reply(exchange, 200, "text/html; charset=utf-8", """
                        <!doctype html><html lang="ja"><head><meta charset="utf-8"></head><body>
                        <div id="pwa-queue-panel" hidden><div id="pwa-queue-status"></div><div id="pwa-conflicts"></div></div>
                        <script>
                        localStorage.setItem('ses_pwa_user_scope','scope-A');
                        window.SES={pwa:{setStatus:()=>{},sessionExpired:()=>{}}};
                        window.__v1Ready=(async()=>{
                          const now=Date.now();
                          const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service',1);
                            r.onupgradeneeded=()=>{const s=r.result.createObjectStore('queue',{keyPath:'clientRequestId'});
                              s.createIndex('userScope','userScope',{unique:false});s.createIndex('status','status',{unique:false});
                              s.createIndex('createdAt','createdAt',{unique:false});};
                            r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error);});
                          await new Promise((ok,no)=>{const tx=db.transaction('queue','readwrite');const q=tx.objectStore('queue');
                            const base={userScope:'scope-A',screen:'attendance',month:'2026-10',method:'POST',
                              path:'/api/my/pwa/attendance/daily',resourceKey:'attendance:2026-10',baseVersion:0,
                              payloadHash:'v1-hash',createdAt:now};
                            q.put(Object.assign({},base,{clientRequestId:'v1-conflict',status:'CONFLICT',payload:{workDate:'2026-10-01'},
                              conflict:{server:{fullName:'本人',contracts:[{contractName:'v1-secret-contract'}]},fields:[]}}));
                            q.put(Object.assign({},base,{clientRequestId:'v1-oversized',status:'PENDING',
                              payload:{workDate:'2026-10-02',remarks:'x'.repeat(40000)}}));
                            q.put(Object.assign({},base,{clientRequestId:'v1-small',status:'PENDING',
                              payload:{workDate:'2026-10-03',remarks:'small'}}));
                            tx.oncomplete=ok;tx.onerror=()=>no(tx.error);});
                          const version=db.version;
                          db.close();
                          return version;
                        })();
                        </script>
                        </body></html>
                        """);
                return;
            }
            if (STATIC_ALLOWLIST.contains(path)) {
                Path asset = STATIC.resolve(path.substring(1)).normalize();
                String type = path.endsWith(".js") ? "text/javascript; charset=utf-8" : "text/html; charset=utf-8";
                reply(exchange, 200, type, Files.readString(asset, StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/api/my/session-context")) {
                exchange.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=synthetic-csrf; Path=/; SameSite=Lax");
                String presented = exchange.getRequestHeaders().getFirst("X-User-Scope");
                String scope = presented == null || presented.isBlank() ? "scope-A" : presented;
                reply(exchange, 200, "application/json", "{\"code\":200,\"data\":{\"userScope\":\"" + scope
                        + "\",\"preserveQueue\":true,\"restoreAvailable\":" + restoreAvailable.get() + "}}");
                return;
            }
            if (path.equals("/api/my/pwa/restore/reauthenticate")) {
                reauthCalls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                if (!restoreAvailable.get()) {
                    reply(exchange, 403, "application/json",
                            "{\"code\":403,\"message\":\"unsupported\",\"data\":{\"type\":\"PWA_RESTORE_REAUTH_UNSUPPORTED\"}}");
                } else if (!"synthetic-csrf".equals(exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN"))) {
                    reply(exchange, 403, "application/json", "{\"code\":403}");
                } else {
                    reply(exchange, 200, "application/json",
                            "{\"code\":200,\"data\":{\"proof\":\"0123456789abcdef0123456789abcdef0123456789A\"}}");
                }
                return;
            }
            if (path.equals("/api/my/pwa/restore/confirm")) {
                confirmCalls.incrementAndGet();
                JsonNode body = JSON.readTree(exchange.getRequestBody());
                if (!restoreAvailable.get()
                        || !"synthetic-csrf".equals(exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN"))) {
                    reply(exchange, 403, "application/json", "{\"code\":403}");
                    return;
                }
                java.time.Instant now = java.time.Instant.now();
                reply(exchange, 200, "application/json", "{\"code\":200,\"data\":{\"documentNonce\":\""
                        + body.path("documentNonce").asText() + "\",\"userScope\":\"" + body.path("userScope").asText()
                        + "\",\"serverTime\":\"" + now + "\",\"permitExpiresAt\":\"" + now.plusSeconds(600) + "\"}}");
                return;
            }
            if (path.equals("/api/my/pwa/attendance/daily") && exchange.getRequestMethod().equals("POST")) {
                exchange.getRequestBody().readAllBytes();
                String id = exchange.getRequestHeaders().getFirst("X-Client-Request-Id");
                String hash = exchange.getRequestHeaders().getFirst("X-Client-Payload-Hash");
                int base = Integer.parseInt(exchange.getRequestHeaders().getFirst("X-Client-Base-Version"));
                int status;
                String body;
                synchronized (this) {
                    // PwaClientMutationLedgerServiceと同じ契約: 同ID同hashはreplay、異hash・古いversionは409。
                    if (ledgerHashes.containsKey(id)) {
                        if (ledgerHashes.get(id).equals(hash)) {
                            attendanceLog.add("replay:" + id + ":" + base);
                            status = 200;
                            body = "{\"code\":200,\"data\":{\"version\":" + ledgerVersions.get(id) + "}}";
                        } else {
                            attendanceLog.add("hash-mismatch:" + id + ":" + base);
                            status = 409;
                            body = "{\"code\":409,\"data\":{\"type\":\"CONFLICT\",\"serverVersion\":" + attendanceVersion + "}}";
                        }
                    } else if (base != attendanceVersion) {
                        staleConflicts.incrementAndGet();
                        attendanceLog.add("stale:" + id + ":" + base);
                        status = 409;
                        body = "{\"code\":409,\"data\":{\"type\":\"CONFLICT\",\"serverVersion\":" + attendanceVersion + "}}";
                    } else {
                        attendanceVersion++;
                        ledgerHashes.put(id, hash);
                        ledgerVersions.put(id, attendanceVersion);
                        attendanceLog.add("commit:" + id + ":" + base);
                        status = 200;
                        body = "{\"code\":200,\"data\":{\"version\":" + attendanceVersion + "}}";
                    }
                }
                reply(exchange, status, "application/json", body);
                return;
            }
            if (path.equals("/api/my/pwa/change-requests/drafts")) {
                exchange.getRequestBody().readAllBytes();
                String conflict = nextChangeRequestConflict.getAndSet(null);
                if (conflict != null) reply(exchange, 409, "application/json", conflict);
                else reply(exchange, 200, "application/json", "{\"code\":200,\"data\":{\"version\":1}}");
                return;
            }
            if (path.equals("/api/my/profile")) {
                reply(exchange, 200, "application/json", "{\"code\":200,\"data\":{\"version\":6,\"fullName\":\"本人\","
                        + "\"skills\":[{\"skillId\":11,\"proficiency\":\"中級\",\"experienceYears\":2,"
                        + "\"skillSecretNote\":\"secret-skill-note\"}],"
                        + "\"careers\":[{\"periodFrom\":\"2025-04\",\"projectName\":\"PWA案件\",\"role\":\"PG\","
                        + "\"careerSecretNote\":\"secret-career-note\"}],"
                        + "\"contracts\":[{\"projectName\":\"機密案件名\",\"contractName\":\"secret-contract\"}],"
                        + "\"primarySalesUserName\":\"営業秘密氏名\",\"primarySalesUserId\":99}}");
                return;
            }
            reply(exchange, 404, "text/plain", "not found");
        }
    }

    /** 1 testごとに専用のstub serverと一時Chrome profileを使い、終了時に必ず片付ける。 */
    private static final class Harness implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor;
        final Path profile;
        final CdpBrowser browser;
        final String base;

        private Harness(HttpServer server, ExecutorService executor, Path profile, CdpBrowser browser) {
            this.server = server;
            this.executor = executor;
            this.profile = profile;
            this.browser = browser;
            this.base = "http://127.0.0.1:" + server.getAddress().getPort();
        }

        static Harness start(Stub stub, String profilePrefix) throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            server.setExecutor(executor);
            server.createContext("/", stub::handle);
            server.start();
            Path profile = Files.createTempDirectory(profilePrefix);
            try {
                CdpBrowser browser = CdpBrowser.launch(CdpBrowser.chromeExecutable(), profile, 390, 844);
                return new Harness(server, executor, profile, browser);
            } catch (Exception | Error failure) {
                server.stop(0);
                executor.shutdownNow();
                deleteProfile(profile);
                throw failure;
            }
        }

        @Override
        public void close() throws Exception {
            try {
                browser.closeGracefully(Duration.ofSeconds(15));
                browser.close();
            } finally {
                server.stop(0);
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "HTTP test executorを期限内に終了する");
                deleteProfile(profile);
            }
        }

        private static void deleteProfile(Path profile) throws java.io.IOException {
            // Chrome profileはtest実行用の一時ディレクトリ。ユーザーデータや既存profileは使わない。
            try (var paths = Files.walk(profile)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static void reply(HttpExchange exchange, int status, String contentType, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
