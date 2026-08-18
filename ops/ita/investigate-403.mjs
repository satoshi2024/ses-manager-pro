import http from 'http';

class HttpClient {
  constructor(baseUrl = 'http://localhost:8080') {
    this.baseUrl = baseUrl;
    this.cookie = '';
    this.csrfToken = '';
  }

  async login(username, password) {
    const initRes = await this.request('GET', '/login');
    const postData = `username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`;
    return new Promise((resolve, reject) => {
      const url = new URL('/login', this.baseUrl);
      const req = http.request(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/x-www-form-urlencoded',
          'Content-Length': Buffer.byteLength(postData),
          'Cookie': this.cookie,
          'X-XSRF-TOKEN': this.csrfToken
        }
      }, (res) => {
        const setCookies = res.headers['set-cookie'] || [];
        for (const sc of setCookies) {
          const cookiePart = sc.split(';')[0];
          this.cookie = (this.cookie ? this.cookie + '; ' : '') + cookiePart;
          if (cookiePart.startsWith('XSRF-TOKEN=')) {
            this.csrfToken = cookiePart.substring('XSRF-TOKEN='.length);
          }
        }
        resolve(res.statusCode);
      });
      req.on('error', reject);
      req.write(postData);
      req.end();
    });
  }

  async request(method, path, body = null, headers = {}) {
    return new Promise((resolve, reject) => {
      const url = new URL(path, this.baseUrl);
      const reqHeaders = {
        'Cookie': this.cookie,
        'X-XSRF-TOKEN': this.csrfToken,
        'Accept': 'application/json',
        ...headers
      };
      let postData = null;
      if (body) {
        postData = typeof body === 'string' ? body : JSON.stringify(body);
        reqHeaders['Content-Type'] = 'application/json; charset=UTF-8';
        reqHeaders['Content-Length'] = Buffer.byteLength(postData);
      }

      const req = http.request(url, { method, headers: reqHeaders }, (res) => {
        const setCookies = res.headers['set-cookie'] || [];
        for (const sc of setCookies) {
          const cookiePart = sc.split(';')[0];
          this.cookie = (this.cookie ? this.cookie + '; ' : '') + cookiePart;
          if (cookiePart.startsWith('XSRF-TOKEN=')) {
            this.csrfToken = cookiePart.substring('XSRF-TOKEN='.length);
          }
        }

        let data = '';
        res.on('data', chunk => data += chunk);
        res.on('end', () => {
          let parsed = null;
          try {
            parsed = JSON.parse(data);
          } catch (e) {
            parsed = data;
          }
          resolve({
            statusCode: res.statusCode,
            headers: res.headers,
            data: parsed
          });
        });
      });
      req.on('error', reject);
      if (postData) req.write(postData);
      req.end();
    });
  }
}

async function run() {
  const client = new HttpClient();
  const loginStatus = await client.login('s300.admin01', 'Scale300!');
  console.log('Login Status for s300.admin01:', loginStatus);

  const endpoints = [
    { method: 'GET', path: '/api/work-records/attendance/sync/status' },
    { method: 'POST', path: '/api/work-records/attendance/sync/run', body: { month: '2026-08' } },
    { method: 'GET', path: '/api/contract-documents/templates' },
    { method: 'POST', path: '/api/contract-documents', body: { contractId: 7001, templateId: 1 } },
    { method: 'GET', path: '/api/contracts/7001/compliance-documents' }
  ];

  for (const ep of endpoints) {
    const res = await client.request(ep.method, ep.path, ep.body);
    console.log(`\n======================================================`);
    console.log(`Endpoint: ${ep.method} ${ep.path}`);
    console.log(`HTTP Status: ${res.statusCode}`);
    console.log(`Response Body:`, JSON.stringify(res.data, null, 2));
  }
}

run();
