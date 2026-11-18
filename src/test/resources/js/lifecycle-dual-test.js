/*
 * 二者確認の画面部品（static/js/modules/lifecycle-dual.js、W3-21b）の振る舞い検査。
 * 実行: node src/test/resources/js/lifecycle-dual-test.js
 *
 * 実物の common.js（SES.i18n・SES.escapeHtml・SES.csrf）と lifecycle-dual.js を vm で読み込み、
 * DOM・fetch・bootstrap・Swal だけを差し替える。メッセージは空なので SES.i18n.t はkeyを返す。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');
const { webcrypto } = require('crypto');

const ROOT = path.resolve(__dirname, '../../../..');
const COMMON_JS = path.join(ROOT, 'src/main/resources/static/js/common.js');
const DUAL_JS = path.join(ROOT, 'src/main/resources/static/js/modules/lifecycle-dual.js');
const KEY_PATTERN = /^[A-Za-z0-9._~-]{1,190}$/;

function element(id, attributes) {
    return {
        id: id, value: '', textContent: '', innerHTML: '', hidden: true, disabled: false, dataset: {},
        attributes: Object.assign({}, attributes || {}),
        getAttribute(name) { return this.attributes[name] === undefined ? null : this.attributes[name]; },
        setAttribute(name, value) { this.attributes[name] = String(value); }
    };
}

function json(status, body) {
    return {
        status: status, redirected: false, url: '',
        headers: { get: () => 'application/json' },
        json: async () => body
    };
}

function ok(data) {
    return json(200, { code: 200, message: 'success', data: data });
}

/** 1画面分の環境。responders は fetch の呼出し順に応答（関数なら呼んだ結果、Errorなら投げる）。 */
function load(options) {
    const withDecision = options.withDecision;
    const ids = ['dualTaskName', 'dualSummary', 'dualNotice', 'dualSubmitSection', 'dualCandidateSelect',
        'dualSubmitComment', 'dualSubmitButton'];
    if (withDecision) ids.push('dualDecisionSection', 'dualDecisionComment', 'dualConfirmButton', 'dualRejectButton');
    const els = { dualConfirmModal: element('dualConfirmModal', { 'data-dual-api-base': options.apiBase }) };
    ids.forEach(id => { els[id] = element(id); });

    const calls = [];
    const toasts = [];
    const responders = [];
    const noop = function () {};
    const jquery = function () { return { length: 0, on: noop, val: noop, find: noop }; };
    jquery.ajaxSetup = noop;
    const sandbox = {
        // 部品が失敗時に出す console.error は検査の出力に混ぜない
        console: { log: noop, info: noop, warn: noop, error: noop, debug: noop },
        setTimeout: setTimeout,
        setInterval: noop,
        clearTimeout: clearTimeout,
        Uint8Array: Uint8Array,
        window: { SES_MESSAGES: {}, addEventListener: noop, crypto: webcrypto, location: { href: '' } },
        document: {
            addEventListener: noop,
            querySelectorAll: () => [],
            getElementById: id => els[id] || null,
            cookie: 'XSRF-TOKEN=csrf-token-1'
        },
        bootstrap: { Modal: { getOrCreateInstance: () => ({ show: noop, hide: noop }) } },
        Swal: { fire: async () => ({ isConfirmed: true }) },
        fetch: async (url, init) => {
            calls.push({ url: url, init: init });
            const next = responders.shift();
            if (!next) throw new Error('unexpected fetch ' + (init && init.method) + ' ' + url);
            const value = typeof next === 'function' ? await next() : next;
            if (value instanceof Error) throw value;
            return value;
        },
        $: jquery,
        jQuery: jquery
    };
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(COMMON_JS, 'utf8') + '\n;globalThis.__SES = SES;', sandbox, { filename: 'common.js' });
    vm.runInContext(fs.readFileSync(DUAL_JS, 'utf8'), sandbox, { filename: 'lifecycle-dual.js' });
    const SES = sandbox.__SES;
    SES.toast = {
        success: m => toasts.push(['success', m]),
        error: m => toasts.push(['error', m]),
        warning: m => toasts.push(['warning', m])
    };
    let changed = 0;
    SES.lifecycleDual.onChanged = async () => { changed++; };
    return {
        D: SES.lifecycleDual, els: els, calls: calls, toasts: toasts, responders: responders,
        changed: () => changed,
        posts: () => calls.filter(c => c.init && c.init.method === 'POST')
    };
}

