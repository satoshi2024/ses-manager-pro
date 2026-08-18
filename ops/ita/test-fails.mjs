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
  const client = new HttpClient();
  await client.login('s300.admin01', 'Scale300!');

  console.log('--- Test MOD10-06 via Ingestion Flow ---');
  const pasteRes = await client.request('POST', '/api/bp-availability-ingestions/paste', {
    text: '要員: 山田 太郎, Java, 85万円, 2026-09-01'
  });
  const jobId = pasteRes.data?.data?.id;
  console.log('Ingestion Job ID:', jobId);

  const confirmRes = await client.request('POST', `/api/bp-availability-ingestions/${jobId}/confirm`, {
    initialName: 'Y.T',
    bpCompanyId: 11001,
    unitPrice: 850000,
    availableFrom: '2026-09-01'
  });
  const availId = confirmRes.data?.data;
  console.log('Confirmed Avail ID:', availId);

  const prom1 = await client.request('POST', `/api/bp-availabilities/${availId}/promote`);
  console.log('Promote 1 Res:', prom1.statusCode, prom1.data);

  const prom2 = await client.request('POST', `/api/bp-availabilities/${availId}/promote`);
  console.log('Promote 2 Res (expect 409):', prom2.statusCode, prom2.data);

  // Teardown
  const engId = prom1.data?.data?.id;
  if (engId) {
    execSql(`DELETE FROM t_engineer_bp_affiliation WHERE engineer_id = ${engId};`);
    execSql(`DELETE FROM t_engineer WHERE id = ${engId};`);
  }
  if (availId) execSql(`DELETE FROM t_bp_availability WHERE id = ${availId};`);
  if (jobId) execSql(`DELETE FROM t_bp_availability_ingestion WHERE id = ${jobId};`);
}

run();
