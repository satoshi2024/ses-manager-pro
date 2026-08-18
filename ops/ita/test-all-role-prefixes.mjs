import http from 'http';

class HttpClient {
  constructor(baseUrl = 'http://localhost:8080') {
    this.baseUrl = baseUrl;
    this.cookies = {};
  }

  get cookieHeader() {
    return Object.entries(this.cookies).map(([k, v]) => `${k}=${v}`).join('; ');
  }

  get csrfToken() {
    return this.cookies['XSRF-TOKEN'] || '';
  }

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
    const res = await this.request('POST', '/login', postData, {
      'Content-Type': 'application/x-www-form-urlencoded'
    });
    await this.request('GET', '/');
    return res.statusCode;
  }
}

async function run() {
  const roles = [
    { name: '管理者', user: 's300.admin01', pass: 'Scale300!' },
    { name: 'HR', user: 's300.hr01', pass: 'Scale300!' },
    { name: '営業', user: 's300.sales01', pass: 'Scale300!' },
    { name: 'マネージャー', user: 's300.mgr01', pass: 'Scale300!' },
    { name: '要員', user: 's300.member01', pass: 'Scale300!' }
  ];

  const testEndpoints = [
    { name: 'attendance-sync-status', method: 'GET', path: '/api/work-records/attendance/sync/status' },
    { name: 'attendance-sync-run', method: 'POST', path: '/api/work-records/attendance/sync/run?month=2026-08&direction=pull' },
    { name: 'contract-documents-templates', method: 'GET', path: '/api/contract-documents/templates' },
    { name: 'contract-documents-create', method: 'POST', path: '/api/contract-documents?contractId=7001&templateId=1&recipientName=Test&recipientEmail=test@example.com' },
    { name: 'contracts-compliance-documents', method: 'GET', path: '/api/contracts/7001/compliance-documents' },
    { name: 'bp-affiliations', method: 'GET', path: '/api/bp-affiliations' },
    { name: 'bp-migrations', method: 'GET', path: '/api/bp-migrations' },
    { name: 'files', method: 'GET', path: '/api/files' },
    { name: 'saved-views', method: 'GET', path: '/api/saved-views?viewScope=contract' }
  ];

  const matrix = {};

  for (const r of roles) {
    matrix[r.name] = {};
    const client = new HttpClient();
    await client.login(r.user, r.pass);

    for (const ep of testEndpoints) {
      const res = await client.request(ep.method, ep.path);
      matrix[r.name][ep.name] = {
        status: res.statusCode,
        code: res.data?.code,
        msg: res.data?.message?.substring(0, 30)
      };
    }
  }

  console.log(JSON.stringify(matrix, null, 2));
}

run();
