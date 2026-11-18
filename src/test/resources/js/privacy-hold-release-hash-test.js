// NF07-REL: hold 画面（privacy-holds.js）の理由の hash が、deploy-runbook §4.5 の test vector（Java と同じ正規形）と一致するか。
// 画面の script を Node の vm で読み込み、公開した SES.privacyHoldRelease.reasonHash を Web Crypto（Node の webcrypto）で呼ぶ。
'use strict';
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const crypto = require('crypto');

const source = fs.readFileSync(
    path.join(__dirname, '..', '..', '..', 'main', 'resources', 'static', 'js', 'modules', 'privacy-holds.js'), 'utf8');
const SES = {};
const context = {
    SES: SES,
    window: {SES: SES, crypto: crypto.webcrypto},
    TextEncoder: TextEncoder,
    Promise: Promise,
    console: console,
    // 画面の初期化（document ready）は登録だけで実行しない
    $: function () {
    }
};
vm.createContext(context);
vm.runInContext(source, context);

const cases = [
    {
        input: ['LITIGATION_CLOSED', 'TICKET-2001', '訴訟が終結したため保全を解除する'],
        expected: '8e3ccdd899ebae58d63b1319e0820aea0d7ff751194d7d57bee7d8873f5dacd5'
    }
];

if (!SES.privacyHoldRelease || typeof SES.privacyHoldRelease.reasonHash !== 'function') {
    console.error('SES.privacyHoldRelease.reasonHash が公開されていません');
    process.exit(1);
}

Promise.all(cases.map(function (c) {
    return SES.privacyHoldRelease.reasonHash(c.input[0], c.input[1], c.input[2]).then(function (actual) {
        return {input: c.input, expected: c.expected, actual: actual};
    });
})).then(function (results) {
    const failed = results.filter(function (r) {
        return r.actual !== r.expected;
    });
    if (failed.length > 0) {
        console.error('理由の hash が test vector と一致しません: ' + JSON.stringify(failed));
        process.exit(1);
    }
    // 境界をずらした入力は別の hash になる（長さ付き field）
    return Promise.all([
        SES.privacyHoldRelease.reasonHash('AB', 'C', 'x'),
        SES.privacyHoldRelease.reasonHash('A', 'BC', 'x')
    ]).then(function (pair) {
        if (pair[0] === pair[1]) {
            console.error('field の境界をずらした入力が同じ hash になりました');
            process.exit(1);
        }
        console.log('ok');
    });
}).catch(function (e) {
    console.error(e && e.stack ? e.stack : String(e));
    process.exit(1);
});
