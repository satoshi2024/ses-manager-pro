import http from 'http';

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
  const member = new HttpClient();
  const loginRes = await member.login('s300.member001', 'Scale300!');
  console.log('Member login status:', loginRes);
  const notif = await member.request('GET', '/api/notifications/page');
  console.log('Notif Page Result:', notif.statusCode, notif.data);
}

run();
