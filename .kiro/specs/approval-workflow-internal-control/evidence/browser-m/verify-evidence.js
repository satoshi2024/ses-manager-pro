// verify-evidence.js — 記録済みbrowser evidenceをfail-closedで検証（再実行なし）
// 検証項目: 期待flow×viewportの完全一致 / summary件数10 / 重複禁止 / requestStatus=approved /
//           経路別JSON 10件 / PNG 40枚 / 各経路assertion（申請1件・申請者単独不変・適用1回・APPROVE action 1件・retry安定）
const fs = require('fs');
const path = require('path');
const EVIDENCE = __dirname;

const EXPECTED = [
  ['quotation-submit', 'desktop'], ['quotation-submit', '390px'],
  ['contract-activate', 'desktop'], ['contract-activate', '390px'],
  ['invoice-send', 'desktop'], ['invoice-send', '390px'],
  ['bp-payment-confirm', 'desktop'], ['bp-payment-confirm', '390px'],
  ['monthly-closing-confirm', 'desktop'], ['monthly-closing-confirm', '390px']
];
const STEP_CHECKS = [
  ['approval_request_created_once', 'requestCount', 1],
  ['business_operation_applied_once', 'targetStateChanged', true],
  ['approval_final_state', 'approveActionCount', 1],
  ['retry_approve_no_double_op', 'stateStable', true],
  ['applicant_alone_cannot_finalize', 'targetStateUnchanged', true]
];

const failures = [];
const fail = (msg) => failures.push(msg);

// 1) summary存在・件数10
const summaryPath = path.join(EVIDENCE, 'summary.json');
if (!fs.existsSync(summaryPath)) { fail('summary.json が存在しない'); process.exit(1); }
const summary = JSON.parse(fs.readFileSync(summaryPath, 'utf8'));
if (summary.length !== 10) fail('summary件数=' + summary.length + ' expected 10');

// 2) 期待flow×viewportの完全一致 + 重複禁止
const seen = new Set();
for (const [flow, vp] of EXPECTED) {
  const found = summary.filter(f => f.flow === flow && f.viewport === vp);
  if (found.length !== 1) fail('flow=' + flow + ':' + vp + ' 件数=' + found.length + ' expected 1');
  const key = flow + ':' + vp;
  if (seen.has(key)) fail('重複エントリ: ' + key);
  seen.add(key);
}
for (const f of summary) {
  const key = f.flow + ':' + f.viewport;
  if (!EXPECTED.some(([fl, v]) => fl === f.flow && v === f.viewport)) fail('未知のflow/viewport: ' + key);
}

// 3) 各経路assertion + requestStatus=approved + errorなし
for (const f of summary) {
  if (f.error) { fail(f.flow + ':' + f.viewport + ' run error: ' + f.error); continue; }
  for (const [stepName, field, expected] of STEP_CHECKS) {
    const step = f.steps.find(s => s.name === stepName);
    if (!step) { fail(f.flow + ':' + f.viewport + ' 欠落step ' + stepName); continue; }
    if (step[field] !== expected) fail(f.flow + ':' + f.viewport + ' ' + stepName + '.' + field + '=' + step[field] + ' expected ' + expected);
  }
  const final = f.steps.find(s => s.name === 'approval_final_state');
  if (!final || final.requestStatus !== 'approved') fail(f.flow + ':' + f.viewport + ' requestStatus=' + (final && final.requestStatus) + ' expected approved');
}

// 4) 経路別JSON 10件（summary.json以外）
const flowJson = fs.readdirSync(EVIDENCE).filter(n => /^[a-z-]+-(desktop|390px)\.json$/.test(n) && fs.statSync(path.join(EVIDENCE, n)).isFile());
if (flowJson.length !== 10) fail('経路別JSON=' + flowJson.length + ' expected 10');

// 5) PNG 40枚
function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(e => e.isDirectory() ? walk(path.join(dir, e.name)) : [path.join(dir, e.name)]);
}
const pngs = walk(EVIDENCE).filter(p => p.endsWith('.png'));
if (pngs.length !== 40) fail('PNG=' + pngs.length + ' expected 40');

if (failures.length > 0) {
  console.error('EVIDENCE ASSERT FAILED (' + failures.length + '):');
  failures.forEach(x => console.error(' - ' + x));
  process.exit(1);
}
console.log('OK: 10/10 flows × assertion, requestStatus=approved, JSON=10, PNG=40, duplicates=0');
