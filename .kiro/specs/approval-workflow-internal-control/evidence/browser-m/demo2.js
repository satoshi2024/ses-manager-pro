// S07 M browser demo — per-viewport targets (desktop / 390px)
const { chromium } = require('playwright-core');
const fs = require('fs');
const path = require('path');

const BASE = process.env.BASE_URL || 'http://localhost:8080';
const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe';
const EVIDENCE = process.env.EVIDENCE_DIR || 'C:\\Users\\pc\\Documents\\ses-manager-pro\\.kiro\\specs\\approval-workflow-internal-control\\evidence\\browser-m';

const APPLICANT = { username: 'sales1', password: 'sales123' };
const CLOSING_APPLICANT = { username: 'mgr1', password: 'mgr123' };
const APPROVER = { username: 'admin', password: 'admin123' };

function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

async function login(page, user) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      await page.goto(BASE + '/login', { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(300);
      await page.fill('#username', user.username);
      await page.fill('#password', user.password);
      await Promise.all([
        page.waitForURL(u => !u.pathname.includes('/login'), { timeout: 30000 }),
        page.click('button[type="submit"]')
      ]);
      await page.waitForLoadState('domcontentloaded');
      return;
    } catch (e) {
      if (attempt === 3) throw e;
      await page.waitForTimeout(1500);
    }
  }
}

async function gotoReliable(page, url) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
      await page.waitForLoadState('domcontentloaded');
      await page.waitForTimeout(600);
      return;
    } catch (e) {
      if (attempt === 3) throw e;
      await page.waitForTimeout(1200);
    }
  }
}

async function shot(page, label, dir) {
  const file = path.join(dir, label + '.png');
  await page.screenshot({ path: file, fullPage: false });
  return file;
}

async function apiGet(page, url) {
  return await page.evaluate(async (u) => {
    const res = await fetch(u, { credentials: 'same-origin' });
    return await res.json();
  }, url);
}

async function clickApply(spec, appPage) {
  if (spec.prep) { await spec.prep(appPage); }
  if (spec.clickApply) { return await spec.clickApply(appPage); }
  const clicked = await appPage.evaluate((n) => {
    const btns = Array.from(document.querySelectorAll('button'));
    const b = btns.find(x => (x.getAttribute('onclick') || '').includes(n));
    if (!b) return false;
    b.click(); return true;
  }, spec.buttonOnclick);
  if (!clicked) throw new Error('apply button not found: ' + spec.buttonOnclick);
  if (spec.afterApplyClick) { await spec.afterApplyClick(appPage); }
  return true;
}

async function countApprovalRequests(page, requestType, targetId, nonTerminalOnly) {
  const data = await apiGet(page, BASE + '/api/approval/requests?view=mine&page=1&pageSize=200');
  const records = (data.data && data.data.records) || [];
  return records.filter(r => r.requestType === requestType && (targetId == null || String(r.targetId) === String(targetId)) && (!nonTerminalOnly || ['requested','in_review','returned'].includes(r.status)));
}

