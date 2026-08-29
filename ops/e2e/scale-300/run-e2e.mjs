import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '../../..');
const BASE = process.env.BASE_URL || 'http://localhost:8081';
const SPEC_DIR = path.join(REPO_ROOT, '.kiro', 'specs', 'scale-300-e2e');
const EVIDENCE_DIR = path.join(SPEC_DIR, 'evidence');

const USERS = [
  { role: '管理者', username: 'admin', password: 'admin123' },
  { role: '営業', username: 's300.sales01', password: 'Scale300!' },
  { role: 'HR', username: 's300.hr01', password: 'Scale300!' },
  { role: 'マネージャー', username: 's300.mgr01', password: 'Scale300!' },
  { role: '要員', username: 's300.member001', password: 'Scale300!' }
];

const PAGE_URLS = {
  dashboard: ['/dashboard'],
  engineer: ['/engineer/list'],
  customer: ['/customer/list'],
  project: ['/project/list'],
  proposal: ['/proposal/kanban'],
  contract: ['/contract/list', '/contract/gantt', '/contract/renewal-calendar'],
  ai: ['/ai/matching'],
  email: ['/email/template/list'],
  user: ['/user/list'],
  organization: ['/organization/list'],
  'work-record': ['/work-record'],
  invoice: ['/invoice'],
  analytics: ['/analytics', '/analytics/availability-calendar'],
  'system-config': ['/system-config'],
  'audit-log': ['/audit-log'],
  'sales-performance': ['/sales-performance'],
  candidate: ['/candidate/list'],
  'contract-document': ['/contract-document'],
  payroll: ['/payroll'],
  quotation: ['/quotation'],
  document: ['/document'],
  'bp-company': ['/bp-company'],
  'crm-lead': ['/crm/leads'],
  'crm-opportunity': ['/crm/opportunities'],
  approval: ['/approval'],
  'sales-order': ['/sales-order'],
  acceptance: ['/acceptance'],
  myLeave: ['/my/leave'],
  leaveManagement: ['/leave'],
  'compliance-gate': ['/compliance-gate'],
  'portal-admin': ['/portal-admin'],
  myDashboard: ['/my/dashboard'],
  myProfile: ['/my/profile'],
  myPayroll: ['/my/payroll'],
  myExpenses: ['/my/expenses'],
  myOneOnOnes: ['/my/one-on-ones'],
  mySurveys: ['/my/surveys'],
  engineerChangeRequests: ['/engineer-change-requests'],
  expenseManagement: ['/expenses'],
  oneOnOneManagement: ['/one-on-ones'],
  surveyManagement: ['/surveys'],
  'accounting-integration': ['/accounting/integration'],
  'digital-invoice': ['/digital-invoice'],
  'inbound-invoice': ['/inbound-invoice'],
  'ai-evaluation': ['/ai/evaluation'],
  lifecycle: ['/lifecycle'],
  'management-report': ['/management-reports'],
  'certification-learning-skill-gap': ['/certification-learning-skill-gap'],
  'document-archive': ['/document'],
  'my-timesheet': ['/my'],
  myLifecycle: ['/my/lifecycle'],
  myCertificationLearningGap: ['/my/certification-learning-skill-gap'],
  'monthly-closing': ['/monthly-closing'],
  'my-timesheet': ['/my/timesheet'],
  'resume-ingestion': ['/resume-ingestion'],
  'project-ingestion': ['/project-ingestion'],
  'bp-availability': ['/bp-availability/list'],
  'bp-availability-ingestion': ['/bp-availability-ingestion'],
  reconciliation: ['/reconciliation'],
  compliance: ['/compliance'],
  'management-accounting': ['/management-accounting'],
  'document-archive': ['/document/list'],
  'bp-company': ['/bp-company/list'],
  'crm-lead': ['/crm/leads'],
  'crm-opportunity': ['/crm/opportunities'],
  approval: ['/approval/inbox', '/approval/requests'],
  'sales-order': ['/sales-order'],
  acceptance: ['/acceptance'],
  todo: ['/todo'],
  myLeave: ['/my/leave'],
  leaveManagement: ['/leave'],
  'compliance-gate': ['/compliance-gate'],
  'portal-admin': ['/portal-admin'],
  myDashboard: ['/my/dashboard'],
  myProfile: ['/my/profile'],
  myPayroll: ['/my/payroll'],
  myExpenses: ['/my/expenses'],
  myOneOnOnes: ['/my/one-on-ones'],
  mySurveys: ['/my/surveys'],
  engineerChangeRequests: ['/engineer-change-requests'],
  expenseManagement: ['/expenses'],
  oneOnOneManagement: ['/one-on-ones'],
  surveyManagement: ['/surveys'],
  'accounting-integration': ['/accounting/integration'],
  'digital-invoice': ['/digital-invoice'],
  'inbound-invoice': ['/inbound-invoice'],
  'ai-evaluation': ['/ai/evaluation'],
  lifecycle: ['/lifecycle/list', '/lifecycle/templates'],
  myLifecycle: ['/my/lifecycle'],
  'management-report': ['/management-reports'],
  'certification-learning-skill-gap': ['/certification-learning-skill-gap'],
  myCertificationLearningGap: ['/my/certification-learning-skill-gap']
};

