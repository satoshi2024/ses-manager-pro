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
    // Follow redirect to / to get authenticated session cookies and fresh CSRF token
    await this.request('GET', '/');
    return res.statusCode;
  }
}

async function test() {
  const client = new HttpClient();
  await client.login('s300.admin01', 'Scale300!');
  console.log('Cookies after login & root GET:', client.cookies);

  const endpoints = [
    { method: 'GET', path: '/api/work-records/attendance/sync/status' },
    { method: 'POST', path: '/api/work-records/attendance/sync/run?month=2026-08&direction=pull' },
    { method: 'GET', path: '/api/contract-documents/templates' },
    { method: 'POST', path: '/api/contract-documents?contractId=7001&templateId=1&recipientName=Test&recipientEmail=test@example.com' },
    { method: 'GET', path: '/api/contracts/7001/compliance-documents' }
  ];

  for (const ep of endpoints) {
    const res = await client.request(ep.method, ep.path);
    console.log(`\n======================================================`);
    console.log(`Endpoint: ${ep.method} ${ep.path}`);
    console.log('HTTP Status:', res.statusCode);
    console.log('Response Body:', JSON.stringify(res.data, null, 2));
  }
}

test();
