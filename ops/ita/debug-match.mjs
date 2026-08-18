import http from 'http';
import { execSync } from 'child_process';

const DB_USER = 'root';
const DB_PASS = '123456';
const DB_NAME = 'ses_manager_db';
const MYSQL_PATH = '"C:\\Program Files\\MySQL\\MySQL Server 8.4\\bin\\mysql.exe" --default-character-set=utf8mb4';

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

async function debugMust03() {
  const client = new HttpClient();
  await client.login('s300.admin01', 'Scale300!');

  // Insert engineer with skill 1 (Java)
  execSql(`INSERT INTO t_engineer (full_name, initial_name, status, expected_unit_price, available_date, created_at, updated_at) VALUES ('QA_MUST_03', 'QE', 'Bench', 800000, '2026-09-01', NOW(), NOW());`);
  const engId = execSql(`SELECT id FROM t_engineer WHERE full_name = 'QA_MUST_03';`)[0]?.id;
  execSql(`INSERT INTO t_engineer_skill (engineer_id, skill_id, proficiency, experience_years) VALUES (${engId}, 1, '中級', 3);`);

  // Insert project with must skills 1, 2 (Java, Python)
  execSql(`INSERT INTO t_project (customer_id, project_name, status, unit_price_min, unit_price_max, start_date, created_at, updated_at) VALUES (1, 'QA_PROJ_MUST_03', '募集中', NULL, 800000, '2026-09-01', NOW(), NOW());`);
  const projId = execSql(`SELECT id FROM t_project WHERE project_name = 'QA_PROJ_MUST_03';`)[0]?.id;
  execSql(`INSERT INTO t_project_skill (project_id, skill_id, is_must) VALUES (${projId}, 1, 1);`);
  execSql(`INSERT INTO t_project_skill (project_id, skill_id, is_must) VALUES (${projId}, 2, 1);`);

  const res = await client.request('POST', '/api/ai/match/engineer-to-projects', { engineerId: parseInt(engId, 10) });
  console.log('API Status:', res.statusCode);
  console.log('API Response Data:', JSON.stringify(res.data, null, 2));

  // Teardown
  execSql(`DELETE FROM t_project_skill WHERE project_id = ${projId};`);
  execSql(`DELETE FROM t_project WHERE id = ${projId};`);
  execSql(`DELETE FROM t_engineer_skill WHERE engineer_id = ${engId};`);
  execSql(`DELETE FROM t_engineer WHERE id = ${engId};`);
}

debugMust03();