const EXTRA_PAGES = {
  '管理者': ['/engineer/detail?id=1001', '/engineer/detail?id=1252', '/customer/2001', '/bp-company/11001', '/candidate/detail?id=13001', '/approval/routes', '/resume-ingestion/review/1', '/project-ingestion/review/1', '/project/detail?id=5001'],
  '営業': ['/engineer/detail?id=1001', '/customer/2001', '/bp-company/11001', '/candidate/detail?id=13001', '/project/detail?id=5001'],
  'HR': ['/engineer/detail?id=1001', '/candidate/detail?id=13001', '/resume-ingestion/review/1', '/project/detail?id=5001'],
  'マネージャー': ['/engineer/detail?id=1001', '/customer/2001', '/project-ingestion/review/1', '/project/detail?id=5100'],
  '要員': ['/my/attendance']
};

const FORBIDDEN = {
  '管理者': [],
  '営業': ['/user/list', '/api/users'],
  'HR': ['/user/list', '/api/users'],
  'マネージャー': ['/user/list', '/api/users'],
  '要員': ['/dashboard', '/engineer/list', '/customer/list', '/api/engineers', '/user/list']
};

const MOBILE_KEY_MENUS = ['dashboard', 'engineer', 'customer', 'project', 'proposal', 'contract', 'work-record', 'invoice', 'candidate', 'quotation', 'bp-company', 'crm-lead', 'crm-opportunity', 'sales-performance', 'monthly-closing', 'user', 'analytics', 'approval', 'todo'];

const issues = [];
let issueSeq = 1;

function sanitize(s) {
  return String(s).replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, '_');
}

function record(issue) {
  const id = `E2E-${String(issueSeq++).padStart(3, '0')}`;
  issues.push({ id, ...issue });
  fs.appendFileSync(path.join(SPEC_DIR, 'e2e-issues.jsonl'), JSON.stringify({ id, ...issue }) + '\n', 'utf8');
  console.log(`[ISSUE] ${id} ${issue.role} ${issue.viewport} ${issue.page} ${issue.type}: ${issue.message}`);
}

async function login(context, user) {
  const page = await context.newPage();
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.fill('#username', user.username);
  await page.fill('#password', user.password);
  await page.click('button[type="submit"]');
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(1200);
  const url = page.url();
  await page.close();
  return url;
}

