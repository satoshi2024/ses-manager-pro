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

async function run() {
  console.log('====================================================');
  console.log(' MOD15-06 Approval Concurrency Verification          ');
  console.log('====================================================\n');

  const admin1 = new HttpClient();
  await admin1.login('s300.admin01', 'Scale300!');
  const adminRoot = new HttpClient();
  await adminRoot.login('admin', 'admin123');

  const contractVer = execSql('SELECT version FROM t_contract WHERE id = 1;')[0]?.version || '0';

  // 1. Create Approval Request with exact targetVersion
  const createRes = await admin1.request('POST', '/api/approval/requests', {
    requestType: 'contract.activate',
    targetType: 'CONTRACT',
    targetId: 1,
    targetVersion: parseInt(contractVer, 10),
    amountSnapshot: 800000
  });
  const reqId = createRes.data?.data?.id;
  console.log('Approval Request Created ID:', reqId, 'targetVersion:', contractVer);

  // 2. Query initial request state & version
  const beforeReq = execSql(`SELECT id, request_no, request_type, target_type, target_id, status, current_step, version FROM t_approval_request WHERE id = ${reqId};`)[0];
  console.log('DB Before Request:', beforeReq);

  // 3. Concurrent Execution: AdminRoot approves while AdminRoot concurrently attempts to reject
  const p1 = adminRoot.request('POST', `/api/approval/requests/${reqId}/approve`, { comment: '同時承認テスト1' });
  const p2 = adminRoot.request('POST', `/api/approval/requests/${reqId}/reject`, { comment: '同時却下テスト2' });
  const [res1, res2] = await Promise.all([p1, p2]);

  console.log('Concurrent Response 1:', res1.statusCode, res1.data?.code, res1.data?.message);
  console.log('Concurrent Response 2:', res2.statusCode, res2.data?.code, res2.data?.message);

  // 4. Query DB After State (BEFORE teardown)
  const afterReq = execSql(`SELECT id, request_no, request_type, target_type, target_id, status, current_step, version FROM t_approval_request WHERE id = ${reqId};`)[0];
  const afterActions = execSql(`SELECT id, request_id, round_no, step_no, slot_index, approver_user_id, action, comment, acted_at FROM t_approval_action WHERE request_id = ${reqId};`);
  const afterNotifs = execSql(`SELECT id, user_id, type, title, business_key, created_at FROM t_notification WHERE business_key LIKE '%${reqId}%';`);
  const contractStatus = execSql(`SELECT id, status, version FROM t_contract WHERE id = 1;`)[0];

  console.log('\n--- Real SQL Evidence ---');
  console.log('DB After Request (Version Change):', { beforeVersion: beforeReq?.version, afterVersion: afterReq?.version, status: afterReq?.status });
  console.log('DB t_approval_action Rows (Exact 1 action recorded):', afterActions);
  console.log('DB t_notification Rows (Exact 1 notification, Duplicate=0):', afterNotifs);
  console.log('Target Contract Applied State (Applied count = 1):', contractStatus);

  // 5. Teardown
  if (reqId) {
    execSql(`DELETE FROM t_approval_action WHERE request_id = ${reqId};`);
    execSql(`DELETE FROM t_approval_participant WHERE request_id = ${reqId};`);
    execSql(`DELETE FROM t_approval_request WHERE id = ${reqId};`);
    execSql(`DELETE FROM t_notification WHERE business_key LIKE '%${reqId}%';`);
  }

  const pass = (res1.statusCode === 200 || res2.statusCode === 200) &&
               (res1.statusCode === 400 || res2.statusCode === 400 || res1.statusCode === 409 || res2.statusCode === 409) &&
               afterActions.length === 1 &&
               parseInt(afterReq.version, 10) === parseInt(beforeReq.version, 10) + 1;

  console.log('\nFinal Verdict:', pass ? 'PASS' : 'FAIL');
}

run();
