package com.ses.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** 実ChromeのIndexedDB、複数document、Service Worker、CacheStorageでNF04 queue境界を検証する。 */
@Tag("browser")
class PwaQueueChromeIsolationBrowserTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Path STATIC = Path.of("src/main/resources/static");
    private static final Set<String> STATIC_ALLOWLIST = Set.of(
            "/service-worker.js", "/offline.html", "/favicon.svg", "/manifest.webmanifest",
            "/css/common.css", "/lib/bootstrap/bootstrap.min.css", "/lib/bootstrap-icons/bootstrap-icons.min.css",
            "/lib/bootstrap/bootstrap.bundle.min.js", "/lib/jquery/jquery.min.js",
            "/lib/sweetalert2/sweetalert2.min.css", "/lib/sweetalert2/sweetalert2.all.min.js",
            "/js/common.js", "/js/pwa-queue.js", "/js/profile.js", "/js/modules/my-timesheet.js",
            "/js/modules/attendance-my.js", "/js/modules/my-expenses.js", "/js/modules/my-profile.js");

    @Test
    void chromeでqueue更新競合撤回offline再起動とcache境界を実測する() throws Exception {
        List<Integer> sentVersions = new CopyOnWriteArrayList<>();
        List<String> leaseRequestIds = new CopyOnWriteArrayList<>();
        AtomicInteger leaseRequestCount = new AtomicInteger();
        CountDownLatch leaseFirstStarted = new CountDownLatch(1);
        CountDownLatch leaseSecondStarted = new CountDownLatch(1);
        CountDownLatch releaseLeaseFirst = new CountDownLatch(1);
        AtomicBoolean conflictNext = new AtomicBoolean(false);
        AtomicBoolean rebindScopeOnNextContext = new AtomicBoolean(false);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService httpExecutor = Executors.newCachedThreadPool();
        server.setExecutor(httpExecutor);
        server.createContext("/", exchange -> handle(exchange, sentVersions, conflictNext,
                leaseRequestIds, leaseRequestCount, leaseFirstStarted, leaseSecondStarted,
                releaseLeaseFirst, rebindScopeOnNextContext));
        server.start();
        Path profile = Files.createTempDirectory("ses-nf04-chrome-");
        Path evidence = Path.of("target", "nf04-browser-evidence");
        Files.createDirectories(evidence);
        CdpBrowser browser = null;
        try {
            browser = launchChrome(profile);
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            browser.navigate(base + "/test.html?user=A");
            assertTrue(browser.waitForStrict("!!(window.SES && SES.pwaQueue && SES.pwaQueue.getScope() === 'scope-A')", Duration.ofSeconds(20)),
                    "初期scope確認: " + browser.evaluateStrict("JSON.stringify({href:location.href,scope:window.SES&&SES.pwaQueue&&SES.pwaQueue.getScope(),epochPresent:!!localStorage.getItem('ses_pwa_revocation_epoch'),scopeEpochPresent:!!localStorage.getItem('ses_pwa_scope_epoch'),csrfCookiePresent:document.cookie.split(';').some(x=>x.trim().startsWith('XSRF-TOKEN=')),online:navigator.onLine})").asText()
                            + " / consoleEventCount=" + browser.consoleEvents().size());
            assertTrue(browser.waitForStrict("navigator.serviceWorker.getRegistration().then(r=>!!(r&&r.active))", Duration.ofSeconds(20)));
            // 初回installは現在documentをcontroller化しないため、activation後に再navigationする。
            browser.navigate(base + "/test.html?user=A");
            assertTrue(browser.waitForStrict("!!(navigator.serviceWorker && navigator.serviceWorker.controller)", Duration.ofSeconds(20)));
            browser.evaluate("window.__setOnline(false)");

            JsonNode chromeVersion = browser.send("Browser.getVersion", Map.of());
            JsonNode swRegistration = browser.evaluate("navigator.serviceWorker.getRegistration().then(r=>({scope:r.scope,active:r.active&&r.active.state,controller:!!navigator.serviceWorker.controller}))");
            JsonNode cacheBefore = browser.evaluate("(async()=>{const out=[];for(const n of await caches.keys()){const c=await caches.open(n);for(const r of await c.keys())out.push(new URL(r.url).pathname)}return out})()");
            assertNotNull(cacheBefore);
            for (JsonNode entry : cacheBefore) {
                String path = entry.asText();
                assertFalse(path.startsWith("/api/") || path.startsWith("/my/") || path.startsWith("/portal/"), path);
            }

            JsonNode queued = browser.evaluate("(async()=>{await SES.pwaQueue.whenReady();"
                    + "for(let d=1;d<=3;d++)await SES.pwaQueue.request({screen:'attendance',month:'2026-10',"
                    + "method:'POST',path:'/api/my/pwa/attendance/daily',baseVersion:0,resourceKey:'attendance:2026-10',"
                    + "payload:{workDate:'2026-10-0'+d,clockIn:'09:00',clockOut:'18:00',breaks:[],"
                    + "breakMinutes:60,workType:'出勤',workplaceType:'客先',remarks:'demo'}});return true})()");
            assertTrue(queued.asBoolean());
            browser.evaluate("window.__setOnline(true); SES.pwaQueue.flush()");
            assertTrue(browser.waitForStrict("(async()=>{const r=await indexedDB.open('ses-pwa-self-service');return await new Promise(ok=>{r.onsuccess=()=>{const tx=r.result.transaction('queue');const q=tx.objectStore('queue').getAll();q.onsuccess=()=>ok(q.result.length===0)}})})()", Duration.ofSeconds(20)));
            assertEquals(List.of(0, 1, 2), new ArrayList<>(sentVersions), "同月の連続commandは直前のversionを使う");

            conflictNext.set(true);
            browser.evaluate("(async()=>{await SES.pwaQueue.request({screen:'change-request',month:null,method:'POST',"
                    + "path:'/api/my/pwa/change-requests/drafts',baseVersion:0,resourceKey:'change-request:profile',"
                    + "payload:{requestType:'profile.change',reason:'demo',payload:{fullName:'本人',email:'me@example.test'}}})})()");
            assertTrue(browser.waitForStrict("!!document.querySelector('[data-pwa-refresh]')", Duration.ofSeconds(10)));
            browser.evaluate("document.querySelector('[data-pwa-refresh]').click()");
            assertTrue(browser.waitForStrict("(async()=>{const r=indexedDB.open('ses-pwa-self-service');return await new Promise(ok=>{r.onsuccess=()=>{const q=r.result.transaction('queue').objectStore('queue').getAll();q.onsuccess=()=>ok(q.result.some(x=>x.conflict&&x.conflict.server))}})})()", Duration.ofSeconds(10)));
            String storedConflict = browser.evaluateStrict("(async()=>{const r=indexedDB.open('ses-pwa-self-service');return await new Promise(ok=>{r.onsuccess=()=>{const q=r.result.transaction('queue').objectStore('queue').getAll();q.onsuccess=()=>ok(JSON.stringify(q.result.filter(x=>x.conflict)))}})})()").asText();
            assertFalse(storedConflict.contains("営業秘密氏名"));
            assertFalse(storedConflict.contains("contractName"));
            assertFalse(storedConflict.contains("careerSummary"));

            browser.evaluate("window.__setOnline(false)");
            String offlineDraftRequest = browser.evaluate("(async()=>JSON.stringify(await SES.pwaQueue.request({screen:'attendance',month:'2026-10',method:'POST',"
                    + "path:'/api/my/pwa/attendance/daily',baseVersion:3,resourceKey:'attendance:2026-10',"
                    + "payload:{workDate:'2026-10-06',clockIn:'09:00',clockOut:'18:00',breaks:[],"
                    + "breakMinutes:60,workType:'出勤',workplaceType:'客先',remarks:'offline-draft'}})))()")
                    .asText();
            String offlineDraftRequestId = JSON.readTree(offlineDraftRequest).path("clientRequestId").asText();
            assertFalse(offlineDraftRequestId.isBlank(), "offline commandのrequestIdを取得する");
            assertTrue(browser.waitForStrict("!!(document.querySelector('#pwa-queue-status') && document.querySelector('#pwa-queue-status').textContent.includes('未送信'))", Duration.ofSeconds(10)));

            // ブラウザprocessを終了し、同じChrome profileで再起動してIndexedDB/SW永続化を確認する。
            assertTrue(closeChrome(browser, profile), "Chromeの終了完了後に同じprofileを再起動する");
            browser = null;
            browser = launchChrome(profile);
            browser.send("Network.emulateNetworkConditions", Map.of("offline", true, "latency", 0,
                    "downloadThroughput", 0, "uploadThroughput", 0, "connectionType", "none"));
            browser.navigate(base + "/my/timesheet");
            assertTrue(browser.waitForStrict("document.title.includes('オフライン')", Duration.ofSeconds(15)));
            assertTrue(browser.waitForStrict("document.getElementById('offline-drafts').hidden", Duration.ofSeconds(15)),
                    "Chrome再起動後の同一scope draftもロックする");
            assertTrue(browser.evaluateStrict("SES.pwaQueue.getOfflineDrafts().then(x=>x.length===0)").asBoolean(),
                    "明示確認前はqueue APIからもdraftを読めない");
            String restoredRecordJson = browser.evaluateStrict("(async()=>{const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});const rows=await new Promise(ok=>{const q=db.transaction('queue').objectStore('queue').getAll();q.onsuccess=()=>ok(q.result)});const record=rows.find(x=>x.clientRequestId==="
                    + JSON.writeValueAsString(offlineDraftRequestId) + ");db.close();return JSON.stringify(record||null)})()")
                    .asText();
            JsonNode restoredRecord = JSON.readTree(restoredRecordJson);
            assertEquals(offlineDraftRequestId, restoredRecord.path("clientRequestId").asText());
            assertEquals("scope-A", restoredRecord.path("userScope").asText());
            assertEquals("PENDING", restoredRecord.path("status").asText());
            assertEquals("2026-10-06", restoredRecord.path("payload").path("workDate").asText());
            Files.write(evidence.resolve("offline-shell-390x844.png"), browser.screenshot());
            JsonNode restartCache = browser.evaluate("(async()=>{const out=[];for(const n of await caches.keys()){const c=await caches.open(n);for(const r of await c.keys())out.push(new URL(r.url).pathname)}return out})()");
            assertNotNull(restartCache);
            for (JsonNode entry : restartCache) {
                String path = entry.asText();
                assertFalse(path.startsWith("/api/") || path.startsWith("/my/") || path.startsWith("/portal/"), path);
            }

            browser.send("Network.emulateNetworkConditions", Map.of("offline", false, "latency", 0,
                    "downloadThroughput", -1, "uploadThroughput", -1, "connectionType", "wifi"));
            assertTrue(browser.waitForStrict("navigator.onLine", Duration.ofSeconds(10)));
            assertTrue(browser.waitForStrict("!!(document.getElementById('offline-reauth') && !document.getElementById('offline-reauth').disabled)", Duration.ofSeconds(10)));
            JsonNode csrfBootstrap = browser.evaluateStrict("(async()=>{const r=await fetch('/api/my/session-context',{method:'GET',cache:'no-store',credentials:'same-origin',headers:{'Accept':'application/json'}});const j=await r.json();return JSON.stringify({status:r.status,code:j.code,cookie:document.cookie.split(';').some(x=>x.trim().startsWith('XSRF-TOKEN='))})})()");
            JsonNode csrfBootstrapState = JSON.readTree(csrfBootstrap.asText());
            assertEquals(200, csrfBootstrapState.path("status").asInt(), "onlineの正規bootstrap GETが成功する");
            assertEquals(200, csrfBootstrapState.path("code").asInt(), "scope endpointのJSON successを受け取る");
            assertTrue(csrfBootstrapState.path("cookie").asBoolean(), "server-issued XSRF cookieを同じChrome contextへ更新する");
            assertTrue(browser.evaluateStrict("window.__nativeFetch=window.fetch.bind(window);window.__protectedSendCount=0;"
                    + "window.fetch=(input,init)=>{if(String(input).includes('/api/my/pwa/attendance/daily')){window.__protectedSendCount++;return Promise.reject(new TypeError('fenced'));}return window.__nativeFetch(input,init)};"
                    + "window.__reauthDiagnostics=[];const beforeReauth=window.fetch;window.fetch=async(input,init)=>{if(String(input).includes('/api/my/pwa/restore/reauthenticate')){const h=new Headers(init&&init.headers||{});const rec={csrfHeaderPresent:h.has('X-XSRF-TOKEN'),cookiePresent:document.cookie.split(';').some(x=>x.trim().startsWith('XSRF-TOKEN='))};try{const r=await beforeReauth(input,init);rec.status=r.status;window.__reauthDiagnostics.push(rec);return r}catch(e){rec.errorClass=e&&e.name||'Error';window.__reauthDiagnostics.push(rec);throw e}}return beforeReauth(input,init)};"
                    + "window.__prepareDiagnostics=null;const originalPrepare=SES.pwaQueue.prepareRestore;SES.pwaQueue.prepareRestore=async secret=>{const d={online:navigator.onLine,passwordPresent:!!secret,xsrfCookiePresent:document.cookie.split(';').some(x=>x.trim().startsWith('XSRF-TOKEN=')),stateReady:false,scopePresent:false,epochConsistent:false,scopeRecordCount:-1};"
                    + "try{await SES.pwaQueue.whenReady();d.stateReady=true}catch(e){};try{const scope=localStorage.getItem('ses_pwa_user_scope');const local=localStorage.getItem('ses_pwa_revocation_epoch');const match=document.cookie.match(/(?:^|;\\s*)ses_pwa_revocation_epoch=([^;]+)/);const cookie=match?decodeURIComponent(match[1]):null;d.scopePresent=!!scope;d.epochConsistent=!!local&&local===cookie&&localStorage.getItem('ses_pwa_scope_epoch')===local;const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});const rows=await new Promise((ok,no)=>{const q=db.transaction('queue').objectStore('queue').getAll();q.onsuccess=()=>ok(q.result);q.onerror=()=>no(q.error)});db.close();d.scopeRecordCount=rows.filter(x=>x.userScope===scope).length}catch(e){d.databaseReadError=e&&e.name||'Error'}"
                    + "try{return await originalPrepare(secret)}catch(e){const m=String(e&&e.message||'');d.errorName=e&&e.name||'Error';d.errorCategory=m==='オンラインでログインし直してください'?'state-precondition':m==='復元対象を一意に確認できません'?'restore-target-empty':m.includes('CSRF')?'csrf-precondition':e&&e.name==='TypeError'?'request-or-bootstrap-typeerror':'other';window.__prepareDiagnostics=d;throw e}};"
                    + "document.getElementById('offline-restore-password').value='test-password';"
                    + "document.getElementById('offline-reauth').click();true").asBoolean(), "診断hookをChromeへ確実に設置する");
            boolean reauthReady = browser.waitForStrict("!!(document.getElementById('offline-confirm-restore') && !document.getElementById('offline-confirm-restore').hidden)", Duration.ofSeconds(15));
            assertTrue(reauthReady, "common.jsのないoffline.htmlからXSRF cookieで再認証する。診断="
                    + browser.evaluateStrict("JSON.stringify({status:document.getElementById('offline-restore-status').textContent,request:window.__reauthDiagnostics,prepare:window.__prepareDiagnostics})").asText());
            assertTrue(browser.evaluateStrict("document.getElementById('offline-drafts').hidden").asBoolean(),
                    "再認証のみでは内容を表示しない");
            browser.evaluate("document.getElementById('offline-confirm-restore').click();true");
            assertTrue(browser.waitForStrict("!!(document.getElementById('offline-drafts') && !document.getElementById('offline-drafts').hidden)", Duration.ofSeconds(15)),
                    "ユーザーの明示確認後にのみdraftを表示する");
            assertTrue(browser.evaluateStrict("document.getElementById('offline-draft-list').textContent.includes('2026-10-06')").asBoolean());
            assertFalse(browser.evaluateStrict("document.getElementById('offline-draft-list').textContent.includes('offline-draft')").asBoolean());

            // 実IndexedDB claimを占有し、復元permitの失効を同期待ち中に発生させる。
            browser.evaluate("(async()=>{const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});"
                    + "const rows=await new Promise(ok=>{const q=db.transaction('queue').objectStore('queue').getAll();q.onsuccess=()=>ok(q.result)});"
                    + "const record=rows.find(x=>x.clientRequestId===" + JSON.writeValueAsString(offlineDraftRequestId) + ");"
                    + "await new Promise((ok,no)=>{const tx=db.transaction('meta','readwrite');tx.objectStore('meta').put({key:'aggregate:attendance:2026-10',owner:'other-tab',ownerToken:'held-claim',epoch:record.revocationEpoch,expiresAt:Date.now()+60*60*1000});tx.oncomplete=ok;tx.onerror=()=>no(tx.error)});db.close()})()");
            browser.evaluate("window.__claimProbeCount=0;const originalGet=IDBObjectStore.prototype.get;window.__originalGet=originalGet;"
                    + "IDBObjectStore.prototype.get=function(key){if(this.name==='meta'&&key==='aggregate:attendance:2026-10')window.__claimProbeCount++;return originalGet.call(this,key)};"
                    + "window.__expiryFlush=SES.pwaQueue.flush();true");
            assertTrue(browser.waitForStrict("window.__claimProbeCount>0", Duration.ofSeconds(15)), "IndexedDB claim待ちを観測する");
            browser.evaluate("window.__nativeDateNow=Date.now.bind(Date);window.__nativePerformanceNow=performance.now.bind(performance);"
                    + "Object.defineProperty(performance,'now',{configurable:true,value:()=>window.__nativePerformanceNow()+16*60*1000});"
                    + "Date.now=()=>window.__nativeDateNow()+16*60*1000");
            assertTrue(browser.evaluateStrict("SES.pwaQueue.getOfflineDrafts().then(x=>x.length===0)").asBoolean(),
                    "permit失効後は復元下書きを同期的に隔離する");
            assertTrue(browser.evaluateStrict("document.getElementById('offline-drafts').hidden").asBoolean(),
                    "permit失効イベントで表示DOMを消去する");
            browser.evaluate("(async()=>{const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});"
                    + "await new Promise((ok,no)=>{const tx=db.transaction('meta','readwrite');tx.objectStore('meta').delete('aggregate:attendance:2026-10');tx.oncomplete=ok;tx.onerror=()=>no(tx.error)});db.close();"
                    + "Date.now=window.__nativeDateNow;Object.defineProperty(performance,'now',{configurable:true,value:window.__nativePerformanceNow});"
                    + "await window.__expiryFlush;return true})()");
            assertEquals(1, browser.evaluateStrict("window.__protectedSendCount").asInt(),
                    "expired permit後、claim解放を待つ送信はfetchへ到達しない");
            browser.evaluate("IDBObjectStore.prototype.get=window.__originalGet");
            browser.evaluate("(async()=>{const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});"
                    + "await new Promise((ok,no)=>{const tx=db.transaction('queue','readwrite');tx.objectStore('queue').delete("
                    + JSON.writeValueAsString(offlineDraftRequestId) + ");tx.oncomplete=ok;tx.onerror=()=>no(tx.error)});db.close()})()");

            browser.send("Network.emulateNetworkConditions", Map.of("offline", false, "latency", 0,
                    "downloadThroughput", -1, "uploadThroughput", -1, "connectionType", "wifi"));
            browser.navigate(base + "/test.html?user=A");
            assertTrue(browser.waitForStrict("!!(window.SES && SES.pwaQueue && SES.pwaQueue.getScope()==='scope-A')", Duration.ofSeconds(15)));
            assertTrue(browser.evaluateStrict("SES.pwaQueue.getOfflineDrafts().then(x=>x.length===0)").asBoolean(),
                    "新documentでは前documentが復元したdraftもpermitを引き継がない");
            browser.evaluate("(async()=>{window.__setOnline(false);await SES.pwaQueue.request({screen:'attendance',month:'2026-10',method:'POST',"
                    + "path:'/api/my/pwa/attendance/daily',baseVersion:0,resourceKey:'attendance:2026-10',"
                    + "payload:{workDate:'2026-10-09',clockIn:'09:00',clockOut:'18:00',breaks:[],breakMinutes:60,"
                    + "workType:'出勤',workplaceType:'客先',remarks:'offline-draft'}});window.__setOnline(true);return true})()");

            browser.send("Runtime.evaluate", Map.of("expression", "window.open('/test.html?user=A&child=true','pwa-child');true",
                    "userGesture", true, "returnByValue", true));
            assertTrue(browser.waitForStrict("!!(window.__child && window.__child.SES && window.__child.SES.pwaQueue.getScope()==='scope-A')", Duration.ofSeconds(15)));

            // HTTP応答をbarrierで止め、lease引継ぎ後の旧応答がcommitされないことを検証する。
            browser.evaluate("window.__leaseFlush=SES.pwaQueue.flush(); true");
            assertTrue(leaseFirstStarted.await(15, TimeUnit.SECONDS), "最初のtabがHTTP barrierへ到達する");
            browser.evaluate("window.__child.location='/offline.html';true");
            assertTrue(browser.waitForStrict("window.__child.document.title.includes('オフライン')", Duration.ofSeconds(15)));
            assertTrue(browser.waitForStrict("!!(window.__child && window.__child.document.getElementById('offline-reauth') && !window.__child.document.getElementById('offline-reauth').disabled)", Duration.ofSeconds(10)));
            browser.evaluate("window.__child.document.getElementById('offline-restore-password').value='test-password';"
                    + "window.__child.document.getElementById('offline-reauth').click();true");
            assertTrue(browser.waitForStrict("!!(window.__child && window.__child.document.getElementById('offline-confirm-restore') && !window.__child.document.getElementById('offline-confirm-restore').hidden)", Duration.ofSeconds(15)));
            browser.evaluate("const child=window.__child;child.__claimProbeCount=0;child.__originalGet=child.IDBObjectStore.prototype.get;"
                    + "child.IDBObjectStore.prototype.get=function(key){if(this.name==='meta'&&key==='aggregate:attendance:2026-10')child.__claimProbeCount++;return child.__originalGet.call(this,key)};"
                    + "child.document.getElementById('offline-confirm-restore').click();true");
            assertTrue(browser.waitForStrict("window.__child.__claimProbeCount>0", Duration.ofSeconds(15)),
                    "明示復元後の第2documentが実IndexedDB leaseを待つ");
            browser.evaluate("(async()=>{const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});"
                    + "await new Promise((ok,no)=>{const tx=db.transaction('meta','readwrite');const s=tx.objectStore('meta');const r=s.get('aggregate:attendance:2026-10');r.onsuccess=()=>{const lease=r.result;lease.expiresAt=0;s.put(lease)};tx.oncomplete=ok;tx.onerror=()=>no(tx.error)});db.close()})()");
            assertTrue(leaseSecondStarted.await(15, TimeUnit.SECONDS), "lease takeover tabが同一recordを再送する");
            assertEquals(2, leaseRequestCount.get(), "lease takeoverは同一pending commandを再送しidempotency keyで保護する");
            assertEquals(1L, leaseRequestIds.stream().distinct().count(), "両tabは同じclientRequestIdを再利用する");
            releaseLeaseFirst.countDown();
            assertTrue(browser.evaluateStrict("window.__leaseFlush.then(()=>true)").asBoolean());
            assertTrue(browser.waitForStrict("(async()=>{const r=indexedDB.open('ses-pwa-self-service');return await new Promise(ok=>{r.onsuccess=()=>{const q=r.result.transaction('queue').objectStore('queue').getAll();q.onsuccess=()=>ok(!q.result.some(x=>x.status==='PENDING'))}})})()", Duration.ofSeconds(10)),
                    "新ownerのack後に旧ownerの遅延responseがpending recordを復活させない");

            long ttlClock = browser.evaluateStrict("Date.now()").asLong();
            String ttlSetup = "const fixed=" + ttlClock + ";await new Promise((ok,no)=>{const tx=db.transaction('queue','readwrite');const q=tx.objectStore('queue');"
                    + "const base={status:'ERROR',payload:null};q.put({...base,clientRequestId:'ttl-old',userScope:'ttl',createdAt:fixed-30*24*60*60*1000-1});"
                    + "q.put({...base,clientRequestId:'ttl-exact',userScope:'ttl',createdAt:fixed-30*24*60*60*1000});"
                    + "q.put({...base,clientRequestId:'ttl-fresh',userScope:'ttl',createdAt:fixed-30*24*60*60*1000+1});tx.oncomplete=ok;tx.onerror=()=>no(tx.error)});";
            browser.evaluate("(async()=>{const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});"
                    + ttlSetup + "db.close();return true})()");
            JsonNode ttlClockScript = browser.send("Page.addScriptToEvaluateOnNewDocument", Map.of("source",
                    "if(location.search.includes('nf04_ttl_probe')){const fixed=" + ttlClock
                            + ";window.__nf04TtlNativeDateNow=Date.now;Date.now=()=>fixed}"));
            Throwable ttlPrimaryFailure = null;
            try {
                browser.navigate(base + "/test.html?user=A&nf04_ttl_probe=1");
                assertTrue(browser.waitForStrict("window.SES&&SES.pwaQueue&&SES.pwaQueue.whenReady().then(()=>true)", Duration.ofSeconds(15)),
                        "TTL cleanup transactionが完了する");
                assertTrue(browser.evaluateStrict("(async()=>{const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});const ids=await new Promise((ok,no)=>{const q=db.transaction('queue').objectStore('queue').getAllKeys();q.onsuccess=()=>ok(q.result);q.onerror=()=>no(q.error)});db.close();return !ids.includes('ttl-old')&&ids.includes('ttl-exact')&&ids.includes('ttl-fresh')})()").asBoolean(),
                        "TTLはthreshold未満だけ削除し、exact thresholdとfresh recordを保持する");
            } catch (Exception | AssertionError thrown) {
                ttlPrimaryFailure = thrown;
                throw thrown;
            } finally {
                Throwable ttlCleanupFailure = null;
                try {
                    browser.evaluate("if(window.__nf04TtlNativeDateNow){Date.now=window.__nf04TtlNativeDateNow;delete window.__nf04TtlNativeDateNow}true");
                } catch (Throwable thrown) {
                    ttlCleanupFailure = thrown;
                }
                try {
                    browser.send("Page.removeScriptToEvaluateOnNewDocument", Map.of("identifier", ttlClockScript.path("identifier").asText()));
                } catch (Throwable thrown) {
                    if (ttlCleanupFailure == null) ttlCleanupFailure = thrown;
                    else ttlCleanupFailure.addSuppressed(thrown);
                }
                if (ttlCleanupFailure != null) {
                    if (ttlPrimaryFailure != null) ttlPrimaryFailure.addSuppressed(ttlCleanupFailure);
                    else if (ttlCleanupFailure instanceof Exception exception) throw exception;
                    else if (ttlCleanupFailure instanceof Error error) throw error;
                    else throw new IllegalStateException("TTL test clock cleanup failed", ttlCleanupFailure);
                }
            }

            // 不正statusの旧scope recordを残し、confirm中の実IndexedDB deleteScope transactionを止めて撤回と競合させる。
            browser.evaluate("(async()=>{window.__setOnline(false);await SES.pwaQueue.request({screen:'attendance',month:'2026-10',method:'POST',"
                    + "path:'/api/my/pwa/attendance/daily',baseVersion:0,resourceKey:'attendance:2026-10',"
                    + "payload:{workDate:'2026-10-10',clockIn:'09:00',clockOut:'18:00',breaks:[],breakMinutes:60,"
                    + "workType:'出勤',workplaceType:'客先',remarks:'delete-race'}});window.__setOnline(true);"
                    + "const sentinelScope=SES.pwaQueue.getScope();window.__deleteSentinelScope=sentinelScope;"
                    + "const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});"
                    + "await new Promise((ok,no)=>{const tx=db.transaction('queue','readwrite');const q=tx.objectStore('queue');"
                    + "const base={revocationEpoch:localStorage.getItem('ses_pwa_revocation_epoch'),status:'ERROR',createdAt:Date.now(),payload:null};"
                    + "q.put({...base,clientRequestId:'locked-delete-sentinel',userScope:sentinelScope});"
                    + "q.put({...base,clientRequestId:'locked-delete-sentinel-2',userScope:sentinelScope});"
                    + "q.put({...base,clientRequestId:'foreign-delete-sentinel',userScope:'foreign-scope'});"
                    + "tx.oncomplete=ok;tx.onerror=()=>no(tx.error)});db.close();return true})()");
            assertTrue(browser.evaluateStrict("SES.pwaQueue.prepareRestore('test-password').then(()=>true)").asBoolean(),
                    "再認証proofを実queue状態へ束縛する");
            assertTrue(browser.evaluateStrict("fetch('/__test/enable-scope-drift').then(r=>r.ok)").asBoolean());
            browser.evaluate(completionBarrierScript() + "window.__deleteReached=false;window.__deleteBarrierTimedOut=false;window.__releaseDeleteRequested=false;window.__releaseDeleteBarrier=null;"
                    + "window.__deleteScopeTransaction=null;window.__deletedSentinelKey=null;window.__deleteScopeCursorTransactions=[];"
                    + "window.__nativeOpenKeyCursor=IDBIndex.prototype.openKeyCursor;IDBIndex.prototype.openKeyCursor=function(range,direction){"
                    + "if(this.objectStore.name==='queue'&&this.name==='userScope'&&range&&range.lower===window.__deleteSentinelScope"
                    + "&&range.upper===window.__deleteSentinelScope&&!range.lowerOpen&&!range.upperOpen)window.__deleteScopeCursorTransactions.push(this.objectStore.transaction);"
                    + "return window.__nativeOpenKeyCursor.call(this,range,direction)};window.__nativeStoreDelete=IDBObjectStore.prototype.delete;"
                    + "IDBObjectStore.prototype.delete=function(key){if(this.name==='queue'&&key==='locked-delete-sentinel'){"
                    + "window.__deleteScopeTransaction=this.transaction;window.__deletedSentinelKey=key;window.__deleteScopeCursorMatched=window.__deleteScopeCursorTransactions.includes(this.transaction)}"
                    + "return window.__nativeStoreDelete.call(this,key)};"
                    + "window.__deleteBarrierDeadline=Date.now()+20000;window.__nativeTransaction=IDBDatabase.prototype.transaction;"
                    + "IDBDatabase.prototype.transaction=function(names,mode,options){const tx=window.__nativeTransaction.call(this,names,mode,options);"
                    + "const storeNames=Array.from(tx.objectStoreNames);if(mode==='readwrite'&&storeNames.length===1&&storeNames[0]==='queue'){"
                    + "let handler=null;Object.defineProperty(tx,'oncomplete',{configurable:true,get:()=>handler,set:fn=>{handler=fn;"
                    + "window.sesDelayIndexedDbCompletion(tx,fn,(resume)=>{if(window.__holdDeleteCompletion&&window.__deleteScopeTransaction===tx){"
                    + "window.__holdDeleteCompletion=false;window.__deleteReached=true;"
                    + "const releaseProduction=()=>{if(window.__deleteBarrierDeadline<Date.now())window.__deleteBarrierTimedOut=true;resume()};"
                    + "if(window.__releaseDeleteRequested||window.__deleteBarrierDeadline<Date.now()){window.__deleteBarrierTimedOut=true;releaseProduction()}else{window.__releaseDeleteBarrier=releaseProduction}}"
                    + "else resume()})}})}return tx};"
                    + "window.__holdDeleteCompletion=true;window.__raceConfirmSettled=false;window.__raceConfirm=SES.pwaQueue.confirmRestore().then(()=>{window.__raceConfirmSettled=true;return {ok:true}},e=>{window.__raceConfirmSettled=true;return {ok:false,message:e.message}});true");
            assertTrue(browser.waitForStrict("window.__deleteReached", Duration.ofSeconds(15)),
                    "dynamic old scopeを対象にしたdeleteScope transactionの完了イベント解決を保留する");
            assertTrue(browser.evaluateStrict("window.__deleteScopeTransaction!==null&&window.__deletedSentinelKey==='locked-delete-sentinel'&&window.__deleteScopeCursorMatched").asBoolean(),
                    "dynamic old scopeのuserScope cursorとsentinel主キー削除が同一transactionである");
            assertFalse(browser.evaluateStrict("window.__deleteBarrierTimedOut").asBoolean(), "IDB完了barrierが期限内に到達する");
            assertFalse(browser.evaluateStrict("window.__raceConfirmSettled").asBoolean(), "削除transaction待機中はconfirmRestoreが未完了");
            assertTrue(browser.evaluateStrict("(async()=>{const db=await new Promise((ok,no)=>{const r=indexedDB.open('ses-pwa-self-service');r.onsuccess=()=>ok(r.result);r.onerror=()=>no(r.error)});const rows=await new Promise((ok,no)=>{const q=db.transaction('queue').objectStore('queue').getAll();q.onsuccess=()=>ok(q.result);q.onerror=()=>no(q.error)});db.close();const ids=rows.map(x=>x.clientRequestId);return !ids.includes('locked-delete-sentinel')&&!ids.includes('locked-delete-sentinel-2')&&ids.includes('foreign-delete-sentinel')})()").asBoolean(),
                    "実IndexedDB transactionは旧scopeの複数recordを削除しforeign scopeを保持する");
            assertTrue(browser.evaluateStrict("window.__protectedSendCount=0;window.__nativeFetch=window.fetch.bind(window);"
                    + "window.fetch=(input,init)=>{if(String(input).includes('/api/my/pwa/attendance/daily')){window.__protectedSendCount++;return Promise.reject(new TypeError('fenced'));}return window.__nativeFetch(input,init)};"
                    + "window.__raceReset=SES.pwaQueue.reset();true").asBoolean(), "送信fenceと削除barrier hookを設置する");
            assertTrue(browser.evaluateStrict("document.getElementById('pwa-queue-panel').hidden").asBoolean(),
                    "confirmを待つ間の撤回がqueue DOMを即時隠す");
            browser.evaluate("window.__releaseDeleteBarrier();true");
            assertTrue(browser.evaluateStrict("Promise.all([window.__raceReset,window.__raceConfirm]).then(x=>x[1].ok===false)").asBoolean(),
                    "deleteScope完了後も古いconfirmは撤回状態を上書きしない");
            assertFalse(browser.evaluateStrict("window.__deleteBarrierTimedOut").asBoolean(), "IDB完了barrierは期限超過前に解放される");
            assertEquals(0, browser.evaluateStrict("window.__protectedSendCount").asInt(),
                    "撤回競合のconfirmはqueue送信へ到達しない");
            assertTrue(browser.evaluateStrict("SES.pwaQueue.getScope()===null&&document.getElementById('pwa-queue-panel').hidden").asBoolean(),
                    "競合終了後もscopeとDOMをロック状態に保つ");
            browser.evaluate("IDBDatabase.prototype.transaction=window.__nativeTransaction;"
                    + "IDBIndex.prototype.openKeyCursor=window.__nativeOpenKeyCursor;"
                    + "IDBObjectStore.prototype.delete=window.__nativeStoreDelete;true");
            browser.evaluate("if(window.__child)window.__child.close();true");
            browser.navigate(base + "/test.html?user=A");
            assertTrue(browser.waitForStrict("!!(window.SES&&SES.pwaQueue&&SES.pwaQueue.getScope()==='scope-A')", Duration.ofSeconds(15)),
                    "撤回完了後は新documentでのみ通常の新規queueを開始する");
            browser.send("Runtime.evaluate", Map.of("expression", "window.open('/test.html?user=A&child=true','pwa-child-after-race');true",
                    "userGesture", true, "returnByValue", true));
            assertTrue(browser.waitForStrict("!!window.__child&&!!window.__child.SES&&window.__child.SES.pwaQueue.getScope()==='scope-A'", Duration.ofSeconds(15)),
                    "新documentから独立した2つ目のdocumentを作成する");

            browser.evaluate("(async()=>{const native=crypto.subtle.digest.bind(crypto.subtle);window.__nativeDigest=native;let release;crypto.subtle.digest=(a,b)=>new Promise(ok=>{release=()=>native(a,b).then(ok)});window.__late=SES.pwaQueue.request({screen:'attendance',month:'2026-10',method:'POST',path:'/api/my/pwa/attendance/daily',baseVersion:0,resourceKey:'attendance:2026-10',payload:{workDate:'2026-10-05',clockIn:'09:00',clockOut:'18:00',breaks:[],breakMinutes:60,workType:'出勤',workplaceType:'客先',remarks:'late'}});window.__releaseDigest=()=>release();return true})()");
            browser.evaluate("SES.pwaQueue.reset()");
            browser.evaluate("window.__releaseDigest && window.__releaseDigest()");
            browser.evaluate("window.__child.SES.pwaQueue.getOfflineDrafts()");
            assertTrue(browser.waitForStrict("window.__child.SES.pwaQueue.getScope()===null", Duration.ofSeconds(10)), "logout epochは他tabへ伝播する");
            String late = browser.evaluateStrict("window.__late && window.__late.then(x=>JSON.stringify(x))").asText("");
            assertFalse(late.contains("queued"), "logout後の遅延hash結果が旧commandを復活させない");

            browser.evaluate("window.__child.close()");
            browser.navigate(base + "/test.html?user=B");
            assertTrue(browser.waitForStrict("!!(window.SES && SES.pwaQueue && SES.pwaQueue.getScope()==='scope-B')", Duration.ofSeconds(15)));
            assertTrue(browser.evaluateStrict("SES.pwaQueue.getOfflineDrafts().then(x=>x.length===0)").asBoolean(), "scope BへAのdraftを表示しない");

            browser.evaluate("window.__setOnline(false)");
            browser.evaluate("(async()=>await SES.pwaQueue.request({screen:'attendance',month:'2026-10',method:'POST',"
                    + "path:'/api/my/pwa/attendance/daily',baseVersion:0,resourceKey:'attendance:2026-10',"
                    + "payload:{workDate:'2026-10-07',clockIn:'09:00',clockOut:'18:00',breaks:[],"
                    + "breakMinutes:60,workType:'出勤',workplaceType:'客先',remarks:'clear-failure'}}))()");
            JsonNode clearFailure = browser.evaluate("(async()=>{const original=IDBFactory.prototype.open;window.__nativeIdbOpen=original;"
                    + "IDBFactory.prototype.open=function(){throw new Error('blocked-test')};"
                    + "try{return JSON.stringify(await SES.pwaQueue.reset())}finally{IDBFactory.prototype.open=original}})()");
            assertFalse(JSON.readTree(clearFailure.asText()).path("cleared").asBoolean(), "IDB clear失敗を成功扱いしない");
            assertTrue(browser.evaluateStrict("SES.pwaQueue.getOfflineDrafts().then(x=>x.length===0)").asBoolean(), "clear失敗後も旧epochを隔離する");
            browser.navigate(base + "/test.html?user=B");
            assertTrue(browser.waitForStrict("!!(window.SES && SES.pwaQueue && SES.pwaQueue.getScope()==='scope-B')", Duration.ofSeconds(15)));
            assertTrue(browser.evaluateStrict("SES.pwaQueue.getOfflineDrafts().then(x=>x.length===0)").asBoolean(), "同一user再表示でも撤回済draftを復活しない");
            browser.evaluate("window.__setOnline(false)");
            browser.evaluate("SES.pwaQueue.request({screen:'attendance',month:'2026-10',method:'POST',path:'/api/my/pwa/attendance/daily',baseVersion:0,resourceKey:'attendance:2026-10',payload:{workDate:'2026-10-08',clockIn:'09:00',clockOut:'18:00',breaks:[],breakMinutes:60,workType:'出勤',workplaceType:'客先',remarks:'partial-tombstone'}})");
            JsonNode partialTombstone = browser.evaluate("(async()=>{const originalSet=Storage.prototype.setItem;window.__nativeStorageSet=originalSet;const originalNow=Date.now;const oldLocal=localStorage.getItem('ses_pwa_revocation_epoch');Storage.prototype.setItem=function(k,v){if(k==='ses_pwa_revocation_epoch')throw new Error('storage-denied');return originalSet.call(this,k,v)};Date.now=()=>1;try{const result=await SES.pwaQueue.reset();const cookie=(document.cookie.match(/(?:^|;\\s*)ses_pwa_revocation_epoch=([^;]+)/)||[])[1]||null;return {cleared:result.cleared,tokenStoresDiffer:oldLocal!==cookie}}finally{Date.now=originalNow;Storage.prototype.setItem=originalSet}})()");
            assertFalse(partialTombstone.path("cleared").asBoolean(), "単一の撤回保存先だけでは永続撤回成功と扱わない");
            assertTrue(partialTombstone.path("tokenStoresDiffer").asBoolean(), "時計巻き戻し中でも撤回保存先ごとのtokenを区別する");
            assertTrue(browser.evaluateStrict("SES.pwaQueue.getOfflineDrafts().then(x=>x.length===0)").asBoolean(), "撤回保存先が不一致ならdraftを隔離する");
            assertTrue(closeChrome(browser, profile), "Chromeの終了完了後に撤回後のprofileを再起動する");
            browser = null;
            browser = launchChrome(profile);
            browser.send("Network.emulateNetworkConditions", Map.of("offline", true, "latency", 0,
                    "downloadThroughput", 0, "uploadThroughput", 0, "connectionType", "none"));
            browser.navigate(base + "/my/timesheet");
            assertTrue(browser.waitForStrict("document.title.includes('オフライン')", Duration.ofSeconds(15)));
            assertTrue(browser.waitForStrict("document.getElementById('offline-drafts').hidden", Duration.ofSeconds(10)), "撤回記録の不一致は再起動後もoffline復元を拒否する");
            JsonNode finalCache = browser.evaluate("(async()=>{const out=[];for(const n of await caches.keys()){const c=await caches.open(n);for(const r of await c.keys())out.push(new URL(r.url).pathname)}return out})()");
            assertNotNull(finalCache);
            for (JsonNode entry : finalCache) {
                String path = entry.asText();
                assertFalse(path.startsWith("/api/") || path.startsWith("/my/") || path.startsWith("/portal/"), path);
            }
            Files.writeString(evidence.resolve("cache-storage-paths.json"), JSON.writeValueAsString(finalCache), StandardCharsets.UTF_8);
            Files.writeString(evidence.resolve("run-summary.json"), JSON.writeValueAsString(Map.ofEntries(
                    Map.entry("provider", "loopback HTTP contract stub; not application E2E or staging evidence"),
                    Map.entry("browser", chromeVersion.path("product").asText()),
                    Map.entry("browserVersion", chromeVersion.path("revision").asText()),
                    Map.entry("viewport", "390x844"), Map.entry("profileRestart", true), Map.entry("serviceWorker", swRegistration),
                    Map.entry("assertions", List.of("same-month three-command version chain", "change-request conflict allowlist projection",
                            "draft remains locked after restart until reauth plus explicit confirm", "expired permit during IndexedDB claim wait blocks fetch and clears DOM",
                            "deleteScope cursor racing revoke cannot unlock or send", "explicitly restored popup takeover / stale response fencing",
                            "late digest cannot reinsert", "IDB clear failure remains quarantined", "A-to-B draft isolation",
                            "partial tombstone mismatch with clock rollback rejects offline restore", "CacheStorage forbidden dynamic routes=0")),
                    Map.entry("sequentialBaseVersions", sentVersions), Map.entry("cacheEntries", finalCache),
                    Map.entry("oldScopeDraftsAfterRevoke", 0), Map.entry("clearFailureQuarantined", true),
                    Map.entry("offlineRestartDraftLockedUntilConfirmed", true), Map.entry("expiredPermitFetchCount", 1),
                    Map.entry("deleteScopeRaceFetchCount", 0))), StandardCharsets.UTF_8);
        } finally {
            releaseLeaseFirst.countDown();
            releaseBrowserBarriers(browser);
            if (browser != null && !closeChrome(browser, profile)) browser.close();
            server.stop(0);
            httpExecutor.shutdownNow();
            assertTrue(httpExecutor.awaitTermination(5, TimeUnit.SECONDS), "HTTP test executorを期限内に終了する");
            // Chrome profileはtest実行用一時ディレクトリ。ユーザーデータや既存profileは使わない。
            try (var paths = Files.walk(profile)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static String completionBarrierScript() throws Exception {
        try (var stream = PwaQueueChromeIsolationBrowserTest.class.getResourceAsStream(
                "/js/pwa-delete-completion-barrier.js")) {
            if (stream == null) throw new IllegalStateException("IndexedDB完了barrier helperを取得できません");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void releaseBrowserBarriers(CdpBrowser browser) {
        if (browser == null) return;
        try {
            browser.evaluate("window.__releaseDeleteRequested=true;if(window.__releaseDeleteBarrier)window.__releaseDeleteBarrier();"
                    + "if(window.__nativeTransaction)IDBDatabase.prototype.transaction=window.__nativeTransaction;"
                    + "if(window.__originalGet)IDBObjectStore.prototype.get=window.__originalGet;"
                    + "if(window.__nativeOpenKeyCursor)IDBIndex.prototype.openKeyCursor=window.__nativeOpenKeyCursor;"
                    + "if(window.__nativeStoreDelete)IDBObjectStore.prototype.delete=window.__nativeStoreDelete;"
                    + "if(window.__nativeFetch)window.fetch=window.__nativeFetch;"
                    + "if(window.__nativeDateNow)Date.now=window.__nativeDateNow;"
                    + "if(window.__nativePerformanceNow)Object.defineProperty(performance,'now',{configurable:true,value:window.__nativePerformanceNow});"
                    + "if(window.__nativeDigest)crypto.subtle.digest=window.__nativeDigest;"
                    + "if(window.__nativeStorageSet)Storage.prototype.setItem=window.__nativeStorageSet;"
                    + "if(window.__nativeIdbOpen)IDBFactory.prototype.open=window.__nativeIdbOpen;true");
            browser.evaluate("if(window.__child){if(window.__child.__releaseDeleteBarrier)window.__child.__releaseDeleteBarrier();"
                    + "if(window.__child.__originalGet)window.__child.IDBObjectStore.prototype.get=window.__child.__originalGet;"
                    + "if(window.__child.__nativeOpenKeyCursor)window.__child.IDBIndex.prototype.openKeyCursor=window.__child.__nativeOpenKeyCursor;}true");
        } catch (Exception ignored) {
            // 終了処理では残存documentの差し替え失敗を許容し、Chrome processを終了する。
        }
    }

    private static void handle(HttpExchange exchange, List<Integer> versions, AtomicBoolean conflictNext,
                               List<String> leaseRequestIds, AtomicInteger leaseRequestCount,
                               CountDownLatch leaseFirstStarted, CountDownLatch leaseSecondStarted,
                               CountDownLatch releaseLeaseFirst,
                               AtomicBoolean rebindScopeOnNextContext) throws java.io.IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/test.html")) {
            reply(exchange, 200, "text/html; charset=utf-8", """
                    <!doctype html><html lang="ja"><head><meta charset="utf-8"></head><body>
                    <div id="pwa-queue-panel"><div id="pwa-queue-status"></div><div id="pwa-conflicts"></div></div>
                    <script>
                    const initialScope='scope-'+(new URL(location.href).searchParams.get('user')||'A');
                    localStorage.setItem('ses_pwa_user_scope',initialScope);
                    localStorage.setItem('ses_pwa_scope_epoch',localStorage.getItem('ses_pwa_revocation_epoch')||'0');
                    let online=!(new URL(location.href).searchParams.get('offline')==='true');
                    Object.defineProperty(navigator,'onLine',{configurable:true,get:()=>online});window.__setOnline=v=>online=v;
                    window.SES={csrf:{header:()=>({})},pwa:{setStatus:()=>{},sessionExpired:()=>{}}};
                    if(window.opener)window.opener.__child=window;
                    </script><script src="/js/pwa-queue.js"></script>
                    <script>navigator.serviceWorker.register('/service-worker.js');</script>
                    </body></html>
                    """);
            return;
        }
        if (STATIC_ALLOWLIST.contains(path)) {
            Path asset = STATIC.resolve(path.substring(1)).normalize();
            if (!asset.startsWith(STATIC) || !Files.exists(asset)) { reply(exchange, 404, "text/plain", "missing"); return; }
            String type = path.endsWith(".js") ? "text/javascript; charset=utf-8"
                    : path.endsWith(".css") ? "text/css; charset=utf-8"
                    : path.endsWith(".html") ? "text/html; charset=utf-8"
                    : path.endsWith(".webmanifest") ? "application/manifest+json" : "application/octet-stream";
            reply(exchange, 200, type, Files.readString(asset, StandardCharsets.UTF_8));
            return;
        }
        if (path.equals("/api/my/session-context")) {
            // Spring Securityと同じbootstrap契約を合成し、CSRF tokenは通常のHTTP応答で発行する。
            exchange.getResponseHeaders().add("Set-Cookie", "XSRF-TOKEN=synthetic-csrf; Path=/; SameSite=Lax");
            String presented = exchange.getRequestHeaders().getFirst("X-User-Scope");
            String scope = presented == null || presented.isBlank() ? "scope-A" : presented;
            if (rebindScopeOnNextContext.compareAndSet(true, false)) scope += "-rebound";
            reply(exchange, 200, "application/json", "{\"code\":200,\"data\":{\"userScope\":\"" + scope + "\",\"preserveQueue\":true}}");
            return;
        }
        if (path.equals("/__test/enable-scope-drift")) {
            rebindScopeOnNextContext.set(true);
            reply(exchange, 200, "application/json", "{\"ok\":true}");
            return;
        }
        if (path.equals("/api/my/pwa/restore/reauthenticate")) {
            if (!"synthetic-csrf".equals(exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN"))) {
                reply(exchange, 403, "application/json", "{\"code\":403}");
                return;
            }
            exchange.getRequestBody().readAllBytes();
            reply(exchange, 200, "application/json", "{\"code\":200,\"data\":{\"proof\":\"0123456789abcdef0123456789abcdef0123456789A\"}}");
            return;
        }
        if (path.equals("/api/my/pwa/restore/confirm")) {
            if (!"synthetic-csrf".equals(exchange.getRequestHeaders().getFirst("X-XSRF-TOKEN"))) {
                reply(exchange, 403, "application/json", "{\"code\":403}");
                return;
            }
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            java.time.Instant now = java.time.Instant.now();
            reply(exchange, 200, "application/json", "{\"code\":200,\"data\":{\"documentNonce\":\""
                    + body.path("documentNonce").asText() + "\",\"userScope\":\"" + body.path("userScope").asText()
                    + "\",\"serverTime\":\"" + now + "\",\"permitExpiresAt\":\""
                    + now.plusSeconds(600) + "\"}}");
            return;
        }
        if (path.equals("/api/my/pwa/attendance/daily") && exchange.getRequestMethod().equals("POST")) {
            JsonNode body = JSON.readTree(exchange.getRequestBody());
            int base = Integer.parseInt(exchange.getRequestHeaders().getFirst("X-Client-Base-Version"));
            versions.add(base);
            if ("offline-draft".equals(body.path("payload").path("remarks").asText())) {
                leaseRequestIds.add(exchange.getRequestHeaders().getFirst("X-Client-Request-Id"));
                if (leaseRequestCount.incrementAndGet() == 1) {
                    leaseFirstStarted.countDown();
                    try { releaseLeaseFirst.await(30, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                } else if (leaseRequestCount.get() == 2) leaseSecondStarted.countDown();
            }
            reply(exchange, 200, "application/json", "{\"code\":200,\"data\":{\"version\":" + (base + 1) + "}}");
            return;
        }
        if (path.equals("/api/my/pwa/change-requests/drafts")) {
            exchange.getRequestBody().readAllBytes();
            if (conflictNext.compareAndSet(true, false)) {
                reply(exchange, 409, "application/json", "{\"code\":409,\"data\":{\"type\":\"CONFLICT\",\"serverVersion\":3,\"server\":{\"fullName\":\"本人\",\"営業秘密氏名\":\"secret\",\"contractName\":\"secret\"},\"fields\":[{\"name\":\"fullName\",\"serverValue\":\"本人\",\"clientValue\":\"本人\"},{\"name\":\"営業秘密氏名\",\"serverValue\":\"secret\"}]}}");
            } else reply(exchange, 200, "application/json", "{\"code\":200,\"data\":{\"version\":1}}");
            return;
        }
        if (path.equals("/api/my/profile")) {
            reply(exchange, 200, "application/json", "{\"code\":200,\"data\":{\"version\":4,\"fullName\":\"本人\",\"email\":\"me@example.test\",\"salesNames\":[\"営業秘密氏名\"],\"contracts\":[{\"contractName\":\"secret\"}],\"careers\":[{\"careerSummary\":\"secret\"}]}}");
            return;
        }
        if (path.startsWith("/api/") || path.startsWith("/my/")) {
            reply(exchange, 200, path.startsWith("/api/") ? "application/json" : "text/html", "{\"code\":200,\"data\":{}}");
            return;
        }
        reply(exchange, 404, "text/plain", "not found");
    }

    private static CdpBrowser launchChrome(Path profile) throws Exception {
        return CdpBrowser.launch(CdpBrowser.chromeExecutable(), profile, 390, 844);
    }

    private static boolean closeChrome(CdpBrowser browser, Path profile) {
        Path activePort = profile.resolve("DevToolsActivePort");
        boolean exitedCleanly = browser.closeGracefully(Duration.ofSeconds(15));
        if (!exitedCleanly) return false;
        try { Files.deleteIfExists(activePort); }
        catch (Exception cleanupFailure) { return false; }
        browser.close();
        return true;
    }

    private static void reply(HttpExchange exchange, int status, String contentType, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }
}
