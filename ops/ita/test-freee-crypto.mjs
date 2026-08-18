import crypto from 'crypto';
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

export function encrypt(plain, keyStr = 'change-me-change-me-change-me-1234') {
  const key = Buffer.alloc(32);
  Buffer.from(keyStr, 'utf-8').copy(key);
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key, iv);
  const enc = Buffer.concat([cipher.update(plain, 'utf-8'), cipher.final()]);
  const tag = cipher.getAuthTag();
  const cipherWithTag = Buffer.concat([enc, tag]);
  return iv.toString('base64') + ':' + cipherWithTag.toString('base64');
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
  const encToken = encrypt('sample_access_token_12345');
  const encRefresh = encrypt('sample_refresh_token_67890');

  // Insert mock connected row
  execSql(`DELETE FROM t_freee_connection;`);
  execSql(`INSERT INTO t_freee_connection (company_id, company_name, access_token_encrypted, refresh_token_encrypted, token_expires_at, connected_by, connection_status) VALUES (12345, 'テスト事業所（モック）', '${encToken}', '${encRefresh}', '2026-12-31 23:59:59', 101, 'CONNECTED');`);

  const rows = execSql(`SELECT id, company_id, company_name, connection_status, access_token_encrypted, token_expires_at FROM t_freee_connection;`);
  console.log('Inserted t_freee_connection row in DB:', rows);

  const admin = new HttpClient();
  await admin.login('s300.admin01', 'Scale300!');
  const statusRes = await admin.request('GET', '/api/payroll/status');
  console.log('API /api/payroll/status response:', statusRes.statusCode, statusRes.data);

  // Teardown
  execSql(`DELETE FROM t_freee_connection;`);
}

test();
