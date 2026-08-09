import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '../../..');
const BASE = process.env.BASE_URL || 'http://localhost:8081';
const SPEC_DIR = path.join(REPO_ROOT, '.kiro', 'specs', 'scale-300-e2e');
const ROUND2_DIR = path.join(SPEC_DIR, 'round2');
const FULL_DIR = path.join(ROUND2_DIR, 'evidence', 'full');
const SELECTED_DIR = path.join(ROUND2_DIR, 'evidence', 'selected');

const USERS = [
  { role: '管理者', username: 'admin', password: 'admin123' },
  { role: '営業', username: 's300.sales01', password: 'Scale300!' },
  { role: 'HR', username: 's300.hr01', password: 'Scale300!' },
  { role: 'マネージャー', username: 's300.mgr01', password: 'Scale300!' },
  { role: '要員', username: 's300.member001', password: 'Scale300!' }
];

const issues = [];
const checks = [];
let issueSeq = 1;
const seenIssueKeys = new Set();

function sanitize(s) {
  return String(s).replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, '_');
}

function record(issue) {
  const id = `R2-${String(issueSeq++).padStart(3, '0')}`;
  issues.push({ id, ...issue });
  const key = `${issue.role}|${issue.viewport}|${issue.page}|${issue.url}|${issue.type}|${issue.message}`;
  seenIssueKeys.add(key);
  console.log(`[ISSUE] ${id} ${issue.role} ${issue.viewport} ${issue.page} ${issue.url} ${issue.type}: ${issue.message}`);
}

function recordUnique(issue) {
  const key = `${issue.role}|${issue.viewport}|${issue.page}|${issue.url}|${issue.type}|${issue.message}`;
  if (seenIssueKeys.has(key)) return;
  record(issue);
}

function check(name, detail, ok = true) {
  checks.push({ name, detail, ok });
  console.log(`[CHECK] ${ok ? 'OK ' : 'NG '} ${name}: ${detail}`);
}

async function login(context, user) {
  const page = await context.newPage();
  await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded' });
  await page.fill('#username', user.username);
  await page.fill('#password', user.password);
  await page.click('button[type="submit"]');
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(1200);
  return page;
}

async function auditPage(context, opts) {
  const { role, viewport, pageName, url, waitMs = 2200, selected = false, ignoreErrorText = false, expectedStatuses = [] } = opts;
  const page = await context.newPage();
  const pageErrors = [];
  const consoleErrors = [];
  const badResponses = [];
  page.on('pageerror', (e) => pageErrors.push(String(e.stack || e)));
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('response', (r) => { if (r.status() >= 400) badResponses.push(`${r.status()} ${r.url()}`); });

  let status = null;
  try {
    const resp = await page.goto(`${BASE}${url}`, { waitUntil: 'domcontentloaded', timeout: 30000 });
    status = resp ? resp.status() : null;
  } catch (e) {
    recordUnique({ role, viewport, page: pageName, url, type: 'NAV_ERROR', message: String(e).slice(0, 300) });
  }
  await page.waitForTimeout(waitMs);

  const data = await page.evaluate(() => ({
    bodyLen: document.body ? document.body.innerText.length : 0,
    overflowX: document.documentElement.scrollWidth - window.innerWidth,
    h1: document.querySelector('h1') ? document.querySelector('h1').innerText : '',
    rows: document.querySelectorAll('tbody tr').length,
    errorText: (document.body ? document.body.innerText : '').match(/エラーが発生しました|Internal Server Error|サーバーエラー|エラー/g) || []
  })).catch(() => ({ bodyLen: 0, overflowX: 0, h1: '', rows: 0, errorText: [] }));

  const dir = selected ? SELECTED_DIR : FULL_DIR;
  const sub = path.join(dir, viewport, sanitize(role), sanitize(pageName));
  fs.mkdirSync(sub, { recursive: true });
  const file = `${sanitize(url)}_${Date.now()}.png`;
  try { await page.screenshot({ path: path.join(sub, file), fullPage: false }); } catch (e) { /* ignore */ }

  if (status !== null && status >= 400 && !expectedStatuses.includes(status)) {
    recordUnique({ role, viewport, page: pageName, url, type: 'HTTP_' + status, message: `HTTP ${status}` });
  }
  for (const e of pageErrors) {
    recordUnique({ role, viewport, page: pageName, url, type: 'PAGE_ERROR', message: e.slice(0, 500), stack: e.slice(0, 900) });
  }
  for (const e of consoleErrors.slice(0, 3)) {
    if (expectedStatuses.some((s) => e.includes(`status of ${s}`))) continue;
    recordUnique({ role, viewport, page: pageName, url, type: 'CONSOLE_ERROR', message: e.slice(0, 300) });
  }
  for (const r of badResponses.slice(0, 3)) {
    if (expectedStatuses.some((s) => r.startsWith(`${s} `) && r.endsWith(url))) continue;
    recordUnique({ role, viewport, page: pageName, url, type: 'RESPONSE_4XX', message: r.slice(0, 300) });
  }
  if (data.overflowX > 5) {
    recordUnique({ role, viewport, page: pageName, url, type: 'H_OVERFLOW', message: `horizontal overflow ${data.overflowX}px` });
  }
  if (data.errorText.length > 0 && status === 200 && !ignoreErrorText) {
    recordUnique({ role, viewport, page: pageName, url, type: 'ERROR_TEXT', message: data.errorText.slice(0, 3).join(' | ') });
  }
  if (data.bodyLen === 0 && status !== null && status < 400) {
    recordUnique({ role, viewport, page: pageName, url, type: 'EMPTY_BODY', message: 'page body is empty' });
  }

  const result = { status, pageErrors, consoleErrors, badResponses, data };
  await page.close();
  return result;
}