async function checkPage(context, user, viewportName, menuKey, url) {
  const page = await context.newPage();
  const consoleErrors = [];
  const pageErrors = [];
  page.on('console', (msg) => {
    if (msg.type() === 'error') consoleErrors.push(msg.text());
  });
  page.on('pageerror', (err) => pageErrors.push(String(err)));

  let status = null;
  try {
    const resp = await page.goto(`${BASE}${url}`, { waitUntil: 'domcontentloaded', timeout: 30000 });
    status = resp ? resp.status() : null;
  } catch (err) {
    record({ role: user.role, viewport: viewportName, page: menuKey, url, type: 'NAV_ERROR', message: String(err) });
    await page.close();
    return;
  }
  await page.waitForTimeout(2000);

  const data = await page.evaluate(() => {
    const bodyText = document.body ? document.body.innerText : '';
    return {
      title: document.title,
      bodyLen: bodyText.length,
      overflowX: document.documentElement.scrollWidth - window.innerWidth,
      h1: document.querySelector('h1') ? document.querySelector('h1').innerText : '',
      errorText: (bodyText.match(/エラーが発生しました|Internal Server Error|サーバーエラー|エラー/g) || []).slice(0, 3),
      rowCount: document.querySelectorAll('tbody tr').length,
      cardCount: document.querySelectorAll('.card').length,
      menuVisible: Array.from(document.querySelectorAll('aside a, .sidebar a, nav a')).some((a) => {
        const href = (a.getAttribute('href') || '').split('?')[0];
        return href.length > 1 && (window.location.pathname || '').startsWith(href);
      })
    };
  }).catch(() => ({ title: '', bodyLen: 0, overflowX: 0, h1: '', errorText: [], rowCount: 0, cardCount: 0, menuVisible: false }));

  const dir = path.join(EVIDENCE_DIR, viewportName, sanitize(user.role));
  fs.mkdirSync(dir, { recursive: true });
  const file = `${sanitize(menuKey)}_${sanitize(url)}_${Date.now()}.png`;
  try {
    await page.screenshot({ path: path.join(dir, file), fullPage: false });
  } catch (e) { /* ignore screenshot failure */ }

  if (status !== null && status >= 400) {
    record({ role: user.role, viewport: viewportName, page: menuKey, url, type: 'HTTP_' + status, message: `HTTP ${status}${data.menuVisible ? ' (sidebar menu visible)' : ''}` });
  }
  if (data.menuVisible && status !== null && status >= 400) {
    record({ role: user.role, viewport: viewportName, page: menuKey, url, type: 'MENU_SHOWN_BUT_FAIL', message: `menu link visible but page returns ${status}` });
  }
  for (const e of consoleErrors) {
    record({ role: user.role, viewport: viewportName, page: menuKey, url, type: 'CONSOLE_ERROR', message: e.slice(0, 300) });
  }
  for (const e of pageErrors) {
    record({ role: user.role, viewport: viewportName, page: menuKey, url, type: 'PAGE_ERROR', message: e.slice(0, 300) });
  }
  if (data.overflowX > 5) {
    record({ role: user.role, viewport: viewportName, page: menuKey, url, type: 'H_OVERFLOW', message: `horizontal overflow ${data.overflowX}px` });
  }
  if (data.errorText.length > 0) {
    record({ role: user.role, viewport: viewportName, page: menuKey, url, type: 'ERROR_TEXT', message: data.errorText.join(' | ') });
  }
  if (data.bodyLen === 0 && status !== null && status < 400) {
    record({ role: user.role, viewport: viewportName, page: menuKey, url, type: 'EMPTY_BODY', message: 'page body is empty' });
  }
  await page.close();
}

function loadRoleMenus() {
  const tsv = fs.readFileSync(path.join(__dirname, 'role-pages.tsv'), 'utf8');
  const map = {};
  for (const line of tsv.split(/\r?\n/).slice(1)) {
    if (!line.trim()) continue;
    const [role, menuKey] = line.split('\t');
    if (!map[role]) map[role] = new Set();
    map[role].add(menuKey);
  }
  return map;
}

