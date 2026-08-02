/*
 * SES.autocomplete の候補絞り込みロジックの検査（Node.jsで実行）。
 *
 * 「入力した文字で始まらない候補が上に出る」不具合の再発防止が目的。
 * ネイティブ <datalist> は option のラベル（最寄り駅では「都道府県 路線」）にも一致し、
 * さらに前方一致ではなく部分一致で絞り込むため、「東」と打つと駅名が「東」で始まらない
 * 候補が並んでいた。ここでは前方一致優先とラベル非照合を固定する。
 *
 * 実行: node src/test/resources/js/autocomplete-match-test.js
 * 失敗時は非0で終了し、標準出力に失敗内容を出す。
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const ROOT = path.resolve(__dirname, '../../../..');
const COMMON_JS = path.join(ROOT, 'src/main/resources/static/js/common.js');
const STATION_JSON = path.join(ROOT, 'src/main/resources/static/data/station_names.json');

// common.js はブラウザ前提のスクリプトなので、読み込み時に触る最小限のAPIだけ用意する。
function loadSes() {
    const noop = function () {};
    const jquery = function () { return { length: 0, on: noop, val: noop, find: noop }; };
    jquery.ajaxSetup = noop;
    const sandbox = {
        console: console,
        setTimeout: setTimeout,
        setInterval: noop,
        clearTimeout: clearTimeout,
        window: { addEventListener: noop },
        document: { addEventListener: noop, querySelectorAll: () => [], getElementById: () => null },
        $: jquery,
        jQuery: jquery
    };
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);
    // トップレベルの const SES はグローバルに載らないため、明示的に取り出す
    vm.runInContext(fs.readFileSync(COMMON_JS, 'utf8') + '\n;globalThis.__SES = SES;', sandbox, { filename: 'common.js' });
    return sandbox.__SES;
}

const SES = loadSes();
const auto = SES.autocomplete;
const failures = [];

function check(name, fn) {
    try {
        fn();
        console.log('  ok  - ' + name);
    } catch (e) {
        failures.push(name + ': ' + e.message);
        console.log('  NG  - ' + name + ' :: ' + e.message);
    }
}

// 実データ（駅名10,000件超）を候補に変換する。ラベルには意図的に都道府県を入れ、
// ラベルが照合対象になっていないことを確認する。
const stationsRaw = JSON.parse(fs.readFileSync(STATION_JSON, 'utf8'));
const seen = new Set();
const stations = auto.prepare(stationsRaw.filter(s => {
    if (seen.has(s.name)) return false;
    seen.add(s.name);
    return true;
}).map(s => ({ value: s.name, label: String(s.pref).split(/\s+/)[0] })));

console.log('駅候補: ' + stations.length + '件');

check('1文字入力の候補は先頭が必ずその文字で始まる', () => {
    ['東', '新', '大', 'あ', '田'].forEach(q => {
        const hits = auto.match(stations, q);
        assert.ok(hits.length > 0, q + ' の候補が0件');
        assert.ok(hits[0].value.startsWith(q), q + ' の先頭候補が前方一致でない: ' + hits[0].value);
    });
});

check('前方一致が部分一致より必ず先に並ぶ', () => {
    const hits = auto.match(stations, '田', 30);
    let seenPartial = false;
    hits.forEach(h => {
        const isPrefix = h.value.startsWith('田');
        if (!isPrefix) seenPartial = true;
        assert.ok(!(isPrefix && seenPartial), '部分一致の後ろに前方一致が来ている: ' + h.value);
    });
});

check('駅名に含まれない候補は返らない（ラベルは照合対象外）', () => {
    // 「東」は多くの都道府県名・路線名に含まれるため、datalist では駅名に「東」を含まない
    // 候補（例: 大阪府 JR東海道本線 の駅）が大量に出ていた。
    auto.match(stations, '東', 50).forEach(h => {
        assert.ok(h.value.indexOf('東') >= 0, '駅名に一致しない候補: ' + h.value + ' / ' + h.label);
    });
});

check('ラベルだけが一致する候補は返らない', () => {
    const items = auto.prepare([
        { value: '渋谷', label: '東京都' },
        { value: '東雲', label: '東京都' }
    ]);
    const hits = auto.match(items, '東京');
    assert.strictEqual(hits.length, 0, 'ラベル一致で候補が返っている: ' + JSON.stringify(hits));
});

check('全角英数と半角英数を同一視する（ＪＲ→JR）', () => {
    const items = auto.prepare([{ value: 'ＪＲ河内永和' }, { value: 'JR総持寺' }]);
    const hits = auto.match(items, 'jr');
    assert.strictEqual(hits.length, 2, '全角/半角のゆらぎを吸収できていない');
    assert.ok(hits.every(h => auto.normalize(h.value).startsWith('jr')));
});

check('カタカナとひらがなを同一視する', () => {
    const items = auto.prepare([{ value: 'ヒバリガオカ' }]);
    assert.strictEqual(auto.match(items, 'ひばり').length, 1);
});

check('空白・中黒のゆらぎを吸収する', () => {
    const items = auto.prepare([{ value: 'アイ・パーク' }]);
    assert.strictEqual(auto.match(items, 'あいぱ').length, 1);
});

check('空入力では候補を返さない', () => {
    assert.strictEqual(auto.match(stations, '').length, 0);
    assert.strictEqual(auto.match(stations, '   ').length, 0);
});

check('上限件数を超えない', () => {
    assert.ok(auto.match(stations, '新').length <= auto.LIMIT);
    assert.strictEqual(auto.match(stations, '新', 5).length, 5);
});

check('完全一致の駅名が候補の先頭に来る', () => {
    // 完全一致の駅名が実在するものだけを対象にする（'大阪' は駅名として登録が無い）
    ['東京', '新宿', '横浜'].forEach(q => {
        const hits = auto.match(stations, q);
        assert.strictEqual(hits[0].value, q, q + ' の先頭候補: ' + hits[0].value);
    });
    // 元データの並び順に依存せず先頭へ出ること
    const items = auto.prepare([{ value: '東京テレポート' }, { value: '東京ビッグサイト' }, { value: '東京' }]);
    assert.strictEqual(auto.match(items, '東京')[0].value, '東京');
});

if (failures.length > 0) {
    console.log('\n失敗 ' + failures.length + '件:\n' + failures.join('\n'));
    process.exit(1);
}
console.log('\nすべて成功');
