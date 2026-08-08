package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 月次検収画面 JS の Node.js 上での実行級回帰テスト（R7-P2-04）。
 * DOMContentLoaded -> queryパラメータ解析 -> APIパラメータ伝播 -> 厳格 r.id マッチ -> table-warning 付与 ->
 * scrollIntoView 呼び出しが ReferenceError 等の未定義エラーなしに実行完了することを検証する。
 */
class AcceptanceJsRuntimeTest {

    @Test
    void testAcceptanceJsQueryParamAndTargetHighlight() throws IOException, InterruptedException {
        boolean nodeAvail = false;
        try {
            Process checkP = new ProcessBuilder("node", "-v").start();
            nodeAvail = checkP.waitFor(5, TimeUnit.SECONDS) && checkP.exitValue() == 0;
        } catch (Exception ignored) {
        }
        if ("true".equals(System.getenv("CI"))) {
            assertTrue(nodeAvail, "CI環境では Node.js が必須です");
        } else if (!nodeAvail) {
            assumeTrue(false, "Node.js が利用できないため skip します");
        }

        Path jsPath = Paths.get("src/main/resources/static/js/modules/acceptance.js");
        assertTrue(Files.exists(jsPath), "acceptance.js が存在しません: " + jsPath);

        String jsCode = Files.readString(jsPath);
        String script = """
            let scrolled = false;
            let warningAdded = false;
            let apiRequestedParams = null;
            let datasetOk = false;

            global.window = {
                location: { search: '?workMonth=2026-09&acceptanceId=123' }
            };
            global.$ = (sel) => ({ html: () => {}, empty: () => {}, append: () => {}, find: () => ({ val: () => '' }) });
            global.URLSearchParams = require('url').URLSearchParams;
            const elemMap = {
                acceptanceWorkMonth: { value: '' },
                btnSearchAcceptance: { addEventListener: () => {} },
                acceptanceStatusFilter: { value: '', innerHTML: '', appendChild: () => {} },
                acceptanceCustomerFilter: { value: '', innerHTML: '', appendChild: () => {} },
                acceptanceEngineerFilter: { value: '', innerHTML: '', appendChild: () => {} }
            };
            global.document = {
                getElementById: (id) => elemMap[id] || { addEventListener: () => {}, innerHTML: '', appendChild: () => {} },
                querySelector: (sel) => {
                    if (sel === '#acceptanceTable tbody') {
                        return {
                            innerHTML: '',
                            appendChild: (tr) => {
                                if (tr.classList && tr.classList.contains('table-warning')) warningAdded = true;
                                if (tr.dataset && tr.dataset.acceptanceId === '123') datasetOk = true;
                            },
                            querySelector: (s) => {
                                if (s === '.table-warning' && warningAdded) {
                                    return { scrollIntoView: () => { scrolled = true; } };
                                }
                                return null;
                            }
                        };
                    }
                    return null;
                },
                createElement: (tag) => {
                    const classes = new Set();
                    return {
                        tagName: tag,
                        dataset: {},
                        classList: {
                            add: (cls) => { classes.add(cls); if (cls === 'table-warning') warningAdded = true; },
                            contains: (cls) => classes.has(cls)
                        },
                        innerHTML: ''
                    };
                },
                addEventListener: (evt, cb) => {
                    if (evt === 'DOMContentLoaded') global.domContentLoadedCb = cb;
                }
            };
            global.SES = {
                i18n: { t: (k, d) => d },
                toast: { error: (msg) => console.error('TOAST: ' + msg) },
                escapeHtml: (s) => s,
                api: {
                    get: (url, params) => {
                        if (url === '/api/acceptances') {
                            apiRequestedParams = params;
                            return Promise.resolve({
                                records: [{ id: 123, contractId: 999, contractNo: 'CON-001', workMonth: '2026-09', status: '提出済' }]
                            });
                        }
                        return Promise.resolve([]);
                    }
                }
            };

            """ + jsCode + """

            // Execute DOMContentLoaded callback
            global.domContentLoadedCb();

            // Wait for Promise.then microtask resolution
            setTimeout(() => {
                if (!warningAdded) {
                    console.error('table-warning was not added to target row');
                    process.exit(1);
                }
                if (!scrolled) {
                    console.error('scrollIntoView was not called');
                    process.exit(1);
                }
                if (!apiRequestedParams || apiRequestedParams.acceptanceId !== '123') {
                    console.error('acceptanceId was not passed in API params: ' + JSON.stringify(apiRequestedParams));
                    process.exit(1);
                }
                if (!datasetOk) {
                    console.error('tr[data-acceptance-id] was not set on the target row');
                    process.exit(1);
                }
                console.log('SUCCESS');
                process.exit(0);
            }, 200);
            """;

        Path tempJs = Files.createTempFile("test_acceptance_", ".js");
        Files.writeString(tempJs, script);
        try {
            Process p = new ProcessBuilder("node", tempJs.toString())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(p.getInputStream().readAllBytes());
            boolean finished = p.waitFor(10, TimeUnit.SECONDS);
            assertTrue(finished && p.exitValue() == 0, "Acceptance JS runtime test failed: " + output);
        } finally {
            Files.deleteIfExists(tempJs);
        }
    }
}
