package com.ses.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * RB-POSTLAUNCH B-1〜B-4・D-2: ライフサイクル画面の表示を、実物の {@code lifecycle.js}・{@code my-lifecycle.js}・
 * {@code lifecycle-dual.js} を Node.js で読み込んで確かめる（DOM・bootstrap・jQuery は最小の替身）。
 *
 * <ul>
 *   <li>B-1: 社内のタスク一覧の証跡列は code（DOCUMENT_LINK 等）ではなく表示名を出し、未知の code は code へ fallback する。</li>
 *   <li>B-2: 担当変更は利用者IDの数値入力ではなく、候補（氏名・username・role）から選ばせる。</li>
 *   <li>B-3: 案件一覧の進捗は一覧APIの件数（totalTasks・completedTasks）から出す（以前は全件 0/0）。</li>
 *   <li>B-4: 本人画面で未着手（先行タスク待ち）のタスクに「完了・提出」を出さない（完了APIは着手中だけを受け付ける）。</li>
 *   <li>D-2: 二者確認の提出者・確認者は名前で出し、本人画面で名前の無い他の利用者は「社内の担当者」と出す。</li>
 * </ul>
 */
class LifecycleScreenFixesJsTest {

    private static final Path MODULES = Path.of("src/main/resources/static/js/modules");