async function runFlow(browser, spec, viewport) {
  const dir = path.join(EVIDENCE, spec.key + '-' + viewport.name);
  fs.mkdirSync(dir, { recursive: true });
  const results = { flow: spec.key, name: spec.name, viewport: viewport.name, url: BASE, steps: [] };
  const step = (name, detail) => { results.steps.push({ name, ...(detail || {}) }); console.log('[' + spec.key + ':' + viewport.name + '] ' + name, JSON.stringify(detail || {})); };

  const t = spec.targets[viewport.name];
  const ctxApp = await browser.newContext({ viewport: { width: viewport.w, height: viewport.h } });
  const appPage = await ctxApp.newPage();
  appPage.on('dialog', d => d.accept('2026-08-01').catch(() => {}));
  const applicant = spec.applicant === 'mgr' ? CLOSING_APPLICANT : APPLICANT;
  await login(appPage, applicant);
  await appPage.waitForTimeout(600);
  step('applicant_login', { user: applicant.username, url: appPage.url() });

  await gotoReliable(appPage, BASE + t.applicantPage);
  await shot(appPage, '1-business-page', dir);
  step('applicant_page', { url: t.applicantPage, title: await appPage.title() });

  const beforeStatus = await t.readTarget(appPage);
  step('target_before_apply', beforeStatus.data || beforeStatus);

  await clickApply({ ...spec, buttonOnclick: t.buttonOnclick, clickApply: t.clickApply, afterApplyClick: t.afterApplyClick, prep: t.prep }, appPage);
  await appPage.waitForTimeout(1500);
  // retry (sequential second click) — same command -> same idempotency key -> 1 request only
  await clickApply({ ...spec, buttonOnclick: t.buttonOnclick, clickApply: t.clickApply, afterApplyClick: t.afterApplyClick }, appPage).catch(() => false);
  await appPage.waitForTimeout(2000);
  await shot(appPage, '2-after-apply-retry', dir);
  const afterApply = await t.readTarget(appPage);
  step('target_after_apply', afterApply.data || afterApply);

  const unchanged = t.sameState(beforeStatus, afterApply);
  step('applicant_alone_cannot_finalize', { targetStateUnchanged: unchanged, before: beforeStatus.data || beforeStatus, after: afterApply.data || afterApply });
  if (!unchanged) { throw new Error('ASSERT: 申請者単独では対象状態が変化しないこと'); }

  const reqs = await countApprovalRequests(appPage, spec.requestType, t.targetId, spec.requestType === 'closing.confirm');
  step('approval_request_created_once', { requestCount: reqs.length, requests: reqs.map(r => ({ id: r.id, requestNo: r.requestNo, status: r.status, targetId: r.targetId })) });
  if (reqs.length !== 1) { throw new Error('ASSERT: 二重click/retryでも申請は1件のみであること (actual=' + reqs.length + ')'); }
  await ctxApp.close();

  const ctxAppr = await browser.newContext({ viewport: { width: viewport.w, height: viewport.h } });
  const apprPage = await ctxAppr.newPage();
  apprPage.on('dialog', d => d.accept('2026-08-01').catch(() => {}));
  await login(apprPage, APPROVER);
  await apprPage.waitForTimeout(600);
  step('approver_login', { user: APPROVER.username });

  const reqId = reqs.length > 0 ? reqs[0].id : null;
  if (!reqId) throw new Error('no approval request created for ' + spec.key);
  await gotoReliable(apprPage, BASE + '/approval/requests/' + reqId);
  await shot(apprPage, '3-request-detail-before-approve', dir);

  // double-click approve -> 2nd click must be idempotent no-op
  await apprPage.waitForSelector('button[data-action="approve"]', { timeout: 15000 }).catch(() => {});
  const approveClicked = await apprPage.evaluate(() => {
    const b = document.querySelector('button[data-action="approve"]');
    if (!b) return false;
    b.click(); b.click();
    return true;
  });
  if (!approveClicked) throw new Error('approve button not found for ' + reqId);
  await apprPage.waitForTimeout(3000);
  await gotoReliable(apprPage, BASE + '/approval/requests/' + reqId);
  await shot(apprPage, '4-request-detail-after-approve', dir);

  const afterApprove = await t.readTarget(apprPage);
  step('target_after_approve', afterApprove.data || afterApprove);
  const targetStateChanged = !t.sameState(beforeStatus, afterApprove);
  step('business_operation_applied_once', { targetStateChanged, after: afterApprove.data || afterApprove });
  if (!targetStateChanged) { throw new Error('ASSERT: 承認後に業務操作が1回適用され対象状態が変化すること'); }

  const detail = await apiGet(apprPage, BASE + '/api/approval/requests/' + reqId);
  const actions = (detail.data && detail.data.actions) || [];
  const approveActionCount = actions.filter(a => a.action === 'APPROVE').length;
  step('approval_final_state', { requestStatus: detail.data && detail.data.status, approveActionCount, actions: actions.map(a => ({ action: a.action, stepNo: a.stepNo })) });
  if (approveActionCount !== 1) { throw new Error('ASSERT: 承認時二重clickでもAPPROVE actionは1件のみであること (actual=' + approveActionCount + ')'); }

  // retry approve after completion -> no second business op
  await apprPage.evaluate(() => { const b = document.querySelector('button[data-action="approve"]'); if (b) b.click(); });
  await apprPage.waitForTimeout(1500);
  const afterRetry = await t.readTarget(apprPage);
  const retryStable = t.sameState(afterApprove, afterRetry);
  step('retry_approve_no_double_op', { stateStable: retryStable });
  if (!retryStable) { throw new Error('ASSERT: retry後も業務操作は再適用されないこと'); }
  await ctxAppr.close();
  return results;
}