async function runMatrix(browser, viewportName, mobile) {
  const roleMenus = loadRoleMenus();
  for (const user of USERS) {
    if (process.env.SMOKE && user.role !== '管理者' && user.role !== '要員') continue;
    const context = await browser.newContext({
      viewport: mobile ? { width: 390, height: 844 } : { width: 1440, height: 900 },
      locale: 'ja-JP',
      timezoneId: 'Asia/Tokyo'
    });
    const afterLogin = await login(context, user);
    if (!afterLogin || afterLogin.includes('/login')) {
      record({ role: user.role, viewport: viewportName, page: 'login', url: '/login', type: 'LOGIN_FAIL', message: `login failed for ${user.username}` });
      await context.close();
      continue;
    }
    const menus = [...(roleMenus[user.role] || [])];
    const pageSet = new Set();
    for (const menu of menus) {
      if (mobile && !MOBILE_KEY_MENUS.includes(menu) && user.role !== '管理者' && user.role !== '要員') continue;
      const urls = PAGE_URLS[menu];
      if (!urls) throw new Error(`Missing PAGE_URLS for menu: ${menu}`);
      for (const url of urls) pageSet.add({ menu, url });
    }
    for (const url of EXTRA_PAGES[user.role] || []) {
      pageSet.add({ menu: 'detail', url });
    }
    let pageCount = 0;
    for (const { menu, url } of pageSet) {
      if (process.env.SMOKE && pageCount >= 4) break;
      await checkPage(context, user, viewportName, menu, url);
      pageCount++;
    }

    // forbidden direct URL checks
    for (const url of FORBIDDEN[user.role] || []) {
      const page = await context.newPage();
      let status = null;
      try {
        const resp = await page.goto(`${BASE}${url}`, { waitUntil: 'domcontentloaded', timeout: 15000 });
        status = resp ? resp.status() : null;
      } catch (e) { status = 'error'; }
      await page.waitForTimeout(800);
      if (user.role !== '管理者' && status !== 403) {
        record({ role: user.role, viewport: viewportName, page: 'permission', url, type: 'PERMISSION', message: `expected 403 but got ${status}` });
      }
      await page.close();
    }
    await context.close();
  }
}

async function functionalChecks(browser) {
  const guard = async (name, fn) => {
    try {
      await fn();
    } catch (err) {
      record({ role: 'functional', viewport: 'desktop', page: name, url: '-', type: 'FUNCTIONAL_ERROR', message: String(err).slice(0, 300) });
    }
  };
  // 1. admin creates a customer via UI
  {
    await guard('customer_create', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
    await login(context, USERS[0]);
    const page = await context.newPage();
    await page.goto(`${BASE}/customer/list`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    await page.click('#btn-new-customer');
    await page.waitForTimeout(500);
    await page.fill('#cust-companyName', `シードE2E株式会社${Date.now() % 10000}`);
    await page.selectOption('#cust-commercialFlow', '元請け');
    await page.selectOption('#cust-trustLevel', 'A');
    await page.click('button[onclick="saveCustomer()"]');
    await page.waitForTimeout(1800);
    const modalOpen = await page.evaluate(() => document.getElementById('customerModal')?.classList.contains('show'));
    const toast = await page.evaluate(() => document.body.innerText.includes('登録しました'));
    fs.mkdirSync(path.join(EVIDENCE_DIR, 'desktop', 'functional'), { recursive: true });
    await page.screenshot({ path: path.join(EVIDENCE_DIR, 'desktop', 'functional', 'customer_create.png') });
    if (modalOpen || !toast) {
      record({ role: '管理者', viewport: 'desktop', page: 'customer', url: '/customer/list', type: 'FUNCTIONAL', message: 'customer create did not succeed' });
    }
    await page.close();
    await context.close();
    });
  }

  // 2. admin creates a task and moves it to COMPLETED
  {
    await guard('task_create', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
    await login(context, USERS[0]);
    const page = await context.newPage();
    await page.goto(`${BASE}/todo`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    await page.click('[data-bs-target="#taskModal"]');
    await page.waitForTimeout(500);
    await page.fill('#task-title', `E2Eタスク${Date.now() % 100000}`);
    await page.waitForFunction(() => document.querySelectorAll('#task-assignee-user-id option').length > 1, null, { timeout: 5000 });
    await page.selectOption('#task-assignee-user-id', { index: 1 });
    await page.click('button[onclick="saveTask()"]');
    await page.waitForTimeout(1800);
    const completeBtn = page.locator('button:has-text("完了")').first();
    const hasComplete = await completeBtn.count();
    if (hasComplete > 0) {
      await completeBtn.click();
      await page.waitForTimeout(1200);
    }
    await page.screenshot({ path: path.join(EVIDENCE_DIR, 'desktop', 'functional', 'task_create.png') });
    await page.close();
    await context.close();
    });
  }

  // 3. HR changes candidate stage via UI
  {
    await guard('candidate_stage', async () => {
    const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
    await login(context, USERS[2]);
    const page = await context.newPage();
    await page.goto(`${BASE}/candidate/detail?id=13001`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(1500);
    const stageBtn = page.locator('[data-bs-target="#stageModal"]');
    if (await stageBtn.count() > 0) {
      await stageBtn.first().click();
      await page.waitForTimeout(400);
      await page.selectOption('#stage-newStage', '書類選考');
      await page.click('button[onclick="saveStageChange()"]');
      await page.waitForTimeout(1500);
    }
    await page.screenshot({ path: path.join(EVIDENCE_DIR, 'desktop', 'functional', 'candidate_stage.png') });
    await page.close();
    await context.close();
    });
  }

  // 4. member my-timesheet renders
  {
    await guard('member_timesheet', async () => {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
    const afterLogin = await login(context, USERS[4]);
    const page = await context.newPage();
    await page.goto(`${BASE}/my/timesheet`, { waitUntil: 'domcontentloaded' });
    await page.waitForTimeout(2000);
    const hasGrid = await page.evaluate(() => document.body.innerText.includes('勤怠'));
    await page.screenshot({ path: path.join(EVIDENCE_DIR, 'mobile', 'functional', 'member_timesheet.png') });
    if (!hasGrid) {
      record({ role: '要員', viewport: 'mobile', page: 'my-timesheet', url: '/my/timesheet', type: 'FUNCTIONAL', message: 'member timesheet did not render' });
    }
    await page.close();
    await context.close();
    });
  }
}

async function concurrentLoginCheck(browser) {
  const users = [
    'admin', 's300.sales01', 's300.sales02', 's300.sales03', 's300.hr01',
    's300.mgr01', 's300.member001', 's300.member002', 's300.member003', 's300.member004'
  ];
  const results = await Promise.all(users.map(async (username) => {
    const password = username === 'admin' ? 'admin123' : 'Scale300!';
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    try {
      const page = await context.newPage();
      await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
      await page.fill('#username', username);
      await page.fill('#password', password);
      await Promise.all([
        page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 30000 }).catch(() => null),
        page.click('button[type="submit"]')
      ]);
      const ok = !page.url().includes('/login');
      await page.close();
      await context.close();
      return { username, ok };
    } catch (e) {
      await context.close().catch(() => {});
      return { username, ok: false, error: String(e) };
    }
  }));
  const failed = results.filter((r) => !r.ok);
  if (failed.length) {
    record({ role: 'multi', viewport: 'desktop', page: 'login', url: '/login', type: 'CONCURRENT_LOGIN', message: `${failed.length}/10 login failed: ${failed.map((f) => f.username).join(',')}` });
  }
}