const PENDING_VIEW = {
    taskId: 7, taskStatus: 'IN_PROGRESS', caseStatus: 'ACTIVE', taskGeneration: 3, state: 'PENDING', stale: false,
    submissionId: 55, submissionRevision: 2, evidenceSha256: 'ab'.repeat(32), submitterUserId: 2,
    commentsVisible: true, submissionComment: 'please', submissionCommentPresent: true,
    canSubmit: false, canDecide: true
};
const SUBMITTABLE_VIEW = { taskId: 7, taskStatus: 'IN_PROGRESS', caseStatus: 'ACTIVE', taskGeneration: 3, canSubmit: true };
const CANDIDATES = [
    { documentId: 10, documentVersionId: 21, versionNo: 2, title: 'skill-sheet', createdAt: '2026-11-16T10:00:00' },
    { documentId: 10, documentVersionId: 20, versionNo: 1, title: 'skill-sheet', createdAt: '2026-11-01T10:00:00' }
];

const failures = [];
async function check(name, fn) {
    try {
        await fn();
        console.log('  ok  - ' + name);
    } catch (e) {
        failures.push(name + ': ' + (e && e.stack || e));
        console.log('  FAIL - ' + name + ': ' + (e && e.message || e));
    }
}

async function main() {
    console.log('lifecycle-dual.js');

    await check('Idempotency-Keyは同じ操作と内容なら再利用し内容が変われば作り直す', async () => {
        const { D } = load({ withDecision: true, apiBase: '/api/lifecycle' });
        D.taskId = 7;
        const k1 = D.keyFor('SUBMIT', { a: 1 });
        assert.ok(KEY_PATTERN.test(k1), k1);
        assert.strictEqual(D.keyFor('SUBMIT', { a: 1 }), k1);
        const k2 = D.keyFor('SUBMIT', { a: 2 });
        assert.notStrictEqual(k2, k1);
        assert.notStrictEqual(D.keyFor('DECIDE:55', { a: 2 }), k2);
    });

    await check('提出はCLEAN版の版IDとCSRFとIdempotency-Keyを送り、通信エラー後の再送は同じkey、成功後は新しいkey', async () => {
        const env = load({ withDecision: true, apiBase: '/api/lifecycle' });
        env.responders.push(ok(SUBMITTABLE_VIEW), ok(CANDIDATES));
        await env.D.open(7);
        assert.strictEqual(env.els.dualSubmitSection.hidden, false);
        assert.ok(env.els.dualCandidateSelect.innerHTML.includes('value="10:21"'));
        assert.ok(env.els.dualCandidateSelect.innerHTML.includes('value="10:20"'));
        assert.strictEqual(env.els.dualCandidateSelect.disabled, false);

        env.els.dualCandidateSelect.value = '10:21';
        env.els.dualSubmitComment.value = 'hello';
        env.responders.push(new TypeError('Failed to fetch'));
        await env.D.submit();
        const first = env.posts()[0];
        assert.strictEqual(first.url, '/api/lifecycle/tasks/7/dual-confirmation/submissions');
        assert.strictEqual(first.init.headers['X-XSRF-TOKEN'], 'csrf-token-1');
        assert.strictEqual(first.init.headers['Content-Type'], 'application/json');
        const key1 = first.init.headers['Idempotency-Key'];
        assert.ok(KEY_PATTERN.test(key1), key1);
        assert.deepStrictEqual(JSON.parse(first.init.body), {
            taskGeneration: 3, documentSelections: [{ documentId: 10, documentVersionId: 21 }], comment: 'hello'
        });
        assert.ok(env.toasts.some(t => t[0] === 'error' && t[1] === 'lifecycle.dual.error.network'));

        env.responders.push(ok({ submissionId: 55, state: 'PENDING', replayed: true }),
            ok(Object.assign({}, SUBMITTABLE_VIEW, { state: 'PENDING', submissionId: 55, submissionRevision: 1 })),
            ok(CANDIDATES));
        await env.D.submit();
        assert.strictEqual(env.posts()[1].init.headers['Idempotency-Key'], key1, '再送は同じkey');
        assert.ok(env.toasts.some(t => t[0] === 'success' && t[1] === 'lifecycle.dual.toast.replayed'));
        assert.strictEqual(env.changed(), 1, '成功後にページのタスク一覧を読み直す');
        assert.strictEqual(env.D.pending, null);

        env.els.dualCandidateSelect.value = '10:21';
        env.responders.push(ok({ submissionId: 56, state: 'PENDING' }), ok(SUBMITTABLE_VIEW), ok(CANDIDATES));
        await env.D.submit();
        assert.notStrictEqual(env.posts()[2].init.headers['Idempotency-Key'], key1, '成功後の新しい操作は新しいkey');
    });

    await check('送信中は二重送信せずボタンを無効にする', async () => {
        const env = load({ withDecision: true, apiBase: '/api/lifecycle' });
        env.responders.push(ok(SUBMITTABLE_VIEW), ok(CANDIDATES));
        await env.D.open(7);
        env.els.dualCandidateSelect.value = '10:20';
        let release;
        env.responders.push(() => new Promise(resolve => { release = resolve; }));
        const firstSubmit = env.D.submit();
        await env.D.submit();
        assert.strictEqual(env.posts().length, 1);
        assert.strictEqual(env.els.dualSubmitButton.disabled, true);
        env.responders.push(ok(SUBMITTABLE_VIEW), ok(CANDIDATES));
        release(ok({ submissionId: 55, state: 'PENDING' }));
        await firstSubmit;
        assert.strictEqual(env.posts().length, 1);
        assert.strictEqual(env.D.busy, false);
    });

    await check('版を選ばない提出と版IDの無い候補は送らない（Q-9）', async () => {
        const env = load({ withDecision: true, apiBase: '/api/lifecycle' });
        env.responders.push(ok(SUBMITTABLE_VIEW), ok([{ documentId: 10, documentVersionId: null, versionNo: 1 }]));
        await env.D.open(7);
        assert.strictEqual(env.els.dualCandidateSelect.disabled, true);
        assert.ok(env.els.dualCandidateSelect.innerHTML.includes('lifecycle.dual.candidate.none'));
        assert.strictEqual(env.els.dualSubmitButton.disabled, true);
        await env.D.submit();
        assert.strictEqual(env.posts().length, 0);
        assert.ok(env.toasts.some(t => t[0] === 'warning' && t[1] === 'error.lifecycle.evidenceVersionRequired'));
    });

    await check('確認・差戻しはサーバーのmessageを403でも表示し、4xxの後は新しいkey、5xxの後の再送は同じkey', async () => {
        const env = load({ withDecision: true, apiBase: '/api/lifecycle' });
        env.responders.push(ok(PENDING_VIEW));
        await env.D.open(7);
        assert.strictEqual(env.els.dualDecisionSection.hidden, false);
        assert.strictEqual(env.els.dualConfirmButton.disabled, false);
        assert.strictEqual(env.els.dualSubmitSection.hidden, true);

        env.responders.push(json(403, { code: 403, message: 'SELF-CONFIRMATION-DENIED' }));
        await env.D.decide('CONFIRM');
        const first = env.posts()[0];
        assert.strictEqual(first.url, '/api/lifecycle/tasks/7/dual-confirmation/submissions/55/decision');
        assert.deepStrictEqual(JSON.parse(first.init.body), {
            submissionRevision: 2, evidenceSha256: 'ab'.repeat(32), decision: 'CONFIRM', comment: null
        });
        assert.ok(env.toasts.some(t => t[0] === 'error' && t[1] === 'SELF-CONFIRMATION-DENIED'));

        // 4xxはサーバーが拒否を決めた（何も保存されていない）ため、次は新しいkey
        env.responders.push(json(409, { code: 409, message: 'RETRY-EXHAUSTED' }), ok(PENDING_VIEW));
        await env.D.decide('CONFIRM');
        const second = env.posts()[1].init.headers['Idempotency-Key'];
        assert.notStrictEqual(second, first.init.headers['Idempotency-Key']);
        assert.ok(env.toasts.some(t => t[0] === 'error' && t[1] === 'RETRY-EXHAUSTED'));

        // 5xxは結果が分からないため、同じ内容の再送は同じkey（処理済みなら保存済みの結果が返る）
        env.responders.push(json(500, { code: 500, message: 'SERVER-ERROR' }));
        await env.D.decide('CONFIRM');
        const third = env.posts()[2].init.headers['Idempotency-Key'];
        assert.notStrictEqual(third, second);
        // 応答のdecisionは確認結果（CONFIRMED / REJECTED）。要求のCONFIRM / REJECTとは値域が違う（RB-PRERUN 6）。
        env.responders.push(ok({ state: 'CONFIRMED', decision: 'CONFIRMED', replayed: true }),
            ok(Object.assign({}, PENDING_VIEW, { state: 'CONFIRMED', canDecide: false })));
        await env.D.decide('CONFIRM');
        assert.strictEqual(env.posts()[3].init.headers['Idempotency-Key'], third);
        assert.ok(env.toasts.some(t => t[0] === 'success' && t[1] === 'lifecycle.dual.toast.replayed'));
        assert.strictEqual(env.D.pending, null);
    });

    await check('送信の成功後にページの再読込が失敗しても成功を表示し送信中を解除する', async () => {
        const env = load({ withDecision: true, apiBase: '/api/lifecycle' });
        env.responders.push(ok(PENDING_VIEW));
        await env.D.open(7);
        env.D.onChanged = async () => { throw new Error('page reload failed'); };
        env.responders.push(ok({ state: 'REJECTED', decision: 'REJECTED' }),
            ok(Object.assign({}, PENDING_VIEW, { state: 'REJECTED', canDecide: false })));
        await env.D.decide('REJECT');
        assert.ok(env.toasts.some(t => t[0] === 'success' && t[1] === 'lifecycle.dual.toast.rejected'));
        assert.strictEqual(env.D.busy, false);
    });

    await check('本人画面は確認・差戻しの欄が無く、確認の要求を送らない', async () => {
        const env = load({ withDecision: false, apiBase: '/api/my/lifecycle' });
        env.responders.push(ok(Object.assign({}, PENDING_VIEW, { canSubmit: true, canDecide: false })), ok(CANDIDATES));
        await env.D.open(7);
        assert.strictEqual(env.calls[0].url, '/api/my/lifecycle/tasks/7/dual-confirmation');
        assert.strictEqual(env.calls[1].url, '/api/my/lifecycle/tasks/7/dual-confirmation/candidates');
        assert.ok(env.els.dualNotice.textContent.includes('lifecycle.dual.notice.selfNoDecision'));
        env.D.view.canDecide = true;
        await env.D.decide('CONFIRM');
        assert.strictEqual(env.posts().length, 0);
    });

    await check('本文が返されないcommentは「コメントあり」だけを表示し、本文はescapeする', async () => {
        const env = load({ withDecision: true, apiBase: '/api/lifecycle' });
        env.responders.push(ok({
            taskId: 7, taskStatus: 'IN_PROGRESS', taskGeneration: 3, state: 'REJECTED', submissionId: 55,
            submissionRevision: 1, documentId: 10, documentVersionNo: 2, submitterUserId: 2, commentsVisible: false,
            submissionComment: null, submissionCommentPresent: true, decision: 'REJECTED', decisionComment: null,
            decisionCommentPresent: false, canSubmit: false, canDecide: false
        }));
        await env.D.open(7);
        const html = env.els.dualSummary.innerHTML;
        // 確認結果はAPIの値（REJECTED）のkeyで表示する。bundleにそのkeyがあることは LifecycleDualConfirmationScreenTest が見る
        assert.ok(html.includes('lifecycle.dual.decision.REJECTED'));
        assert.ok(html.includes('lifecycle.dual.comment.present'));
        assert.ok(html.includes('lifecycle.dual.comment.hidden'));
        assert.ok(html.includes('lifecycle.dual.comment.none'));

        const visible = load({ withDecision: true, apiBase: '/api/lifecycle' });
        visible.responders.push(ok(Object.assign({}, PENDING_VIEW, { submissionComment: '<b>x</b>' })));
        await visible.D.open(7);
        assert.ok(visible.els.dualSummary.innerHTML.includes('&lt;b&gt;x&lt;/b&gt;'));
        assert.ok(!visible.els.dualSummary.innerHTML.includes('<b>x</b>'));
    });

    if (failures.length > 0) {
        console.error('\n' + failures.length + ' failure(s)\n' + failures.join('\n'));
        process.exit(1);
    }
    console.log('all passed');
}

main().catch(e => {
    console.error(e);
    process.exit(1);
});