function statusReader(getUrl) {
  return async (page) => apiGet(page, BASE + getUrl);
}
function statusSame(a, b) { return a.data && b.data && a.data.status === b.data.status; }

const bpReader = (month, id) => async (page) => {
  const data = await apiGet(page, BASE + '/api/invoices/bp-payments?month=' + month);
  const list = (data.data || []).filter(x => String(x.id) === String(id));
  return { data: list.length > 0 ? { id: list[0].id, status: list[0].status, amount: list[0].amount } : { id, status: '(not found)' } };
};

const bpPrep = async (page) => {
  await page.evaluate(() => { const m = document.getElementById('bpWorkMonth'); if (m) m.value = '2026-07'; });
  await page.evaluate(() => { const b = document.getElementById('btnSearchBpPayment'); if (b) b.click(); });
  await page.waitForTimeout(1800);
};
const closingReader = (month) => async (page) => apiGet(page, BASE + '/api/monthly-closing/summary?month=' + month);
const closingSame = (a, b) => a.data && b.data && a.data.closed === b.data.closed;

const specs = [
  {
    key: 'quotation-submit', name: '見積提出', requestType: 'quotation.submit', applicant: 'sales',
    targets: {
      desktop: { targetId: 1, applicantPage: '/quotation', buttonOnclick: "changeQuotationStatus(1, '提出済')", readTarget: statusReader('/api/quotations/1'), sameState: statusSame },
      '390px': { targetId: 2, applicantPage: '/quotation', buttonOnclick: "changeQuotationStatus(2, '提出済')", readTarget: statusReader('/api/quotations/2'), sameState: statusSame }
    }
  },
  {
    key: 'contract-activate', name: '契約稼動化', requestType: 'contract.activate', applicant: 'sales',
    targets: {
      desktop: {
        targetId: 2, applicantPage: '/contract/list', buttonOnclick: 'changeContractStatus(2,', readTarget: statusReader('/api/contracts/2'), sameState: statusSame,
        afterApplyClick: async (page) => {
          await page.waitForSelector('.swal2-modal', { timeout: 6000 });
          await page.waitForTimeout(400);
          await page.evaluate(() => { const sel = document.querySelector('.swal2-modal select'); if (sel) { sel.value = '稼動中'; sel.dispatchEvent(new Event('change', { bubbles: true })); } });
          await page.click('.swal2-confirm');
          await page.waitForTimeout(1500);
        }
      },
      '390px': {
        targetId: 3, applicantPage: '/contract/list', buttonOnclick: 'changeContractStatus(3,', readTarget: statusReader('/api/contracts/3'), sameState: statusSame,
        afterApplyClick: async (page) => {
          await page.waitForSelector('.swal2-modal', { timeout: 6000 });
          await page.waitForTimeout(400);
          await page.evaluate(() => { const sel = document.querySelector('.swal2-modal select'); if (sel) { sel.value = '稼動中'; sel.dispatchEvent(new Event('change', { bubbles: true })); } });
          await page.evaluate(() => { const c = document.querySelector('.swal2-confirm'); if (c) c.click(); });
          await page.waitForTimeout(1500);
        }
      }
    }
  },
  {
    key: 'invoice-send', name: '請求送付', requestType: 'invoice.send', applicant: 'sales',
    targets: {
      desktop: { targetId: 1, applicantPage: '/invoice?month=2026-07', buttonOnclick: "updateInvoiceStatus(1, '送付済')", readTarget: statusReader('/api/invoices/1'), sameState: statusSame },
      '390px': { targetId: 2, applicantPage: '/invoice?month=2026-07', buttonOnclick: "updateInvoiceStatus(2, '送付済')", readTarget: statusReader('/api/invoices/2'), sameState: statusSame }
    }
  },
  {
    key: 'bp-payment-confirm', name: 'BP支払確定', requestType: 'bp_payment.confirm', applicant: 'sales',
    targets: {
      desktop: { targetId: 1, applicantPage: '/invoice?month=2026-07', buttonOnclick: "updateBpPaymentStatus(1, '支払済')", prep: bpPrep, readTarget: bpReader('2026-07', 1), sameState: statusSame },
      '390px': { targetId: 2, applicantPage: '/invoice?month=2026-07', buttonOnclick: "updateBpPaymentStatus(2, '支払済')", prep: bpPrep, readTarget: bpReader('2026-07', 2), sameState: statusSame }
    }
  },
  {
    key: 'monthly-closing-confirm', name: '月次締め', requestType: 'closing.confirm', applicant: 'mgr',
    targets: {
      desktop: {
        targetId: null, applicantPage: '/monthly-closing', readTarget: closingReader('2026-05'), sameState: closingSame,
        clickApply: async (page) => {
          await page.evaluate(() => { const m = document.getElementById('closingMonth'); if (m) m.value = '2026-05'; });
          await page.evaluate(() => { const b = document.getElementById('btnLoadClosing'); if (b) b.click(); });
          await page.waitForTimeout(1800);
          const st = await page.evaluate(() => { const b = document.getElementById('btnConfirmClosing'); if (!b) return 'missing'; if (b.disabled) return 'disabled'; b.click(); return 'clicked'; });
          if (st !== 'clicked') throw new Error('btnConfirmClosing ' + st);
          await page.waitForTimeout(1500);
        }
      },
      '390px': {
        targetId: null, applicantPage: '/monthly-closing', readTarget: closingReader('2026-04'), sameState: closingSame,
        clickApply: async (page) => {
          await page.evaluate(() => { const m = document.getElementById('closingMonth'); if (m) m.value = '2026-04'; });
          await page.evaluate(() => { const b = document.getElementById('btnLoadClosing'); if (b) b.click(); });
          await page.waitForTimeout(1800);
          const st = await page.evaluate(() => { const b = document.getElementById('btnConfirmClosing'); if (!b) return 'missing'; if (b.disabled) return 'disabled'; b.click(); return 'clicked'; });
          if (st !== 'clicked') throw new Error('btnConfirmClosing ' + st);
          await page.waitForTimeout(1500);
        }
      }
    }
  }
];

(async () => {
  const browser = await chromium.launch({ executablePath: CHROME, headless: true });
  const viewports = [
    { name: 'desktop', w: 1440, h: 900 },
    { name: '390px', w: 390, h: 844 }
  ];
  const all = [];
  for (const spec of specs) {
    for (const vp of viewports) {
      try {
        const r = await runFlow(browser, spec, vp);
        all.push(r);
        fs.writeFileSync(path.join(EVIDENCE, spec.key + '-' + vp.name + '.json'), JSON.stringify(r, null, 2));
      } catch (e) {
        console.error('FLOW FAILED ' + spec.key + ':' + vp.name, e.message);
        all.push({ flow: spec.key, viewport: vp.name, error: e.message });
      }
    }
  }
  fs.writeFileSync(path.join(EVIDENCE, 'summary.json'), JSON.stringify(all, null, 2));
  await browser.close();
  const failures = all.filter(f => f.error);
  if (failures.length > 0) {
    console.error('FAILED flows: ' + failures.map(f => f.flow + ':' + f.viewport).join(', '));
    process.exit(1);
  }
  console.log('DONE all PASS. evidence at ' + EVIDENCE);
})();
