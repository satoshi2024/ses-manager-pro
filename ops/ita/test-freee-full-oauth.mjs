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
  console.log('--- Step 0: Ensure Clean DB State ---');
  execSql('DELETE FROM t_freee_connection;');

  const admin = new HttpClient();
  await admin.login('s300.admin01', 'Scale300!');

  console.log('\n--- Step 1: Check Initial /api/payroll/status (Before Connect) ---');
  const initialStatus = await admin.request('GET', '/api/payroll/status');
  console.log('Initial Status:', initialStatus.statusCode, initialStatus.data);

  console.log('\n--- Step 2: Trigger /integrations/freee/authorize ---');
  const authRes = await admin.request('GET', '/integrations/freee/authorize');
  console.log('Authorize response status:', authRes.statusCode);
  const redirectLocation = authRes.headers.location;
  console.log('Authorize redirect location:', redirectLocation);

  const authUrl = new URL(redirectLocation);
  const state = authUrl.searchParams.get('state');
  console.log('Extracted state param:', state);

  console.log('\n--- Step 3: Trigger Callback /integrations/freee/callback with code and state ---');
  const callbackUrl = `/integrations/freee/callback?code=mock_freee_auth_code_999&state=${encodeURIComponent(state)}`;
  const callbackRes = await admin.request('GET', callbackUrl);
  console.log('Callback response status:', callbackRes.statusCode, 'Location:', callbackRes.headers.location);

  console.log('\n--- Step 4: Verify /api/payroll/status (Connected=true) ---');
  const afterStatus = await admin.request('GET', '/api/payroll/status');
  console.log('After Status:', afterStatus.statusCode, afterStatus.data);

  console.log('\n--- Step 5: Query t_freee_connection DB Table (Encrypted Tokens) ---');
  const dbRows = execSql('SELECT id, company_id, company_name, connection_status, access_token_encrypted, refresh_token_encrypted, token_expires_at, connected_by FROM t_freee_connection;');
  console.log(dbRows);

  console.log('\n--- Step 6: Verify Audit Log Record for FREEE_CONNECT ---');
  const auditRows = execSql("SELECT id, username, method, uri, status, application_code, success_flag, created_at FROM t_audit_log WHERE application_code = 'FREEE_CONNECT' ORDER BY id DESC LIMIT 2;");
  console.log(auditRows);
}

run();
