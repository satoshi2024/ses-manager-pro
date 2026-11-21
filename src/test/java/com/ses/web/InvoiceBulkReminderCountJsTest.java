package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RB-PRERUN 追加 P2-1: 一括督促の結果画面の集計を、実物の {@code invoice.js} を Node.js で読み込んで確かめる。
 *
 * <p>メール送信は非同期のため、サーバーは各行に送信の受付（{@code QUEUED}）を返す。以前の画面は {@code SENT} と
 * {@code SKIPPED} 以外をすべて失敗に数えたため、送信を受け付けた督促が「失敗」と表示され、利用者の再送で督促が重複し得た。
 * {@code QUEUED}・{@code DRY_RUN} は「送信受付」に数え、{@code FAILED} だけを失敗に数えることを、集計関数と
 * 送信ボタンの処理（fetchの応答 → 件数の表示）の両方で固定する。DOM・fetch・bootstrap は最小の替身に差し替える。</p>
 */
class InvoiceBulkReminderCountJsTest {

    @Test
    void 一括督促の送信受付は失敗に数えず受付として表示する() throws IOException, InterruptedException {
        boolean nodeAvailable = false;
        try {
            Process check = new ProcessBuilder("node", "-v").start();
            nodeAvailable = check.waitFor(5, TimeUnit.SECONDS) && check.exitValue() == 0;
        } catch (Exception ignored) {
            // Node.js が無い環境
        }
        if ("true".equals(System.getenv("CI"))) {
            assertTrue(nodeAvailable, "CI環境では Node.js が必須です");
        } else {
            assumeTrue(nodeAvailable, "Node.js が利用できないため skip します");
        }

        String invoiceJs = Files.readString(Path.of("src/main/resources/static/js/modules/invoice.js"),
                StandardCharsets.UTF_8);
        String script = """
                const elements = {};
                function el(id) {
                    if (!elements[id]) {
                        elements[id] = { id: id, value: '', textContent: '', innerHTML: '', style: {},
                            addEventListener: () => {}, appendChild: () => {} };
                    }
                    return elements[id];
                }
                const resultRows = [];
                let resultModalShown = false;
                let reminderModalHidden = false;
                let posted = null;
                global.window = { invoiceCurrentPage: 1, location: { search: '' } };
                global.document = {
                    getElementById: el,
                    querySelector: (selector) => selector === '#bulkReminderResultTable tbody'
                        ? { innerHTML: '', appendChild: (tr) => resultRows.push(tr) } : null,
                    querySelectorAll: () => [],
                    createElement: (tag) => ({ tagName: tag, innerHTML: '' }),
                    addEventListener: () => {}
                };
                function Modal() { return { show: () => { resultModalShown = true; }, hide: () => {} }; }
                Modal.getInstance = () => ({ hide: () => { reminderModalHidden = true; } });
                global.bootstrap = { Modal: Modal, Tab: function () { return { show: () => {} }; } };
                global.SES = {
                    i18n: { t: (key, fallback) => (typeof fallback === 'string' ? fallback : key) },
                    escapeHtml: (value) => String(value),
                    csrf: { header: () => ({ 'X-XSRF-TOKEN': 'test' }) },
                    toast: { success: () => {}, error: () => {} }
                };
                global.alert = (message) => { console.error('unexpected alert: ' + message); process.exit(1); };
                const ROWS = [
                    { invoiceId: 1, status: 'QUEUED' },
                    { invoiceId: 2, status: 'QUEUED' },
                    { invoiceId: 3, status: 'DRY_RUN' },
                    { invoiceId: 4, status: 'SENT' },
                    { invoiceId: 5, status: 'SKIPPED', reason: 'error.invoice.reminderAlreadyPaid' },
                    { invoiceId: 6, status: 'FAILED', reason: 'error.invoice.reminderFailed' }
                ];
                global.fetch = (url, init) => {
                    posted = { url: url, init: init };
                    return Promise.resolve({ json: () => Promise.resolve({ code: 200, data: ROWS }) });
                };

                """ + invoiceJs + """

                function fail(message) { console.error(message); process.exit(1); }
                function expectCounts(actual, expected, label) {
                    const keys = ['sent', 'accepted', 'skipped', 'failed'];
                    for (const key of keys) {
                        if (actual[key] !== expected[key]) {
                            fail(label + ': ' + key + ' expected ' + expected[key] + ' but was ' + actual[key]
                                + ' ' + JSON.stringify(actual));
                        }
                    }
                }
                expectCounts(countBulkReminderResults(ROWS), { sent: 1, accepted: 3, skipped: 1, failed: 1 }, 'count');
                expectCounts(countBulkReminderResults([{ status: 'QUEUED' }]), { sent: 0, accepted: 1, skipped: 0, failed: 0 },
                    'queued only');
                expectCounts(countBulkReminderResults([{ status: 'UNKNOWN' }, null]), { sent: 0, accepted: 0, skipped: 0, failed: 2 },
                    'unknown');
                expectCounts(countBulkReminderResults(null), { sent: 0, accepted: 0, skipped: 0, failed: 0 }, 'empty');

                el('reminderTemplate').value = '9';
                isBulkReminder = true;
                selectedInvoiceIds = ROWS.map(row => row.invoiceId);
                submitReminder();
                setTimeout(() => {
                    if (!posted || posted.url !== '/api/invoices/reminders') fail('一括督促のAPIを呼んでいない: ' + JSON.stringify(posted));
                    if (el('bulkSentCount').textContent !== 1) fail('送信済: ' + el('bulkSentCount').textContent);
                    if (el('bulkAcceptedCount').textContent !== 3) fail('送信受付: ' + el('bulkAcceptedCount').textContent);
                    if (el('bulkSkippedCount').textContent !== 1) fail('スキップ: ' + el('bulkSkippedCount').textContent);
                    if (el('bulkFailedCount').textContent !== 1) fail('失敗: ' + el('bulkFailedCount').textContent);
                    if (resultRows.length !== ROWS.length) fail('結果の行数: ' + resultRows.length);
                    if (!reminderModalHidden || !resultModalShown) fail('モーダルの切替えが行われていない');
                    console.log('SUCCESS');
                    process.exit(0);
                }, 100);
                """;

        Path temp = Files.createTempFile("invoice_bulk_reminder_", ".js");
        Files.writeString(temp, script, StandardCharsets.UTF_8);
        try {
            Process process = new ProcessBuilder("node", temp.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(20, TimeUnit.SECONDS);
            assertTrue(finished && process.exitValue() == 0, "invoice.js の一括督促集計の検査が失敗しました: " + output);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * RB-POSTLAUNCH E-1: 単件督促も送信は非同期のため、成功の応答は送信の受付（QUEUED / DRY_RUN）である。
     * 成功の文言は「送信しました」ではなく受付を示し、4言語で翻訳され、画面の既定文言は ja の bundle と同じ。
     */
    @Test
    void 単件督促の成功文言は送信の完了ではなく受付を示す() throws IOException {
        java.util.Map<String, String> expected = java.util.Map.of(
                "messages.properties", "受け付け",
                "messages_en.properties", "accepted",
                "messages_ko.properties", "접수",
                "messages_zh_CN.properties", "受理");
        String ja = null;
        for (java.util.Map.Entry<String, String> bundle : expected.entrySet()) {
            java.util.Properties properties = new java.util.Properties();
            try (java.io.Reader reader = Files.newBufferedReader(Path.of("src/main/resources", bundle.getKey()),
                    StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            String value = properties.getProperty("invoice.reminder.sent");
            assertTrue(value != null && value.contains(bundle.getValue()), bundle.getKey() + ": " + value);
            if ("messages.properties".equals(bundle.getKey())) {
                ja = value;
            }
        }
        String invoiceJs = Files.readString(Path.of("src/main/resources/static/js/modules/invoice.js"),
                StandardCharsets.UTF_8);
        assertTrue(invoiceJs.contains("SES.i18n.t('invoice.reminder.sent', '" + ja + "')"), "既定文言は ja の bundle と同じ");
        assertTrue(!invoiceJs.contains("督促メールを送信しました"), "送信完了の文言を残さない");
    }
}