async function paginationCheck(context, opts) {
  const { role = '管理者', viewport = 'desktop', pageName, url, apiUrl, loadFn, pageSize, tbodySelector = 'tbody tr', pageHolder } = opts;
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.stack || e)));
  await page.goto(`${BASE}${url}`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(1800);

  let total = null;
  let pages = null;
  try {
    const res = await page.request.get(`${BASE}${apiUrl}`);
    if (res.ok()) {
      const body = await res.json();
      if (body.code === 200 && body.data && body.data.total !== undefined) {
        total = body.data.total;
        pages = body.data.pages;
      }
    }
  } catch (e) { /* api info only */ }

  if (pages === null || pages <= 1) {
    check(`pagination:${pageName}`, `pages=${pages}, total=${total}`, false);
    await page.close();
    return { total, pages, errors };
  }

  const lastPageRows = await page.evaluate(async ({ fn, n }) => {
    window[fn](n);
    return true;
  }, { fn: loadFn, n: pages });
  await page.waitForTimeout(1200);
  const rowsOnLast = await page.locator(tbodySelector).count();
  const activePageText = await page.evaluate((holder) => {
    const el = holder ? document.querySelector(holder) : document.querySelector('.pagination .page-item.active a, .pagination .page-item.active span');
    return el ? el.innerText.trim() : '';
  }, pageHolder);
  check(`pagination:${pageName}`, `total=${total}, pages=${pages}, lastPageRows=${rowsOnLast}, activePage=${activePageText}`, rowsOnLast > 0 && String(activePageText).includes(String(pages)));
  if (rowsOnLast === 0 || !String(activePageText).includes(String(pages))) {
    recordUnique({ role, viewport, page: pageName, url, type: 'PAGINATION', message: `last page check failed: rows=${rowsOnLast}, active=${activePageText}, expected=${pages}` });
  }
  for (const e of errors) {
    recordUnique({ role, viewport, page: pageName, url, type: 'PAGE_ERROR', message: e.slice(0, 300) });
  }
  await page.close();
  return { total, pages, rowsOnLast, errors };
}

