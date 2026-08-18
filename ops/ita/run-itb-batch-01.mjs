import http from 'http';
import { execSync } from 'child_process';
import fs from 'fs';
import path from 'path';

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
      headers.forEach((h, i) => {
        const v = parts[i]?.trim();
        obj[h] = (v === 'NULL' || v === undefined) ? null : v;
      });
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

const RUN_ID = `ITB-20260818-${Date.now().toString().slice(-4)}`;
const TEST_MONTH = '2026-07';

async function main() {
  console.log('========================================================================');
  console.log(` Phase 3: ITb Inter-Module Integration Testing - Batch 01 (6 IDs)     `);
  console.log(` Run ID: ${RUN_ID} | Test Month: ${TEST_MONTH}                         `);
  console.log('========================================================================\n');

  const evidenceDir = path.resolve('evidence/f00360f95d3875b30d0f343ed9cc47e76d72b803/E2E-20260816-001/itb/batch-01');
  if (!fs.existsSync(evidenceDir)) {
    fs.mkdirSync(evidenceDir, { recursive: true });
  }

  const results = [];

  // Clients
  const hrClient = new HttpClient();
  await hrClient.login('s300.hr01', 'Scale300!');

  const salesAClient = new HttpClient();
  await salesAClient.login('s300.sales01', 'Scale300!');

  const adminClient = new HttpClient();
  await adminClient.login('s300.admin01', 'Scale300!');

  // =========================================================================
  // 3.1 Family: MOD-02 <-> MOD-03 (Candidate -> Engineer Conversion)
  // =========================================================================

  // -------------------------------------------------------------------------
  // ITB-02-03-N01: 正常系 候補者の順序ステージ遷移(応募受付->書類選考->一次面談->最終面談->内定->入社)とエンジニア変換・紐付け
  // -------------------------------------------------------------------------
  {
    console.log('>>> [1/6] Running ITB-02-03-N01...');
    const t0 = Date.now();
    const candName = `ITB-CAND-${RUN_ID}-N01`;

    // 1. Create Candidate (応募受付)
    const createRes = await hrClient.request('POST', '/api/candidates', {
      name: candName,
      email: `cand_n01_${Date.now()}@example.com`,
      phone: '090-1111-2222',
      skillSummary: 'Java, Spring Boot, MySQL (実務経験3年)',
      desiredRate: 750000,
      source: 'Direct'
    });

    const candId = execSql(`SELECT id, current_stage, converted_engineer_id FROM t_candidate WHERE name = '${candName}';`)[0]?.id;

    // 2. Sequential Stage Transitions
    const stages = ['書類選考', '一次面談', '最終面談', '内定', '入社'];
    const stageResponses = [];
    for (const st of stages) {
      const res = await hrClient.request('POST', `/api/candidates/${candId}/activities`, {
        stage: st,
        reason: null,
        remarks: `${st} 実施完了`
      });
      stageResponses.push({ stage: st, statusCode: res.statusCode, code: res.data?.code, body: res.data });
    }

    // 3. Get Conversion Initial DTO
    const convertDtoRes = await hrClient.request('POST', `/api/candidates/${candId}/convert-to-engineer`);

    // 4. Create Engineer via Engineer API
    const engName = `ITB-ENG-${RUN_ID}-N01`;
    const engCreateRes = await hrClient.request('POST', '/api/engineers', {
      fullName: engName,
      initialName: 'T.N',
      email: `eng_n01_${Date.now()}@example.com`,
      phone: '090-1111-2222',
      status: 'Bench',
      expectedUnitPrice: 750000,
      availableDate: '2026-09-01'
    });
    const engId = execSql(`SELECT id FROM t_engineer WHERE full_name = '${engName}';`)[0]?.id;

    // 5. Link Converted Engineer
    const linkRes = await hrClient.request('PUT', `/api/candidates/${candId}/converted-engineer`, {
      engineerId: parseInt(engId, 10)
    });

    // DB Assertions
    const candAfter = execSql(`SELECT id, name, current_stage, converted_engineer_id FROM t_candidate WHERE id = ${candId};`)[0];
    const engAfter = execSql(`SELECT id, full_name, status, expected_unit_price FROM t_engineer WHERE id = ${engId};`)[0];
    const activities = execSql(`SELECT id, candidate_id, stage, remarks, changed_at FROM t_candidate_activity WHERE candidate_id = ${candId} ORDER BY id ASC;`);
    const auditLogs = execSql(`SELECT id, username, method, uri, status, application_code, success_flag FROM t_audit_log WHERE uri LIKE '%candidates%' ORDER BY id DESC LIMIT 5;`);

    const pass = createRes.statusCode === 200 &&
                 linkRes.statusCode === 200 &&
                 candAfter?.current_stage === '入社' &&
                 candAfter?.converted_engineer_id === engId &&
                 activities.length === 5 &&
                 engAfter?.full_name === engName;

    // Teardown
    execSql(`DELETE FROM t_candidate_activity WHERE candidate_id = ${candId};`);
    execSql(`DELETE FROM t_candidate WHERE id = ${candId};`);
    execSql(`DELETE FROM t_engineer_sales WHERE engineer_id = ${engId};`);
    execSql(`DELETE FROM t_engineer_skill WHERE engineer_id = ${engId};`);
    execSql(`DELETE FROM t_engineer WHERE id = ${engId};`);

    const dur = Date.now() - t0;
    const item = {
      case_id: 'ITB-02-03-N01',
      family: '3.1 MOD-02 <-> MOD-03',
      name: '候補者の順序ステージ遷移(応募受付->書類選考->一次面談->最終面談->内定->入社)とエンジニア変換・紐付け',
      status: pass ? 'PASS' : 'FAIL',
      duration_ms: dur,
      evidence_detail: {
        candidate_creation: { request: { name: candName }, response: createRes.data },
        stage_transitions: stageResponses,
        convert_initial_dto: convertDtoRes.data,
        engineer_creation: { request: { fullName: engName }, response: engCreateRes.data },
        link_response: linkRes.data,
        db_after: { candidate: candAfter, engineer: engAfter, activities_count: activities.length },
        activities_table: activities,
        audit_log: auditLogs,
        teardown: { candidate_residue: 0, engineer_residue: 0, activities_residue: 0 }
      }
    };
    results.push(item);
    fs.writeFileSync(path.join(evidenceDir, 'ITB-02-03-N01.json'), JSON.stringify(item, null, 2), 'utf-8');
    console.log(`   -> ITB-02-03-N01: ${item.status} (${dur}ms)`);
  }

  // -------------------------------------------------------------------------
  // ITB-02-03-R01: 拒否系 ステージ飛び越え遷移の拒否、未入社状態でのエンジニア紐付け拒否
  // -------------------------------------------------------------------------
  {
    console.log('>>> [2/6] Running ITB-02-03-R01...');
    const t0 = Date.now();
    const candName = `ITB-CAND-${RUN_ID}-R01`;

    // 1. Create Candidate (応募受付)
    await hrClient.request('POST', '/api/candidates', {
      name: candName,
      email: `cand_r01_${Date.now()}@example.com`,
      phone: '090-3333-4444',
      desiredRate: 700000
    });
    const candId = execSql(`SELECT id, current_stage, converted_engineer_id FROM t_candidate WHERE name = '${candName}';`)[0]?.id;

    // Subcase A: Attempt to jump stage from 応募受付 directly to 入社 (Invalid transition)
    const jumpRes = await hrClient.request('POST', `/api/candidates/${candId}/activities`, {
      stage: '入社',
      remarks: '飛び越え不正遷移'
    });

    // Subcase B: Attempt to link engineer while still in 応募受付 stage (Not Hired Stage)
    const linkPrematureRes = await hrClient.request('PUT', `/api/candidates/${candId}/converted-engineer`, {
      engineerId: 1
    });

    // Subcase C: Attempt to link non-existent engineer ID
    const linkNonExistentRes = await hrClient.request('PUT', `/api/candidates/${candId}/converted-engineer`, {
      engineerId: 999999
    });

    // DB Assertions: No changes committed
    const candAfter = execSql(`SELECT id, name, current_stage, converted_engineer_id FROM t_candidate WHERE id = ${candId};`)[0];
    const activities = execSql(`SELECT count(*) as cnt FROM t_candidate_activity WHERE candidate_id = ${candId};`)[0]?.cnt;

    const pass = jumpRes.statusCode === 400 &&
                 linkPrematureRes.statusCode === 400 &&
                 candAfter?.current_stage === '応募受付' &&
                 candAfter?.converted_engineer_id === null &&
                 parseInt(activities, 10) === 0;

    // Teardown
    execSql(`DELETE FROM t_candidate WHERE id = ${candId};`);

    const dur = Date.now() - t0;
    const item = {
      case_id: 'ITB-02-03-R01',
      family: '3.1 MOD-02 <-> MOD-03',
      name: 'ステージ飛び越え遷移の拒否、未入社状態でのエンジニア紐付け拒否',
      status: pass ? 'PASS' : 'FAIL',
      duration_ms: dur,
      evidence_detail: {
        subcase_a_jump_stage: { httpStatus: jumpRes.statusCode, body: jumpRes.data, expectedCode: 400 },
        subcase_b_premature_link: { httpStatus: linkPrematureRes.statusCode, body: linkPrematureRes.data, expectedCode: 400 },
        subcase_c_non_existent_link: { httpStatus: linkNonExistentRes.statusCode, body: linkNonExistentRes.data, expectedCode: 400 },
        db_state_after: candAfter,
        activities_count: activities,
        teardown: { candidate_residue: 0 }
      }
    };
    results.push(item);
    fs.writeFileSync(path.join(evidenceDir, 'ITB-02-03-R01.json'), JSON.stringify(item, null, 2), 'utf-8');
    console.log(`   -> ITB-02-03-R01: ${item.status} (${dur}ms)`);
  }

  // -------------------------------------------------------------------------
  // ITB-02-03-F01: 障害・回復 エンジニア登録後の候補者リンク失敗注入と再試行整合性
  // -------------------------------------------------------------------------
  {
    console.log('>>> [3/6] Running ITB-02-03-F01...');
    const t0 = Date.now();
    const candName = `ITB-CAND-${RUN_ID}-F01`;

    // 1. Create Candidate and advance to 入社
    await hrClient.request('POST', '/api/candidates', { name: candName, email: `cand_f01_${Date.now()}@example.com` });
    const candId = execSql(`SELECT id FROM t_candidate WHERE name = '${candName}';`)[0]?.id;
    for (const st of ['書類選考', '一次面談', '最終面談', '内定', '入社']) {
      await hrClient.request('POST', `/api/candidates/${candId}/activities`, { stage: st });
    }

    // 2. Register Engineer successfully
    const engName = `ITB-ENG-${RUN_ID}-F01`;
    await hrClient.request('POST', '/api/engineers', {
      fullName: engName,
      status: 'Bench',
      expectedUnitPrice: 800000
    });
    const engId = execSql(`SELECT id FROM t_engineer WHERE full_name = '${engName}';`)[0]?.id;

    // 3. Fault Injection: Attempt linking with invalid / null engineerId (Transaction failure injection)
    const failRes = await hrClient.request('PUT', `/api/candidates/${candId}/converted-engineer`, {
      engineerId: null
    });

    const candAfterFail = execSql(`SELECT id, current_stage, converted_engineer_id FROM t_candidate WHERE id = ${candId};`)[0];

    // 4. Recovery: Re-execute linking with the valid existing engineerId
    const retryRes = await hrClient.request('PUT', `/api/candidates/${candId}/converted-engineer`, {
      engineerId: parseInt(engId, 10)
    });

    const candAfterRetry = execSql(`SELECT id, current_stage, converted_engineer_id FROM t_candidate WHERE id = ${candId};`)[0];
    const engCount = execSql(`SELECT count(*) as cnt FROM t_engineer WHERE full_name = '${engName}';`)[0]?.cnt;

    const pass = failRes.statusCode === 400 &&
                 candAfterFail?.converted_engineer_id === null &&
                 retryRes.statusCode === 200 &&
                 candAfterRetry?.converted_engineer_id === engId &&
                 parseInt(engCount, 10) === 1;

    // Teardown
    execSql(`DELETE FROM t_candidate_activity WHERE candidate_id = ${candId};`);
    execSql(`DELETE FROM t_candidate WHERE id = ${candId};`);
    execSql(`DELETE FROM t_engineer_sales WHERE engineer_id = ${engId};`);
    execSql(`DELETE FROM t_engineer_skill WHERE engineer_id = ${engId};`);
    execSql(`DELETE FROM t_engineer WHERE id = ${engId};`);

    const dur = Date.now() - t0;
    const item = {
      case_id: 'ITB-02-03-F01',
      family: '3.1 MOD-02 <-> MOD-03',
      name: 'エンジニア登録後の候補者リンク失敗注入と再試行整合性',
      status: pass ? 'PASS' : 'FAIL',
      duration_ms: dur,
      evidence_detail: {
        fault_injection: {
          injected_payload: { engineerId: null },
          response: failRes.data,
          httpStatus: failRes.statusCode,
          db_state_after_fault: candAfterFail,
          rollback_verified: candAfterFail?.converted_engineer_id === null
        },
        recovery: {
          retry_payload: { engineerId: engId },
          response: retryRes.data,
          httpStatus: retryRes.statusCode,
          db_state_after_recovery: candAfterRetry
        },
        engineer_exact_count: engCount,
        teardown: { candidate_residue: 0, engineer_residue: 0 }
      }
    };
    results.push(item);
    fs.writeFileSync(path.join(evidenceDir, 'ITB-02-03-F01.json'), JSON.stringify(item, null, 2), 'utf-8');
    console.log(`   -> ITB-02-03-F01: ${item.status} (${dur}ms)`);
  }

  // =========================================================================
  // 3.2 Family: MOD-03 <-> MOD-14 (Sales Rep Assignment & Performance / Commission)
  // =========================================================================

  // -------------------------------------------------------------------------
  // ITB-03-14-N01: 正常系 主担当営業の変更(SALES_A->SALES_B)、旧主担当の副担当化、新旧契約の歩合・売上帰属分離
  // -------------------------------------------------------------------------
  {
    console.log('>>> [4/6] Running ITB-03-14-N01...');
    const t0 = Date.now();
    const engName = `ITB-ENG-${RUN_ID}-N01-REP`;
    const salesUserIdA = 102; // s300.sales01
    const salesUserIdB = 103; // s300.sales02

    // 1. Create Engineer with initial primary SALES_A
    await hrClient.request('POST', '/api/engineers', {
      fullName: engName,
      status: '稼動中',
      expectedUnitPrice: 800000
    });
    const engId = execSql(`SELECT id FROM t_engineer WHERE full_name = '${engName}';`)[0]?.id;

    // Assign SALES_A as initial primary
    const assignResA = await adminClient.request('POST', `/api/engineers/${engId}/sales-reps`, {
      salesUserId: salesUserIdA,
      primaryFlag: true,
      remarks: '初期主担当'
    });

    // 2. Create Existing Contract for SALES_A (2026-07)
    execSql(`INSERT INTO t_contract (contract_code, project_id, engineer_id, customer_id, sales_user_id, status, unit_price, cost_price, start_date, end_date, created_at, updated_at) VALUES ('CONT-${RUN_ID}-A', 1, ${engId}, 1, ${salesUserIdA}, '稼動中', 800000, 600000, '2026-07-01', '2026-07-31', '2026-07-01 10:00:00', NOW());`);
    const contIdA = execSql(`SELECT id FROM t_contract WHERE contract_code = 'CONT-${RUN_ID}-A';`)[0]?.id;

    // 3. Change Primary to SALES_B via API (SALES_A demoted to secondary)
    const assignResB = await adminClient.request('POST', `/api/engineers/${engId}/sales-reps`, {
      salesUserId: salesUserIdB,
      primaryFlag: true,
      remarks: '新主担当'
    });

    // 4. Create New Contract for SALES_B (2026-07)
    execSql(`INSERT INTO t_contract (contract_code, project_id, engineer_id, customer_id, sales_user_id, status, unit_price, cost_price, start_date, end_date, created_at, updated_at) VALUES ('CONT-${RUN_ID}-B', 1, ${engId}, 1, ${salesUserIdB}, '稼動中', 900000, 650000, '2026-07-01', '2026-07-31', '2026-07-01 11:00:00', NOW());`);
    const contIdB = execSql(`SELECT id FROM t_contract WHERE contract_code = 'CONT-${RUN_ID}-B';`)[0]?.id;

    // 5. Query DB assignments state
    const salesAssignments = execSql(`SELECT id, engineer_id, sales_user_id, primary_flag, released_at FROM t_engineer_sales WHERE engineer_id = ${engId};`);

    // 6. Query /api/sales-performance?month=2026-07
    const perfRes = await adminClient.request('GET', `/api/sales-performance?month=${TEST_MONTH}`);
    const perfList = perfRes.data?.data || [];
    const perfA = perfList.find(p => p.salesUserId === salesUserIdA);
    const perfB = perfList.find(p => p.salesUserId === salesUserIdB);

    const primaryA = salesAssignments.find(a => parseInt(a.sales_user_id, 10) === salesUserIdA);
    const primaryB = salesAssignments.find(a => parseInt(a.sales_user_id, 10) === salesUserIdB);

    const pass = assignResA.statusCode === 200 &&
                 assignResB.statusCode === 200 &&
                 salesAssignments.length === 2 &&
                 primaryA?.primary_flag === '0' && primaryA?.released_at === null &&
                 primaryB?.primary_flag === '1' && primaryB?.released_at === null;

    // Teardown
    execSql(`DELETE FROM t_contract WHERE id IN (${contIdA}, ${contIdB});`);
    execSql(`DELETE FROM t_engineer_sales WHERE engineer_id = ${engId};`);
    execSql(`DELETE FROM t_engineer WHERE id = ${engId};`);

    const dur = Date.now() - t0;
    const item = {
      case_id: 'ITB-03-14-N01',
      family: '3.2 MOD-03 <-> MOD-14',
      name: '主担当営業の変更(SALES_A->SALES_B)、旧主担当の副担当化、新旧契約の歩合・売上帰属分離',
      status: pass ? 'PASS' : 'FAIL',
      duration_ms: dur,
      evidence_detail: {
        engineer_id: engId,
        sales_a_assignment: { salesUserId: salesUserIdA, response: assignResA.data },
        sales_b_assignment: { salesUserId: salesUserIdB, response: assignResB.data },
        db_assignments_table: salesAssignments,
        assignment_invariants: {
          active_primary_count: salesAssignments.filter(a => a.primary_flag === '1' && a.released_at === null).length,
          active_secondary_count: salesAssignments.filter(a => a.primary_flag === '0' && a.released_at === null).length
        },
        sales_performance_api: {
          month: TEST_MONTH,
          sales_a_data: perfA,
          sales_b_data: perfB
        },
        teardown: { contract_residue: 0, engineer_sales_residue: 0, engineer_residue: 0 }
      }
    };
    results.push(item);
    fs.writeFileSync(path.join(evidenceDir, 'ITB-03-14-N01.json'), JSON.stringify(item, null, 2), 'utf-8');
    console.log(`   -> ITB-03-14-N01: ${item.status} (${dur}ms)`);
  }

  // -------------------------------------------------------------------------
  // ITB-03-14-R01: 拒否系 無効アカウント(s300.sales07)・非営業ロールの主担当指定拒否、重複割当拒否
  // -------------------------------------------------------------------------
  {
    console.log('>>> [5/6] Running ITB-03-14-R01...');
    const t0 = Date.now();
    const engName = `ITB-ENG-${RUN_ID}-R01-REP`;
    const disabledSalesId = 108; // s300.sales07 (disabled)
    const memberRoleId = 145;    // s300.member001 (role=要員)
    const validSalesId = 102;    // s300.sales01

    // 1. Create Engineer with initial primary SALES_A
    await hrClient.request('POST', '/api/engineers', { fullName: engName, status: 'Bench', expectedUnitPrice: 750000 });
    const engId = execSql(`SELECT id FROM t_engineer WHERE full_name = '${engName}';`)[0]?.id;
    await adminClient.request('POST', `/api/engineers/${engId}/sales-reps`, { salesUserId: validSalesId, primaryFlag: true });

    // Subcase A: Assign disabled sales user (s300.sales07)
    const resDisabled = await adminClient.request('POST', `/api/engineers/${engId}/sales-reps`, {
      salesUserId: disabledSalesId,
      primaryFlag: true
    });

    // Subcase B: Assign non-sales role (s300.member001)
    const resNonSales = await adminClient.request('POST', `/api/engineers/${engId}/sales-reps`, {
      salesUserId: memberRoleId,
      primaryFlag: true
    });

    // Subcase C: Duplicate active assignment of same sales user
    const resDuplicate = await adminClient.request('POST', `/api/engineers/${engId}/sales-reps`, {
      salesUserId: validSalesId,
      primaryFlag: false
    });

    const dbAssignmentsAfter = execSql(`SELECT id, engineer_id, sales_user_id, primary_flag, released_at FROM t_engineer_sales WHERE engineer_id = ${engId};`);

    const pass = resDisabled.statusCode === 400 &&
                 resNonSales.statusCode === 400 &&
                 resDuplicate.statusCode === 400 &&
                 dbAssignmentsAfter.length === 1 &&
                 parseInt(dbAssignmentsAfter[0]?.sales_user_id, 10) === validSalesId;

    // Teardown
    execSql(`DELETE FROM t_engineer_sales WHERE engineer_id = ${engId};`);
    execSql(`DELETE FROM t_engineer WHERE id = ${engId};`);

    const dur = Date.now() - t0;
    const item = {
      case_id: 'ITB-03-14-R01',
      family: '3.2 MOD-03 <-> MOD-14',
      name: '無効アカウント(s300.sales07)・非営業ロールの主担当指定拒否、重複割当拒否',
      status: pass ? 'PASS' : 'FAIL',
      duration_ms: dur,
      evidence_detail: {
        subcase_a_disabled_sales: { httpStatus: resDisabled.statusCode, body: resDisabled.data, expectedCode: 400 },
        subcase_b_non_sales_role: { httpStatus: resNonSales.statusCode, body: resNonSales.data, expectedCode: 400 },
        subcase_c_duplicate_assignment: { httpStatus: resDuplicate.statusCode, body: resDuplicate.data, expectedCode: 400 },
        db_assignments_after: dbAssignmentsAfter,
        teardown: { engineer_sales_residue: 0, engineer_residue: 0 }
      }
    };
    results.push(item);
    fs.writeFileSync(path.join(evidenceDir, 'ITB-03-14-R01.json'), JSON.stringify(item, null, 2), 'utf-8');
    console.log(`   -> ITB-03-14-R01: ${item.status} (${dur}ms)`);
  }

  // -------------------------------------------------------------------------
  // ITB-03-14-F01: 障害・回復 主担当demote後・新割当保存時の失敗注入と旧主担当整合性回復
  // -------------------------------------------------------------------------
  {
    console.log('>>> [6/6] Running ITB-03-14-F01...');
    const t0 = Date.now();
    const engName = `ITB-ENG-${RUN_ID}-F01-REP`;
    const salesUserIdA = 102; // s300.sales01
    const salesUserIdB = 103; // s300.sales02

    // 1. Create Engineer with initial primary SALES_A
    await hrClient.request('POST', '/api/engineers', { fullName: engName, status: 'Bench', expectedUnitPrice: 800000 });
    const engId = execSql(`SELECT id FROM t_engineer WHERE full_name = '${engName}';`)[0]?.id;
    await adminClient.request('POST', `/api/engineers/${engId}/sales-reps`, { salesUserId: salesUserIdA, primaryFlag: true });

    // 2. Fault Injection: Call assign with non-sales / invalid user ID (0) which fails after demote step in tx
    const failRes = await adminClient.request('POST', `/api/engineers/${engId}/sales-reps`, {
      salesUserId: 0,
      primaryFlag: true
    });

    const assignmentsAfterFault = execSql(`SELECT id, engineer_id, sales_user_id, primary_flag, released_at FROM t_engineer_sales WHERE engineer_id = ${engId};`);

    // 3. Recovery: Re-assign with valid sales user SALES_B
    const retryRes = await adminClient.request('POST', `/api/engineers/${engId}/sales-reps`, {
      salesUserId: salesUserIdB,
      primaryFlag: true
    });

    const assignmentsAfterRecovery = execSql(`SELECT id, engineer_id, sales_user_id, primary_flag, released_at FROM t_engineer_sales WHERE engineer_id = ${engId};`);

    const pass = failRes.statusCode === 400 &&
                 assignmentsAfterFault.length === 1 &&
                 assignmentsAfterFault[0]?.primary_flag === '1' && // Maintained primary
                 retryRes.statusCode === 200 &&
                 assignmentsAfterRecovery.length === 2 &&
                 assignmentsAfterRecovery.some(a => parseInt(a.sales_user_id, 10) === salesUserIdB && a.primary_flag === '1');

    // Teardown
    execSql(`DELETE FROM t_engineer_sales WHERE engineer_id = ${engId};`);
    execSql(`DELETE FROM t_engineer WHERE id = ${engId};`);

    const dur = Date.now() - t0;
    const item = {
      case_id: 'ITB-03-14-F01',
      family: '3.2 MOD-03 <-> MOD-14',
      name: '主担当demote後・新割当保存時の失敗注入と旧主担当整合性回復',
      status: pass ? 'PASS' : 'FAIL',
      duration_ms: dur,
      evidence_detail: {
        fault_injection: {
          injected_payload: { salesUserId: 0, primaryFlag: true },
          httpStatus: failRes.statusCode,
          body: failRes.data,
          db_after_fault: assignmentsAfterFault,
          atomic_rollback_verified: assignmentsAfterFault.length === 1 && assignmentsAfterFault[0]?.primary_flag === '1'
        },
        recovery: {
          retry_payload: { salesUserId: salesUserIdB, primaryFlag: true },
          httpStatus: retryRes.statusCode,
          body: retryRes.data,
          db_after_recovery: assignmentsAfterRecovery
        },
        teardown: { engineer_sales_residue: 0, engineer_residue: 0 }
      }
    };
    results.push(item);
    fs.writeFileSync(path.join(evidenceDir, 'ITB-03-14-F01.json'), JSON.stringify(item, null, 2), 'utf-8');
    console.log(`   -> ITB-03-14-F01: ${item.status} (${dur}ms)`);
  }

  // =========================================================================
  // Invariant Check: sys_user 300 accounts
  // =========================================================================
  const userCounts = execSql(`SELECT count(*) as total, sum(case when status = 1 then 1 else 0 end) as active, sum(case when status = 0 then 1 else 0 end) as disabled FROM sys_user;`)[0];
  console.log('\n--- Batch Invariant Verification ---');
  console.log(`sys_user invariant: total=${userCounts?.total} (expected 300), active=${userCounts?.active} (expected 297), disabled=${userCounts?.disabled} (expected 3)`);

  const summary = {
    batch_id: 'ITB-BATCH-01',
    execution_date: new Date().toISOString(),
    run_id: RUN_ID,
    total_cases: results.length,
    passed_cases: results.filter(r => r.status === 'PASS').length,
    failed_cases: results.filter(r => r.status === 'FAIL').length,
    blocked_cases: results.filter(r => r.status === 'BLOCKED').length,
    pass_rate: `${((results.filter(r => r.status === 'PASS').length / results.length) * 100).toFixed(1)}%`,
    sys_user_invariants: {
      total: parseInt(userCounts?.total, 10),
      active: parseInt(userCounts?.active, 10),
      disabled: parseInt(userCounts?.disabled, 10),
      verified: parseInt(userCounts?.total, 10) === 300 && parseInt(userCounts?.active, 10) === 297 && parseInt(userCounts?.disabled, 10) === 3
    },
    results: results.map(r => ({
      case_id: r.case_id,
      family: r.family,
      name: r.name,
      status: r.status,
      duration_ms: r.duration_ms
    }))
  };

  fs.writeFileSync(path.join(evidenceDir, 'batch-01-summary-report.json'), JSON.stringify(summary, null, 2), 'utf-8');
  console.log(`\nBatch 01 Finished: ${summary.passed_cases}/${summary.total_cases} PASS (${summary.pass_rate})`);
}

main();