async function main() {
  fs.mkdirSync(SPEC_DIR, { recursive: true });
  fs.mkdirSync(EVIDENCE_DIR, { recursive: true });
  // 前回実行分の蓄積を残さない（JSONは毎回上書き、JSONLは追記のため明示的にリセットする）
  fs.writeFileSync(path.join(SPEC_DIR, 'e2e-issues.jsonl'), '', 'utf8');
  const browser = await chromium.launch({
    headless: true
  });
  try {
    await runMatrix(browser, 'desktop', false);
    if (!process.env.SMOKE) {
      await runMatrix(browser, 'mobile', true);
      await functionalChecks(browser);
      await concurrentLoginCheck(browser);
    }
  } finally {
    await browser.close();
  }

  const report = {
    base: BASE,
    runAt: new Date().toISOString(),
    totalIssues: issues.length,
    issues
  };
  fs.writeFileSync(path.join(SPEC_DIR, 'e2e-issues.json'), JSON.stringify(report, null, 2), 'utf8');

  const lines = [
    '# 300人規模 E2E 自動検出結果',
    '',
    `- 実行日時: ${report.runAt}`,
    `- 対象URL: ${BASE}`,
    `- 検出問題数: ${issues.length}`,
    '',
    '| ID | ロール | ビューポート | ページ | 種別 | 内容 |',
    '|---|---|---|---|---|---|'
  ];
  for (const i of issues) {
    lines.push(`| ${i.id} | ${i.role} | ${i.viewport} | ${i.page} | ${i.type} | ${String(i.message).replace(/\|/g, '\\|').slice(0, 160)} |`);
  }
  fs.writeFileSync(path.join(SPEC_DIR, 'e2e-report.md'), lines.join('\n') + '\n', 'utf8');
  console.log(`done: ${issues.length} issues -> ${path.join(SPEC_DIR, 'e2e-issues.json')}`);
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