async function searchCheck(context, opts) {
  const { role = '管理者', viewport = 'desktop', pageName, url, apiUrl, keyword, selectors, loadFn, expectContains } = opts;
  const page = await context.newPage();
  await page.goto(`${BASE}${url}`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(1500);

  let filled = null;
  for (const sel of selectors) {
    const count = await page.locator(sel).count();
    if (count > 0) {
      await page.fill(sel, keyword);
      filled = sel;
      break;
    }
  }
  if (!filled) {
    check(`search:${pageName}`, `no input found among ${selectors.join(',')}`, false);
    await page.close();
    return;
  }
  await page.evaluate((fn) => { if (window[fn]) window[fn](1); }, loadFn);
  await page.waitForTimeout(1200);
  const bodyText = await page.evaluate(() => document.body.innerText);
  const ok = expectContains ? bodyText.includes(expectContains) : bodyText.includes(keyword);
  check(`search:${pageName}`, `keyword=${keyword} on ${filled}, found=${ok}`);
  if (!ok) {
    recordUnique({ role, viewport, page: pageName, url, type: 'SEARCH', message: `search "${keyword}" did not show expected result` });
  }
  await page.close();
}

async function modalCheck(context, opts) {
  const { role = '管理者', viewport = 'desktop', pageName, url, modalId, triggers, waitMs = 700 } = opts;
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e.stack || e)));
  await page.goto(`${BASE}${url}`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(1500);

  let clicked = null;
  for (const sel of triggers) {
    const count = await page.locator(sel).count();
    if (count > 0) {
      await page.locator(sel).first().click().catch(() => {});
      clicked = sel;
      break;
    }
  }
  await page.waitForTimeout(waitMs);
  const state = await page.evaluate((id) => {
    const m = document.getElementById(id);
    if (!m) return { exists: false };
    const inputs = Array.from(m.querySelectorAll('input,select,textarea')).filter((el) => el.offsetParent !== null);
    return { exists: true, visible: m.classList.contains('show'), inputs: inputs.length };
  }, modalId);

  if (!clicked) {
    check(`modal:${pageName}`, `${modalId} trigger not found`, false);
    recordUnique({ role, viewport, page: pageName, url, type: 'MODAL', message: `modal ${modalId} trigger not found` });
  } else if (!state.exists || !state.visible) {
    check(`modal:${pageName}`, `${modalId} did not open`, false);
    recordUnique({ role, viewport, page: pageName, url, type: 'MODAL', message: `modal ${modalId} did not open (trigger ${clicked})` });
  } else {
    check(`modal:${pageName}`, `${modalId} opened, visible fields=${state.inputs}`, state.inputs > 0);
    if (state.inputs === 0) {
      recordUnique({ role, viewport, page: pageName, url, type: 'MODAL', message: `modal ${modalId} opened but no visible form fields` });
    }
  }
  for (const e of errors) {
    recordUnique({ role, viewport, page: pageName, url, type: 'PAGE_ERROR', message: e.slice(0, 300) });
  }
  await page.close();
}

