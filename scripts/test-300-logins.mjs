import http from 'node:http';

function getLoginCsrfAndCookie() {
  return new Promise((resolve, reject) => {
    http.get('http://localhost:8080/login', (res) => {
      const cookies = res.headers['set-cookie'] || [];
      let xsrfToken = '';
      const cookieStr = cookies.map(c => c.split(';')[0]).join('; ');
      for (const c of cookies) {
        const m = c.match(/XSRF-TOKEN=([^;]+)/);
        if (m) xsrfToken = decodeURIComponent(m[1]);
      }
      let body = '';
      res.on('data', chunk => body += chunk);
      res.on('end', () => {
        const m = body.match(/name="_csrf"\s+value="([^"]+)"/);
        if (m) xsrfToken = m[1];
        resolve({ xsrfToken, cookieStr });
      });
    }).on('error', reject);
  });
}

function postLogin(username, password, xsrfToken, cookieStr) {
  return new Promise((resolve, reject) => {
    const postData = new URLSearchParams({
      username,
      password,
      _csrf: xsrfToken
    }).toString();

    const req = http.request({
      hostname: 'localhost',
      port: 8080,
      path: '/login',
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'Content-Length': Buffer.byteLength(postData),
        'Cookie': cookieStr,
        'X-XSRF-TOKEN': xsrfToken
      }
    }, (res) => {
      const location = res.headers.location || '';
      resolve({ statusCode: res.statusCode, location });
    });
    req.on('error', reject);
    req.write(postData);
    req.end();
  });
}

async function run() {
  // 1. Build all 300 accounts list
  const users = [
    { username: 'admin', role: '管理者', password: 'admin123', expectedActive: true },
    { username: 's300.admin01', role: '管理者', password: 'Scale300!', expectedActive: true }
  ];

  for (let i = 1; i <= 25; i++) {
    const u = `s300.sales${String(i).padStart(2, '0')}`;
    const active = u !== 's300.sales07';
    users.push({ username: u, role: '営業', password: 'Scale300!', expectedActive: active });
  }

  for (let i = 1; i <= 8; i++) {
    const u = `s300.hr${String(i).padStart(2, '0')}`;
    const active = u !== 's300.hr05';
    users.push({ username: u, role: 'HR', password: 'Scale300!', expectedActive: active });
  }

  for (let i = 1; i <= 10; i++) {
    const u = `s300.mgr${String(i).padStart(2, '0')}`;
    users.push({ username: u, role: 'マネージャー', password: 'Scale300!', expectedActive: true });
  }

  for (let i = 1; i <= 255; i++) {
    const u = `s300.member${String(i).padStart(3, '0')}`;
    const active = u !== 's300.member200';
    users.push({ username: u, role: '要員', password: 'Scale300!', expectedActive: active });
  }

  console.log(`Total users in test list: ${users.length}`);

  let successCount = 0;
  let failureCount = 0;
  let disabledDeniedCount = 0;
  let unexpectedErrors = [];

  for (const user of users) {
    try {
      const { xsrfToken, cookieStr } = await getLoginCsrfAndCookie();
      const res = await postLogin(user.username, user.password, xsrfToken, cookieStr);

      const isSuccessRedirect = res.statusCode === 302 && (res.location === '/' || res.location === 'http://localhost:8080/' || res.location === '/my/timesheet' || res.location === 'http://localhost:8080/my/timesheet');
      const isErrorRedirect = res.statusCode === 302 && res.location.includes('/login?error');

      if (user.expectedActive) {
        if (isSuccessRedirect) {
          successCount++;
        } else {
          failureCount++;
          unexpectedErrors.push({ username: user.username, role: user.role, expected: 'SUCCESS', actual: res });
        }
      } else {
        if (isErrorRedirect || res.statusCode === 401 || res.statusCode === 403) {
          disabledDeniedCount++;
        } else {
          unexpectedErrors.push({ username: user.username, role: user.role, expected: 'DENIED', actual: res });
        }
      }
    } catch (e) {
      unexpectedErrors.push({ username: user.username, error: e.message });
    }
  }

  console.log(`\n=== 300-Account Login Verification Results ===`);
  console.log(`Total accounts tested: ${users.length}`);
  console.log(`Active accounts expected: 297, Succeeded: ${successCount}`);
  console.log(`Disabled accounts expected: 3, Denied: ${disabledDeniedCount}`);
  console.log(`Unexpected errors count: ${unexpectedErrors.length}`);
  if (unexpectedErrors.length > 0) {
    console.log('Errors:', JSON.stringify(unexpectedErrors, null, 2));
  }
}

run();
