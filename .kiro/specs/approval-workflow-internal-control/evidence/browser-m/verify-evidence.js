// verify-evidence.js — 記録済みbrowser evidence JSONがassertionを満たすことを再確認（再実行なし）
const fs = require('fs');
const path = require('path');
const EVIDENCE = __dirname;
const summaryPath = path.join(EVIDENCE, 'summary.json');
const summary = JSON.parse(fs.readFileSync(summaryPath, 'utf8'));
const failures = [];
const checks = [
  ['approval_request_created_once', 'requestCount', 1],
  ['business_operation_applied_once', 'targetStateChanged', true],
  ['approval_final_state', 'approveActionCount', 1],
  ['retry_approve_no_double_op', 'stateStable', true],
  ['applicant_alone_cannot_finalize', 'targetStateUnchanged', true]
];
for (const f of summary) {
  if (f.error) { failures.push(f.flow + ':' + f.viewport + ' (run error)'); continue; }
  for (const [stepName, field, expected] of checks) {
    const step = f.steps.find(s => s.name === stepName);
    if (!step) { failures.push(f.flow + ':' + f.viewport + ' missing step ' + stepName); continue; }
    if (step[field] !== expected) { failures.push(f.flow + ':' + f.viewport + ' ' + stepName + '.' + field + '=' + step[field] + ' expected ' + expected); }
  }
}
if (failures.length > 0) {
  console.error('EVIDENCE ASSERT FAILED:');
  failures.forEach(x => console.error(' - ' + x));
  process.exit(1);
}
console.log('All ' + summary.length + ' flows satisfy assertions: requestCount=1, applicantAloneUnchanged=true, appliedOnce=true, approveActionCount=1, retryStable=true');