async function mobileSidebarCheck(context) {
  const page = await context.newPage();
  await page.goto(`${BASE}/dashboard`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(1800);
  const toggle = page.locator('#sidebar-toggle-btn');
  const visibleBefore = await page.evaluate(() => document.getElementById('sidebar')?.classList.contains('show') || false);
  if (await toggle.count() > 0) {
    await toggle.click();
    await page.waitForTimeout(600);
  }
  const visibleAfter = await page.evaluate(() => document.getElementById('sidebar')?.classList.contains('show') || false);
  const closeBtn = page.locator('#sidebar-close-btn');
  if (await closeBtn.count() > 0) {
    await closeBtn.click();
    await page.waitForTimeout(400);
  }
  const visibleAfterClose = await page.evaluate(() => document.getElementById('sidebar')?.classList.contains('show') || false);
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  check('mobile:sidebar-toggle', `before=${visibleBefore}, afterOpen=${visibleAfter}, afterClose=${visibleAfterClose}, overflow=${overflow}`, !visibleBefore && visibleAfter && !visibleAfterClose);
  if (!visibleAfter) {
    recordUnique({ role: '管理者', viewport: 'mobile', page: 'dashboard', url: '/dashboard', type: 'MOBILE_SIDEBAR', message: `sidebar toggle did not open drawer (before=${visibleBefore}, after=${visibleAfter})` });
  }
  if (overflow > 5) {
    recordUnique({ role: '管理者', viewport: 'mobile', page: 'dashboard', url: '/dashboard', type: 'H_OVERFLOW', message: `mobile sidebar overflow ${overflow}px` });
  }
  await page.close();
}

async function globalSearchCheck(context) {
  const page = await context.newPage();
  await page.goto(`${BASE}/dashboard`, { waitUntil: 'domcontentloaded', timeout: 30000 });
  await page.waitForTimeout(1500);
  const btn = page.locator('#global-search-btn');
  if (await btn.count() === 0) {
    check('global-search', 'button not found', false);
    await page.close();
    return;
  }
  await btn.click();
  await page.waitForTimeout(500);
  await page.fill('#global-search-input', '田中');
  await page.waitForTimeout(1800);
  const text = await page.evaluate(() => document.getElementById('global-search-results')?.innerText || '');
  check('global-search', `keyword=田中, resultText=${text.slice(0, 60).replace(/\n/g, ' ')}`, text.includes('田中 太郎'));
  if (!text.includes('田中 太郎')) {
    recordUnique({ role: '管理者', viewport: 'desktop', page: 'global-search', url: '/dashboard', type: 'SEARCH', message: 'global search for 田中 did not return 田中 太郎' });
  }
  await page.close();
}

async function apiPermissionCheck(context, role) {
  const urls = [
    '/api/engineers?current=1&size=3',
    '/api/customers?current=1&size=3',
    '/api/users?current=1&size=3',
    '/api/role-menus',
    '/api/approval/inbox',
    '/api/notifications',
    '/api/my/timesheet'
  ];
  for (const url of urls) {
    try {
      const res = await context.request.get(`${BASE}${url}`, { failOnStatusCode: false });
      let ct = res.headers()['content-type'] || '';
      let body = '';
      try { body = (await res.text()).slice(0, 120); } catch (e) { /* ignore */ }
      check(`api:${role}:${url}`, `status=${res.status()} ${ct.includes('json') ? 'json' : ct}`, true);
      const isUsersOrRoles = url.includes('/api/users') || url.includes('/api/role-menus');
      if (role !== '管理者' && isUsersOrRoles && res.status() !== 403) {
        recordUnique({ role, viewport: 'desktop', page: 'api-permission', url, type: 'API_PERMISSION', message: `${url} expected 403 but got ${res.status()}` });
      }
      if (role === '要員' && (url.includes('/api/engineers') || url.includes('/api/customers')) && res.status() === 200) {
        recordUnique({ role, viewport: 'desktop', page: 'api-permission', url, type: 'API_PERMISSION', message: `${url} should be denied for 要員 but got 200` });
      }
      if (res.status() === 403 && ct.includes('html')) {
        recordUnique({ role, viewport: 'desktop', page: 'api-permission', url, type: 'API_PERMISSION', message: `${url} 403 returned HTML instead of JSON (content-type=${ct}) body=${body}` });
      }
    } catch (e) {
      check(`api:${role}:${url}`, `request error ${String(e).slice(0, 120)}`, false);
    }
  }
}

async function concurrentLoginCheck(browser) {
  const names = ['admin'];
  for (const i of [1, 2, 3, 4, 5, 6, 8, 9, 10]) names.push(`s300.sales${String(i).padStart(2, '0')}`);
  for (let i = 1; i <= 3; i++) names.push(`s300.hr${String(i).padStart(2, '0')}`);
  for (let i = 1; i <= 3; i++) names.push(`s300.mgr${String(i).padStart(2, '0')}`);
  for (let i = 1; i <= 15; i++) names.push(`s300.member${String(i).padStart(3, '0')}`);
  names.push('s300.member253', 's300.member254', 's300.member255');

  const results = await Promise.all(names.map(async (username) => {
    const context = await browser.newContext({ viewport: { width: 1280, height: 800 } });
    try {
      const page = await context.newPage();
      await page.goto(`${BASE}/login`, { waitUntil: 'domcontentloaded', timeout: 20000 });
      await page.fill('#username', username);
      await page.fill('#password', username === 'admin' ? 'admin123' : 'Scale300!');
      await Promise.all([
        page.waitForNavigation({ waitUntil: 'domcontentloaded', timeout: 20000 }).catch(() => null),
        page.click('button[type="submit"]')
      ]);
      const ok = !page.url().includes('/login');
      await page.close();
      await context.close();
      return { username, ok };
    } catch (e) {
      await context.close().catch(() => {});
      return { username, ok: false, error: String(e).slice(0, 120) };
    }
  }));
  const failed = results.filter((r) => !r.ok);
  check('concurrent-login', `${results.length - failed.length}/${results.length} succeeded`, failed.length === 0);
  if (failed.length) {
    recordUnique({ role: 'multi', viewport: 'desktop', page: 'login', url: '/login', type: 'CONCURRENT_LOGIN', message: `${failed.length}/${results.length} failed: ${failed.map((f) => f.username).join(',')} ${failed.map((f) => f.error || '').join(' ')}` });
  }
}

async function legacyMemberCheck(browser) {
  const legacy = [
    { username: 's300.member253', name: '田中 太郎' },
    { username: 's300.member254', name: '山田 花子' },
    { username: 's300.member255', name: '伊藤 健太' }
  ];
  for (const u of legacy) {
    const context = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
    const page = await login(context, { role: '要員', username: u.username, password: 'Scale300!' });
    await page.goto(`${BASE}/my/timesheet`, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await page.waitForTimeout(1800);
    const text = await page.evaluate(() => document.body.innerText);
    const ok = !page.url().includes('/login') && (text.includes('勤怠') || text.includes('田中') || text.includes('山田') || text.includes('伊藤'));
    check(`legacy-member:${u.username}`, `url=${page.url()}, nameFound=${text.includes(u.name.split(' ')[0])}`, ok);
    if (!ok) {
      recordUnique({ role: '要員', viewport: 'mobile', page: 'my-timesheet', url: '/my/timesheet', type: 'LEGACY_LOGIN', message: `${u.username} (${u.name}) could not open timesheet` });
    }
    fs.mkdirSync(path.join(SELECTED_DIR, 'mobile', 'legacy'), { recursive: true });
    await page.screenshot({ path: path.join(SELECTED_DIR, 'mobile', 'legacy', `${u.username}_timesheet.png`) }).catch(() => {});
    await context.close();
  }
}

async function run() {
  fs.mkdirSync(ROUND2_DIR, { recursive: true });
  fs.mkdirSync(FULL_DIR, { recursive: true });
  fs.mkdirSync(SELECTED_DIR, { recursive: true });
  const browser = await chromium.launch({
    headless: true,
    executablePath: process.env.CHROMIUM_PATH || 'C:\\Users\\satos\\AppData\\Local\\ms-playwright\\chromium-1228\\chrome-win64\\chrome.exe'
  });

  try {
    // 1) known-bug pages with full stacks
    {
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
      await login(context, USERS[0]);
      await auditPage(context, { role: '管理者', viewport: 'desktop', pageName: 'contract-gantt', url: '/contract/gantt', selected: true });
      await auditPage(context, { role: '管理者', viewport: 'desktop', pageName: 'proposal-kanban', url: '/proposal/kanban', selected: true });
      await auditPage(context, { role: '管理者', viewport: 'desktop', pageName: 'engineer-detail', url: '/engineer/detail?id=1001', selected: true });
      await auditPage(context, { role: '管理者', viewport: 'desktop', pageName: 'engineer-detail-legacy', url: '/engineer/detail?id=1' });
      await auditPage(context, { role: '管理者', viewport: 'desktop', pageName: 'dashboard', url: '/dashboard', selected: true });
      await context.close();
    }

    // 2) pagination across scale data
    {
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
      await login(context, USERS[0]);
      await paginationCheck(context, { pageName: 'engineer', url: '/engineer/list', apiUrl: '/api/engineers?current=1&size=10', loadFn: 'loadEngineers', pageSize: 10 });
      await paginationCheck(context, { pageName: 'customer', url: '/customer/list', apiUrl: '/api/customers?current=1&size=10', loadFn: 'loadCustomers', pageSize: 10 });
      await paginationCheck(context, { pageName: 'project', url: '/project/list', apiUrl: '/api/projects?current=1&size=10', loadFn: 'loadProjects', pageSize: 10 });
      await paginationCheck(context, { pageName: 'contract', url: '/contract/list', apiUrl: '/api/contracts?current=1&size=20', loadFn: 'loadContracts', pageSize: 20, pageHolder: '#contract-page-position' });
      await paginationCheck(context, { pageName: 'todo', url: '/todo', apiUrl: '/api/tasks/page?current=1&size=20', loadFn: 'loadTasks', pageSize: 20, tbodySelector: '#task-table-body tr', pageHolder: '#task-pagination .page-item.active a, #task-pagination .page-item.active span' });
      await paginationCheck(context, { pageName: 'candidate', url: '/candidate/list', apiUrl: '/api/candidates?current=1&size=10', loadFn: 'loadCandidates', pageSize: 10 });
      await searchCheck(context, { pageName: 'engineer', url: '/engineer/list', keyword: '田中', selectors: ['#searchName'], loadFn: 'loadEngineers', expectContains: '田中' });
      await globalSearchCheck(context);
      await context.close();
    }

    // 3) detail pages and account link card
    {
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
      await login(context, USERS[0]);
      for (const id of ['1001', '1252', '1', '2', '3']) {
        await auditPage(context, { role: '管理者', viewport: 'desktop', pageName: `detail-${id}`, url: `/engineer/detail?id=${id}` });
      }
      const page = await context.newPage();
      await page.goto(`${BASE}/engineer/detail?id=1`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1800);
      const linkText = await page.evaluate(() => document.getElementById('account-link-current')?.innerText || '');
      check('engineer-detail:account-link', `id=1 linkText=${linkText}`, linkText.includes('397'));
      if (!linkText.includes('397')) {
        recordUnique({ role: '管理者', viewport: 'desktop', page: 'engineer-detail', url: '/engineer/detail?id=1', type: 'ACCOUNT_LINK', message: `legacy engineer id=1 account link not shown (${linkText})` });
      }
      await page.close();
      await context.close();
    }

    // 4) modals
    {
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
      await login(context, USERS[0]);
      await modalCheck(context, { pageName: 'engineer-modal', url: '/engineer/list', modalId: 'engineerModal', triggers: ['[data-bs-target="#engineerModal"]'] });
      await modalCheck(context, { pageName: 'customer-modal', url: '/customer/list', modalId: 'customerModal', triggers: ['[data-bs-target="#customerModal"]'] });
      await modalCheck(context, { pageName: 'contract-modal', url: '/contract/list', modalId: 'contractModal', triggers: ['button[onclick="openNewContract()"]', '[data-bs-target="#contractModal"]'] });
      await modalCheck(context, { pageName: 'task-modal', url: '/todo', modalId: 'taskModal', triggers: ['[data-bs-target="#taskModal"]', 'button[onclick*="openNewTaskModal"]'] });
      await context.close();
    }

    // 5) error pages and permission pages
    {
      const adminCtx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
      await login(adminCtx, USERS[0]);
      await auditPage(adminCtx, { role: '管理者', viewport: 'desktop', pageName: 'not-found', url: '/definitely-not-found', selected: true, expectedStatuses: [404] });
      await auditPage(adminCtx, { role: '管理者', viewport: 'desktop', pageName: 'error-page', url: '/error', selected: true, ignoreErrorText: true });
      await auditPage(adminCtx, { role: '管理者', viewport: 'desktop', pageName: 'approval-routes', url: '/approval/routes' });
      await auditPage(adminCtx, { role: '管理者', viewport: 'desktop', pageName: 'route-skill-tag', url: '/skill-tag' });
      await auditPage(adminCtx, { role: '管理者', viewport: 'desktop', pageName: 'route-search', url: '/search' });
      await auditPage(adminCtx, { role: '管理者', viewport: 'desktop', pageName: 'route-tasks', url: '/tasks' });
      await auditPage(adminCtx, { role: '管理者', viewport: 'desktop', pageName: 'route-saved-views', url: '/saved-views' });
      await auditPage(adminCtx, { role: '管理者', viewport: 'desktop', pageName: 'route-batch-operations', url: '/batch-operations' });
      await adminCtx.close();

      for (const u of [USERS[1], USERS[2], USERS[3]]) {
        const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
        await login(ctx, u);
        await auditPage(ctx, { role: u.role, viewport: 'desktop', pageName: 'approval-inbox', url: '/approval/inbox', selected: u.role === '営業' });
        await auditPage(ctx, { role: u.role, viewport: 'desktop', pageName: 'approval-requests', url: '/approval/requests' });
        await auditPage(ctx, { role: u.role, viewport: 'desktop', pageName: 'approval-routes', url: '/approval/routes', expectedStatuses: [403] });
        if (u.role === 'マネージャー') {
          await auditPage(ctx, { role: u.role, viewport: 'desktop', pageName: 'project-list', url: '/project/list' });
        }
        await ctx.close();
      }
      const memberCtx = await browser.newContext({ viewport: { width: 1440, height: 900 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo' });
      await login(memberCtx, USERS[4]);
      await auditPage(memberCtx, { role: '要員', viewport: 'desktop', pageName: 'forbidden-dashboard', url: '/dashboard', expectedStatuses: [403] });
      await memberCtx.close();
    }

    // 6) API permission matrix
    for (const u of USERS) {
      const ctx = await browser.newContext({ viewport: { width: 1280, height: 800 } });
      await login(ctx, u);
      await apiPermissionCheck(ctx, u.role);
      await ctx.close();
    }

    // 7) mobile interaction checks
    {
      const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, locale: 'ja-JP', timezoneId: 'Asia/Tokyo', isMobile: true, hasTouch: true });
      await login(ctx, USERS[0]);
      await mobileSidebarCheck(ctx);
      for (const [name, url] of [['dashboard', '/dashboard'], ['engineer', '/engineer/list'], ['contract', '/contract/list'], ['proposal', '/proposal/kanban'], ['customer', '/customer/list'], ['todo', '/todo']]) {
        await auditPage(ctx, { role: '管理者', viewport: 'mobile', pageName: name, url, selected: ['engineer', 'contract', 'todo'].includes(name) });
      }
      const page = await ctx.newPage();
      await page.goto(`${BASE}/engineer/list`, { waitUntil: 'domcontentloaded' });
      await page.waitForTimeout(1500);
      const btn = page.locator('[data-bs-target="#engineerModal"]');
      if (await btn.count() > 0) {
        await btn.click();
        await page.waitForTimeout(600);
      }
      const modalInfo = await page.evaluate(() => {
        const m = document.getElementById('engineerModal');
        if (!m) return { exists: false };
        return {
          exists: true,
          visible: m.classList.contains('show'),
          maxHeight: m.querySelector('.modal-content') ? m.querySelector('.modal-content').scrollHeight : 0,
          viewport: window.innerHeight
        };
      });
      check('mobile:modal-scroll', `visible=${modalInfo.visible}, contentH=${modalInfo.maxHeight}, viewportH=${modalInfo.viewport}`, modalInfo.visible);
      await page.close();
      await ctx.close();
    }

    // 8) legacy member logins
    await legacyMemberCheck(browser);

    // 9) concurrent logins
    await concurrentLoginCheck(browser);
  } finally {
    await browser.close();
  }

  const report = {
    base: BASE,
    runAt: new Date().toISOString(),
    round: 2,
    issueCount: issues.length,
    checkCount: checks.length,
    failedCheckCount: checks.filter((c) => !c.ok).length,
    issues
  };
  fs.writeFileSync(path.join(ROUND2_DIR, 'round2-issues.json'), JSON.stringify(report, null, 2), 'utf8');

  const lines = [
    '# 300人規模 E2E 第2ラウンド（深掘り）',
    '',
    `- 実行日時: ${report.runAt}`,
    `- 対象URL: ${BASE}`,
    `- 検出問題数: ${issues.length}（重複除去後）`,
    `- 実行チェック数: ${checks.length}（うち失敗 ${checks.filter((c) => !c.ok).length}）`,
    '',
    '## チェック一覧',
    '',
    '| チェック | 結果 | 内容 |',
    '|---|---|---|',
  ];
  for (const c of checks) {
    lines.push(`| ${c.name} | ${c.ok ? 'OK' : 'NG'} | ${String(c.detail).replace(/\|/g, '\\|').slice(0, 160)} |`);
  }
  lines.push('', '## 検出問題一覧', '', '| ID | ロール | ビューポート | ページ | 種別 | 内容 |', '|---|---|---|---|---|---|');
  for (const i of issues) {
    lines.push(`| ${i.id} | ${i.role} | ${i.viewport} | ${i.page} | ${i.type} | ${String(i.message).replace(/\|/g, '\\|').slice(0, 180)} |`);
  }
  fs.writeFileSync(path.join(ROUND2_DIR, 'round2-report.md'), lines.join('\n') + '\n', 'utf8');
  console.log(`done: ${issues.length} issues, ${checks.length} checks -> ${path.join(ROUND2_DIR, 'round2-report.md')}`);
}

run().catch((err) => {
  console.error(err);
  process.exit(1);
});
