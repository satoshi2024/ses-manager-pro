import http from 'http';

const PORT = 8089;

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, `http://localhost:${PORT}`);
  const path = parsedUrl.pathname;
  const method = req.method;

  let body = '';
  req.on('data', chunk => body += chunk);
  req.on('end', () => {
    console.log(`[Freee Stub] ${method} ${path}`, body ? `Body: ${body}` : '');

    res.setHeader('Content-Type', 'application/json; charset=utf-8');

    // 1. OAuth Authorize
    if (path === '/public_api/authorize') {
      res.writeHead(302, {
        Location: `${parsedUrl.searchParams.get('redirect_uri')}?code=mock_freee_auth_code_999&state=${parsedUrl.searchParams.get('state')}`
      });
      return res.end();
    }

    // 2. Token Exchange (POST /public_api/token)
    if (path === '/public_api/token' && method === 'POST') {
      res.writeHead(200);
      return res.end(JSON.stringify({
        access_token: 'mock_access_token_' + Date.now(),
        token_type: 'bearer',
        expires_in: 86400,
        refresh_token: 'mock_refresh_token_' + Date.now(),
        scope: 'read write',
        company_id: 12345
      }));
    }

    // 3. Current User & Companies (GET /api/v1/users/me or /hr/api/v1/users/me)
    if (path.includes('/users/me')) {
      res.writeHead(200);
      return res.end(JSON.stringify({
        companies: [
          {
            id: 12345,
            name: '株式会社テスト（freee連携）',
            display_name: '株式会社テスト（freee連携）',
            role: 'company_admin'
          }
        ]
      }));
    }

    // 4. Token Revoke (POST /public_api/revoke)
    if (path === '/public_api/revoke' && method === 'POST') {
      res.writeHead(200);
      return res.end(JSON.stringify({ success: true }));
    }

    // 5. Employees list (GET .../employees)
    if (path.includes('/employees')) {
      res.writeHead(200);
      return res.end(JSON.stringify({
        employees: [
          {
            id: 501,
            num: 'EMP001',
            display_name: '山田 太郎',
            birth_at: '1990-01-01',
            entry_date: '2020-04-01',
            retire_date: null
          },
          {
            id: 502,
            num: 'EMP002',
            display_name: '鈴木 花子',
            birth_at: '1992-05-15',
            entry_date: '2021-04-01',
            retire_date: null
          }
        ]
      }));
    }

    // 6. Salaries list (GET .../salaries/employee_payroll_statements)
    if (path.includes('/salaries/employee_payroll_statements')) {
      res.writeHead(200);
      return res.end(JSON.stringify({
        total_count: 1,
        employee_payroll_statements: [
          {
            id: 9001,
            employee_id: 501,
            year: 2026,
            month: 8,
            base_salary: 500000,
            allowances: [
              { name: '役職手当', amount: 50000 }
            ],
            deductions: [
              { name: '健康保険料', amount: 25000 },
              { name: '厚生年金保険料', amount: 45000 }
            ],
            gross_salary: 550000,
            net_salary: 480000
          }
        ]
      }));
    }

    // Default 404
    console.log(`[Freee Stub 404 Not Found] ${method} ${path}`);
    res.writeHead(404);
    res.end(JSON.stringify({ error: 'not_found' }));
  });
});

server.listen(PORT, () => {
  console.log(`Freee Mock Stub Server running on http://localhost:${PORT}`);
});
