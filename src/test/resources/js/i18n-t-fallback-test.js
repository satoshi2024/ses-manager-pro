/*
 * SES.i18n.t の2引数 fallback（FIND-I18N-01）検査。
 * 実行: node src/test/resources/js/i18n-t-fallback-test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const ROOT = path.resolve(__dirname, '../../../..');
const COMMON_JS = path.join(ROOT, 'src/main/resources/static/js/common.js');

function loadSes(messages) {
    const noop = function () {};
    const jquery = function () { return { length: 0, on: noop, val: noop, find: noop }; };
    jquery.ajaxSetup = noop;
    const sandbox = {
        console: console,
        setTimeout: setTimeout,
        setInterval: noop,
        clearTimeout: clearTimeout,
        window: { SES_MESSAGES: messages || {}, addEventListener: noop },
        document: { addEventListener: noop, querySelectorAll: () => [], getElementById: () => null },
        $: jquery,
        jQuery: jquery
    };
    sandbox.globalThis = sandbox;
    vm.createContext(sandbox);
    vm.runInContext(fs.readFileSync(COMMON_JS, 'utf8') + '\n;globalThis.__SES = SES;', sandbox, { filename: 'common.js' });
    return sandbox.__SES;
}

const failures = [];
function check(name, fn) {
    try {
        fn();
        console.log('  ok  - ' + name);
    } catch (e) {
        failures.push(name + ': ' + e.message);
        console.log('  FAIL - ' + name + ': ' + e.message);
    }
}

console.log('SES.i18n.t fallback');
check('missing key with string fallback', () => {
    const SES = loadSes({});
    assert.strictEqual(SES.i18n.t('missing.key', 'Fallback'), 'Fallback');
});
check('existing key without placeholder ignores unused fallback', () => {
    const SES = loadSes({ 'leave.title': '休暇' });
    assert.strictEqual(SES.i18n.t('leave.title', 'Fallback'), '休暇');
});
check('existing key with {0} uses second arg as placeholder', () => {
    const SES = loadSes({ 'hello': 'Hi {0}' });
    assert.strictEqual(SES.i18n.t('hello', 'Polly'), 'Hi Polly');
});
check('numeric placeholder still works', () => {
    const SES = loadSes({ 'count': '{0}件' });
    assert.strictEqual(SES.i18n.t('count', 3), '3件');
});

if (failures.length) {
    console.error('\n' + failures.length + ' failure(s)');
    process.exit(1);
}
console.log('\nall ok');
