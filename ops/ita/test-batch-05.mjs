import http from 'http';
import { execSync } from 'child_process';

const DB_USER = 'root';
const DB_PASS = '123456';
const DB_NAME = 'ses_manager_db';
const MYSQL_PATH = '"C:\\Program Files\\MySQL\\MySQL Server 8.4\\bin\\mysql.exe"';

function execSql(sql) {
  try {
    const stdout = execSync(`${MYSQL_PATH} -u ${DB_USER} -p${DB_PASS} ${DB_NAME} -e "${sql}"`, { encoding: 'utf-8', stdio: ['pipe', 'pipe', 'pipe'] });
    const lines = stdout.trim().split('\n').filter(l => !l.startsWith('mysql: [Warning]'));
    if (lines.length <= 1) return [];
    const headers = lines[0].split('\t').map(h => h.trim());
    return lines.slice(1).map(line => {
      const parts = line.split('\t');
      const obj = {};
      headers.forEach((h, i) => obj[h] = parts[i]?.trim());
      return obj;
    });
  } catch (e) {
    return [];
  }
}

class HttpClient {
  constructor(baseUrl = 'http://localhost:8080') {
    this.baseUrl = baseUrl;
    this.cookies = {};
  }
  get cookieHeader() { return Object.entries(this.cookies).map(([k, v]) => `${k}=${v}`).join('; '); }
  get csrfToken() { return this.cookies['XSRF-TOKEN'] || ''; }
  updateCookies(setCookieHeader) {
    if (!setCookieHeader) return;
    for (const sc of setCookieHeader) {
      const [nameVal] = sc.split(';');
      const [name, ...valParts] = nameVal.split('=');
      this.cookies[name.trim()] = valParts.join('=');
    }
  }
  async request(method, path, body = null, headers = {}) {
    return new Promise((resolve, reject) => {
      const url = new URL(path, this.baseUrl);
      const reqHeaders = {
        'Cookie': this.cookieHeader,
        'X-XSRF-TOKEN': this.csrfToken,
        'Accept': 'application/json',
        ...headers
      };
      let postData = null;
      if (body) {
        if (typeof body === 'string') {
          postData = body;
        } else {
          postData = JSON.stringify(body);
          reqHeaders['Content-Type'] = 'application/json; charset=UTF-8';
        }
        reqHeaders['Content-Length'] = Buffer.byteLength(postData);
      }
      const req = http.request(url, { method, headers: reqHeaders }, (res) => {
        this.updateCookies(res.headers['set-cookie']);
        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
          let parsed = null;
          try { parsed = JSON.parse(data); } catch (e) { parsed = data; }
          resolve({ statusCode: res.statusCode, headers: res.headers, data: parsed });
        });
      });
      req.on('error', reject);
      if (postData) req.write(postData);
      req.end();
    });
  }
  async login(username, password) {
    await this.request('GET', '/login');
    const postData = `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`;
    const res = await this.request('POST', '/login', postData, { 'Content-Type': 'application/x-www-form-urlencoded' });
    await this.request('GET', '/');
    return res.statusCode;
  }
}

async function test() {
  const admin = new HttpClient();
  await admin.login('s300.admin01', 'Scale300!');

  console.log('--- 1. Quotations Test ---');
  const qRes = await admin.request('POST', '/api/quotations', {
    customerId: 2022,
    projectId: 1,
    engineerId: 1,
    title: 'テスト見積',
    unitPrice: 800000,
    settlementHoursMin: 140,
    settlementHoursMax: 180,
    validUntil: '2026-12-31'
  });
  console.log('Quotation Create:', qRes.statusCode, qRes.data);
  const qId = qRes.data?.data?.id;

  if (qId) {
    const pdfRes = await admin.request('GET', `/api/quotations/${qId}/pdf`);
    console.log('Quotation PDF:', pdfRes.statusCode, pdfRes.headers['content-type']);
    execSql(`DELETE FROM t_quotation WHERE id = ${qId};`);
  }

  console.log('\n--- 2. Approvals Test ---');
  const reqRes = await admin.request('POST', '/api/approval/requests', {
    requestType: 'contract.activate',
    targetType: 'CONTRACT',
    targetId: 1,
    targetVersion: 1,
    amountSnapshot: 800000
  });
  console.log('Approval Request Create:', reqRes.statusCode, reqRes.data);
  const reqId = reqRes.data?.data?.id;
  if (reqId) {
    execSql(`DELETE FROM t_approval_action WHERE request_id = ${reqId};`);
    execSql(`DELETE FROM t_approval_participant WHERE request_id = ${reqId};`);
    execSql(`DELETE FROM t_approval_request WHERE id = ${reqId};`);
  }

  console.log('\n--- 3. Sales Orders Test ---');
  const soRes = await admin.request('POST', '/api/sales-orders', {
    customerId: 2022,
    legalEntityId: 1,
    orderDate: '2026-08-01',
    customerPoNo: 'PO-MOD15-' + Date.now(),
    startDate: '2026-08-01',
    endDate: '2026-08-31',
    lines: [
      {
        engineerId: 1,
        projectId: 1,
        unitPrice: 800000,
        settlementMin: 140,
        settlementMax: 180
      }
    ]
  });
  console.log('Sales Order Create:', soRes.statusCode, soRes.data);
  const soId = soRes.data?.data?.order?.id;
  if (soId) {
    execSql(`DELETE FROM t_sales_order_line WHERE order_id = ${soId};`);
    execSql(`DELETE FROM t_sales_order WHERE id = ${soId};`);
  }

  console.log('\n--- 12. Break Glass Test ---');
  const bgRes = await admin.request('POST', '/api/security/break-glass/incidents', {
    reason: 'システム障害緊急対応',
    idpOutageConfirmed: true,
    durationMinutes: 60,
    correlationId: 'BG-TEST-' + Date.now(),
    allowedActions: ['user.read', 'system.config']
  });
  console.log('Break Glass Create:', bgRes.statusCode, bgRes.data);
  const bgId = bgRes.data?.data?.id;
  if (bgId) {
    const bgApprove = await admin.request('POST', `/api/security/break-glass/incidents/${bgId}/approve`);
    console.log('Break Glass Approve:', bgApprove.statusCode, bgApprove.data);
    const bgClose = await admin.request('POST', `/api/security/break-glass/incidents/${bgId}/close`);
    console.log('Break Glass Close:', bgClose.statusCode, bgClose.data);
    execSql(`DELETE FROM break_glass_incident WHERE id = ${bgId};`);
  }
}

test();