    @Test
    void ライフサイクル画面は進捗と証跡種別と担当者と未着手タスクの操作を正しく描画する()
            throws IOException, InterruptedException {
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

        String script = """
                const assert = require('assert');
                const elements = {};
                let decisionSection = null;
                function el(id) {
                    if (id === 'dualDecisionSection') return decisionSection;
                    if (!elements[id]) {
                        elements[id] = { id: id, value: '', textContent: '', innerHTML: '', disabled: false,
                            style: {}, addEventListener: () => {}, setAttribute: () => {}, getAttribute: () => null };
                    }
                    return elements[id];
                }
                const MESSAGES = {
                    'lifecycle.evidenceType.DOCUMENT_LINK': '書類リンク',
                    'lifecycle.evidenceType.NONE': 'なし',
                    'lifecycle.my.waitingPredecessor': '先行タスクの完了待ち',
                    'lifecycle.reassign.placeholder': '担当者を選択してください',
                    'lifecycle.reassign.empty': '選択できる担当者がいません',
                    'lifecycle.dual.label.internalStaff': '社内の担当者'
                };
                global.window = { location: { search: '' } };
                global.document = {
                    getElementById: el,
                    querySelector: () => null,
                    querySelectorAll: () => [],
                    addEventListener: () => {}
                };
                global.$ = () => ({ ready: () => {} });
                global.bootstrap = { Modal: function () { return { show: () => {}, hide: () => {} }; } };
                global.SES = {
                    i18n: {
                        t: (key, arg) => {
                            if (MESSAGES[key] !== undefined) return MESSAGES[key];
                            if (key === 'lifecycle.dual.label.userValue') return 'ユーザーID ' + arg[0];
                            return typeof arg === 'string' ? arg : key;
                        }
                    },
                    escapeHtml: (value) => String(value),
                    toast: { success: () => {}, error: () => {}, warning: () => {} },
                    csrf: { header: () => ({}) }
                };
                eval(require('fs').readFileSync(process.argv[2], 'utf8'));
                eval(require('fs').readFileSync(process.argv[3], 'utf8'));
                eval(require('fs').readFileSync(process.argv[4], 'utf8'));

                // B-3: 一覧は件数から進捗を出す（tasks は一覧APIに無い）
                SES.myLifecycle.renderCases([{ id: 7, lifecycleType: 'ASSIGNMENT', status: 'ACTIVE', anchorDate: '2026-12-01',
                    totalTasks: 2, completedTasks: 1, progressPercent: 50 }]);
                assert.ok(el('myCasesTableBody').innerHTML.includes('1/2'), 'my list progress');
                assert.ok(el('myCasesTableBody').innerHTML.includes('width: 50%'), 'my list bar');
                SES.lifecycle.renderCases([{ id: 8, caseNo: 'LC-8', lifecycleType: 'JOIN', status: 'ACTIVE',
                    engineerName: 'x', totalTasks: 3, completedTasks: 3 }]);
                assert.ok(el('casesTableBody').innerHTML.includes('3/3'), 'internal list progress');

                // B-4: 本人画面の未着手タスクには完了・提出を出さない
                SES.myLifecycle.renderDetailTasks([
                    { id: 1, status: 'PENDING', evidenceType: 'NONE', taskName: 'a' },
                    { id: 2, status: 'IN_PROGRESS', evidenceType: 'NONE', taskName: 'b' }], 'ACTIVE');
                const detail = el('myTasksTableBody').innerHTML;
                assert.strictEqual(detail.split('openDetailCompleteModal').length - 1, 1, 'only in-progress task');
                assert.ok(detail.includes('data-task-id="2"'), 'in-progress button');
                assert.ok(!detail.includes('data-task-id="1"'), 'no pending button');
                assert.ok(detail.includes('先行タスクの完了待ち'), 'pending reason');
                SES.myLifecycle.renderPendingTasks([
                    { id: 3, caseId: 7, status: 'PENDING', evidenceType: 'NONE', taskName: 'c' },
                    { id: 4, caseId: 7, status: 'IN_PROGRESS', evidenceType: 'NONE', taskName: 'd' }]);
                const pending = el('myPendingTasksList').innerHTML;
                assert.strictEqual(pending.split('openCompleteModal').length - 1, 1, 'pending list only in-progress');
                assert.ok(pending.includes('data-task-id="4"') && !pending.includes('data-task-id="3"'), 'pending ids');

                // B-1: 証跡列は表示名（未知のcodeはcode）
                SES.lifecycle.renderTasks([
                    { id: 5, status: 'PENDING', evidenceType: 'DOCUMENT_LINK', taskName: 'e', taskCode: 'E' },
                    { id: 6, status: 'PENDING', evidenceType: 'FUTURE_TYPE', taskName: 'f', taskCode: 'F' },
                    { id: 9, status: 'PENDING', evidenceType: null, taskName: 'g', taskCode: 'G' }], 'ACTIVE');
                const tasks = el('tasksTableBody').innerHTML;
                assert.ok(tasks.includes('>書類リンク</span>'), 'evidence label');
                assert.ok(!tasks.includes('>DOCUMENT_LINK</span>'), 'no raw code');
                assert.ok(tasks.includes('>FUTURE_TYPE</span>'), 'unknown code fallback');
                assert.ok(tasks.includes('>なし</span>'), 'null is NONE');

                // B-2: 担当変更は候補から選ぶ
                const select = { innerHTML: '', disabled: true };
                SES.lifecycle.renderReassignCandidates(select, [
                    { id: 22, username: 'yamada', realName: '山田 太郎', role: 'HR' },
                    { id: 23, username: 'sato', realName: null, role: '営業' },
                    { id: null, username: 'broken' }]);
                assert.ok(select.innerHTML.includes('<option value="22">山田 太郎 (yamada / HR)</option>'), select.innerHTML);
                assert.ok(select.innerHTML.includes('<option value="23">sato (営業)</option>'), select.innerHTML);
                assert.ok(!select.innerHTML.includes('broken'), 'no id');
                assert.strictEqual(select.disabled, false);
                const empty = { innerHTML: '', disabled: false };
                SES.lifecycle.renderReassignCandidates(empty, []);
                assert.ok(empty.innerHTML.includes('選択できる担当者がいません') && empty.disabled, 'empty candidates');

                // review P3-8: 社内専用（本人非公開）のタスクには要員を候補に出さない（サーバーも拒否する）
                const engineerCandidates = [
                    { id: 31, username: 'hr1', realName: '人事 一郎', role: 'HR' },
                    { id: 32, username: 'eng1', realName: '検証 二号', role: '要員' }];
                const internalSelect = { innerHTML: '', disabled: true };
                SES.lifecycle.renderReassignCandidates(internalSelect, engineerCandidates,
                    SES.lifecycle.engineerAssignable({ id: 5, isEngineerVisible: 0 }));
                assert.ok(internalSelect.innerHTML.includes('value="31"') && !internalSelect.innerHTML.includes('value="32"'),
                    'internal task excludes engineer role: ' + internalSelect.innerHTML);
                const visibleSelect = { innerHTML: '', disabled: true };
                SES.lifecycle.renderReassignCandidates(visibleSelect, engineerCandidates,
                    SES.lifecycle.engineerAssignable({ id: 6, isEngineerVisible: 1 }));
                assert.ok(visibleSelect.innerHTML.includes('value="31"') && visibleSelect.innerHTML.includes('value="32"'),
                    'visible task includes engineer role');
                assert.strictEqual(SES.lifecycle.engineerAssignable(undefined), false, 'unknown task excludes engineer');
                SES.lifecycle.renderTasks([{ id: 7, status: 'PENDING', evidenceType: 'NONE', taskName: 'h', taskCode: 'H',
                    isEngineerVisible: 1 }], 'ACTIVE');
                assert.strictEqual(SES.lifecycle.engineerAssignable(SES.lifecycle.tasksById['7']), true, 'tasks by id');

                // D-2: 名前があれば名前、本人画面で名前の無い他の利用者は「社内の担当者」、社内画面で名前が無ければID
                assert.strictEqual(SES.lifecycleDual.personLabel('検証 二号', 11), '検証 二号');
                assert.strictEqual(SES.lifecycleDual.personLabel(null, 22), '社内の担当者');
                decisionSection = {};
                assert.strictEqual(SES.lifecycleDual.personLabel('', 22), 'ユーザーID 22');
                assert.strictEqual(SES.lifecycleDual.personLabel(null, null), '-');
                console.log('OK');
                """;
        Path dir = Files.createTempDirectory("lifecycle-screen-js");
        Path file = dir.resolve("lifecycle-screen-test.js");
        Files.writeString(file, script, StandardCharsets.UTF_8);
        try {
            Process process = new ProcessBuilder("node", file.toString(),
                    MODULES.resolve("lifecycle-dual.js").toAbsolutePath().toString(),
                    MODULES.resolve("lifecycle.js").toAbsolutePath().toString(),
                    MODULES.resolve("my-lifecycle.js").toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Node.js の実行が終わらない");
            assertEquals(0, process.exitValue(), output);
            assertThat(output).contains("OK");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    /** B-2: 担当変更のモーダルは利用者IDの数値入力を持たず、候補を選ぶ select を持つ（候補は既存の公開項目を絞ったAPI）。 */
    @Test
    void 担当変更のモーダルは利用者IDの数値入力ではなく候補の選択肢を持つ() throws IOException {
        String html = Files.readString(Path.of("src/main/resources/templates/lifecycle/detail.html"),
                StandardCharsets.UTF_8);
        int start = html.indexOf("id=\"reassignTaskModal\"");
        assertThat(start).isGreaterThan(0);
        String modal = html.substring(start, html.indexOf("</form>", start));
        assertThat(modal).containsPattern("<select[^>]*id=\"reassignUserId\"")
                .contains("#{lifecycle.reassign.assignee}", "#{lifecycle.reassign.placeholder}")
                .doesNotContain("type=\"number\"", "ユーザーID");
        assertThat(Files.readString(MODULES.resolve("lifecycle.js"), StandardCharsets.UTF_8))
                .contains("SES.api.get('/api/autocomplete/assignable-users')")
                .doesNotContain("担当ユーザーIDを入力してください");
    }
}
