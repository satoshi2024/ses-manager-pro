import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { SURNAMES, MALE_NAMES, FEMALE_NAMES } from './names.mjs';
import { CUSTOMERS, BP_COMPANIES, PROJECT_TEMPLATES, STATIONS, BUSINESS_CONTENT, TASK_TITLES } from './companies.mjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..', '..');
const OUT_SQL = path.join(REPO_ROOT, 'sql', 'seed', 'r3-scale-300', 'seed.sql');
const OUT_MIGRATION = path.join(REPO_ROOT, 'src', 'main', 'resources', 'db', 'migration-dev', 'V100__seed_r3_scale_300.sql');

// ---------- deterministic RNG ----------
function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rnd = mulberry32(300);
const pick = (arr) => arr[Math.floor(rnd() * arr.length)];
const pickWeighted = (pairs) => {
  const total = pairs.reduce((s, p) => s + p[1], 0);
  let r = rnd() * total;
  for (const [v, w] of pairs) {
    r -= w;
    if (r <= 0) return v;
  }
  return pairs[pairs.length - 1][0];
};
const int = (min, max) => Math.floor(rnd() * (max - min + 1)) + min;
const roundTo = (v, unit) => Math.round(v / unit) * unit;
const pad = (n, w) => String(n).padStart(w, '0');

// ---------- SQL helpers ----------
const S = (v) => (v === null || v === undefined ? 'NULL' : `'${String(v).replace(/'/g, "''")}'`);
const D = (y, m, d) => `${y}-${pad(m, 2)}-${pad(d, 2)}`;
const DT = (y, m, d, hh = 10, mm = 0) => `${D(y, m, d)} ${pad(hh, 2)}:${pad(mm, 2)}:00`;
const escArr = (arr) => arr.map((v) => S(v)).join(', ');

let sql = '';
const insert = (table, columns, rows) => {
  if (!rows.length) return;
  const colSql = columns.map((c) => `\`${c}\``).join(',');
  const chunkSize = 200;
  for (let i = 0; i < rows.length; i += chunkSize) {
    const chunk = rows.slice(i, i + chunkSize);
    const values = chunk.map((r) => `(${r.map((v) => S(v)).join(',')})`).join(',\n');
    sql += `INSERT INTO \`${table}\` (${colSql}) VALUES\n${values};\n`;
  }
};
const counts = {};
const count = (table, n) => { counts[table] = (counts[table] || 0) + n; };
const emit = (table, columns, rows) => { insert(table, columns, rows); count(table, rows.length); };

// ---------- name generation ----------
const usedNames = new Set();
function personName() {
  for (let tries = 0; tries < 500; tries++) {
    const surname = pick(SURNAMES).split('/');
    const female = rnd() < 0.32;
    const given = pick(female ? FEMALE_NAMES : MALE_NAMES).split('/');
    const full = `${surname[0]} ${given[0]}`;
    if (!usedNames.has(full)) {
      usedNames.add(full);
      return { fullName: full, kana: `${surname[1]} ${given[1]}`, gender: female ? '女性' : '男性' };
    }
  }
  throw new Error('名前プール枯渇');
}

// ---------- section 1: users ----------
const users = [];
let nextUserId = 101;
const rolePlan = [
  ['管理者', 1],
  ['営業', 25],
  ['HR', 8],
  ['マネージャー', 10],
  ['要員', 252]
];
for (const [role, cnt] of rolePlan) {
  const prefix = role === '管理者' ? 'admin' : role === '営業' ? 'sales' : role === 'HR' ? 'hr' : role === 'マネージャー' ? 'mgr' : 'member';
  for (let i = 1; i <= cnt; i++) {
    const name = personName();
    const id = nextUserId++;
    const username = `s300.${prefix}${pad(i, role === '要員' ? 3 : 2)}`;
    users.push({
      id,
      username,
      password: 'Scale300!',
      realName: name.fullName,
      kana: name.kana,
      role,
      gender: name.gender,
      email: `${username}@ses.local`,
      status: role === '営業' && i === 7 ? 0 : role === 'HR' && i === 5 ? 0 : role === '要員' && i === 200 ? 0 : 1
    });
  }
}
// V2初期マスタの要員3名（田中/山田/伊藤）を300人の中に含める
const legacyMembers = [
  { fullName: '田中 太郎', kana: 'タナカ タロウ', gender: '男性' },
  { fullName: '山田 花子', kana: 'ヤマダ ハナコ', gender: '女性' },
  { fullName: '伊藤 健太', kana: 'イトウ ケンタ', gender: '男性' }
];
legacyMembers.forEach((m, i) => {
  const id = nextUserId++;
  const username = `s300.member${pad(253 + i, 3)}`;
  users.push({
    id,
    username,
    password: 'Scale300!',
    realName: m.fullName,
    kana: m.kana,
    role: '要員',
    gender: m.gender,
    email: `${username}@ses.local`,
    status: 1
  });
});
const salesUsers = users.filter((u) => u.role === '営業');
const hrUsers = users.filter((u) => u.role === 'HR');
const managerUsers = users.filter((u) => u.role === 'マネージャー');
const adminUsers = users.filter((u) => u.role === '管理者');
const memberUsers = users.filter((u) => u.role === '要員');

// ---------- section 2: organizations / cost centers ----------
const orgs = [
  [3001, 'HQ', '事業本部', '事業部', null],
  [3002, 'SALES1', '営業第一部', '部', 3001],
  [3003, 'SALES2', '営業第二部', '部', 3001],
  [3004, 'TECH', '技術本部', '事業部', 3001],
  [3005, 'TECH1', '技術第一部', '部', 3004],
  [3006, 'TECH2', '技術第二部', '部', 3004],
  [3007, 'TECH3', '技術第三部', '部', 3004],
  [3008, 'HR', 'HR部', '部', 3001],
  [3009, 'ADMIN', '管理部', '部', 3001]
];
const costCenters = [
  [4001, 'TECH1-CC', '技術第一部原価', 3005],
  [4002, 'TECH2-CC', '技術第二部原価', 3006],
  [4003, 'TECH3-CC', '技術第三部原価', 3007],
  [4004, 'SALES1-CC', '営業第一部原価', 3002],
  [4005, 'SALES2-CC', '営業第二部原価', 3003],
  [4006, 'HR-CC', 'HR部原価', 3008],
  [4007, 'ADMIN-CC', '管理部原価', 3009],
  [4008, 'HQ-CC', '事業本部共通', 3001]
];

// ---------- section 3: customers / contacts / workplaces ----------
const customers = [];
const customerContacts = [];
const workplaces = [];
let nextContactId = 2101;
let nextWorkplaceId = 2201;
CUSTOMERS.forEach((c, i) => {
  const id = 2001 + i;
  const trust = pickWeighted([['S', 8], ['A', 12], ['B', 10], ['C', 5]]);
  const flow = pickWeighted([['元請', 10], ['一次請', 16], ['二次請', 9]]);
  customers.push({
    id,
    companyName: c[0],
    kana: c[1],
    contactPerson: null,
    contactEmail: null,
    contactPhone: null,
    address: `${c[2]}都道府県${c[2]}市中央区本町${int(1, 30)}-${int(1, 15)}-${int(1, 10)}`,
    flow,
    trust,
    remarks: `${c[3]}向け。長期継続取引あり。`
  });
  const contactCount = i % 5 === 0 ? 2 : 1;
  for (let j = 0; j < contactCount; j++) {
    const name = personName();
    customerContacts.push({
      id: nextContactId++,
      customerId: id,
      name: name.fullName,
      kana: name.kana,
      department: j === 0 ? '情報システム部' : '経営企画部',
      position: j === 0 ? '部長' : '担当',
      rolesJson: JSON.stringify(j === 0 ? ['決裁者', '窓口', '契約'] : ['現場', '請求']),
      email: `contact${j === 0 ? '' : j + 1}@customer${id}.example.jp`,
      phone: `0${int(3, 9)}0-${pad(int(100, 999), 3)}-${pad(int(1000, 9999), 4)}`,
      primaryFlag: j === 0 ? 1 : 0,
      validFrom: D(2020, 4, 1),
      status: '有効',
      version: 1
    });
  }
  workplaces.push({
    id: nextWorkplaceId++,
    tenantId: 'default',
    customerId: id,
    organizationId: 3001,
    name: `${c[0]} ${c[2]}事業所`,
    address: `${c[2]}都道府県${c[2]}市中央区${int(1, 20)}-${int(1, 10)}`,
    organizationUnit: '情報システム部',
    phone: `0${int(3, 9)}0-${pad(int(100, 999), 3)}-${pad(int(1000, 9999), 4)}`,
    validFrom: D(2020, 4, 1),
    status: 'ACTIVE',
    version: 0
  });
});

// ---------- section 4: engineers ----------
const engineers = [];
const engineerSkills = [];
const engineerCareers = [];
const engineerSales = [];
const engineerAccountingHistory = [];
const engineerBpAffiliations = [];
const engineerAccountLinks = [];
let nextEngineerSkillId = 5301;
let nextCareerId = 5401;
let nextEngSalesId = 5601;
let nextAcctHistId = 28001;

const techOrgs = [3005, 3006, 3007];
const techCostCenters = [4001, 4002, 4003];
const legacyEngineerDefs = [
  {
    id: 1,
    fullName: '田中 太郎',
    kana: 'タナカ タロウ',
    initialName: 'T.T',
    gender: '男性',
    employment: '正社員',
    status: '稼動中',
    unitPrice: 800000,
    exp: 5,
    orgId: techOrgs[1],
    costCenterId: techCostCenters[1],
    resumeSummary: 'Javaバックエンド開発を中心に、Spring Bootを用いたAPI設計・実装経験が豊富です。直近ではクラウド（AWS）を活用した基盤構築にも携わっています。'
  },
  {
    id: 2,
    fullName: '山田 花子',
    kana: 'ヤマダ ハナコ',
    initialName: 'Y.H',
    gender: '女性',
    employment: '契約社員',
    status: '提案中',
    unitPrice: 700000,
    exp: 3,
    orgId: techOrgs[2],
    costCenterId: techCostCenters[2],
    resumeSummary: 'フロントエンド開発を得意とし、ReactやVue.jsを用いたSPAの開発経験があります。UI/UXを意識した実装を心がけています。'
  },
  {
    id: 3,
    fullName: '伊藤 健太',
    kana: 'イトウ ケンタ',
    initialName: 'I.K',
    gender: '男性',
    employment: 'BP',
    status: 'Bench',
    unitPrice: 600000,
    exp: 2,
    orgId: techOrgs[0],
    costCenterId: techCostCenters[0],
    resumeSummary: 'Pythonを用いたデータ分析スクリプトの作成や、Djangoでの簡単なWebアプリケーション構築経験があります。現在は新しい技術の習得に意欲的です。'
  }
];
for (const def of legacyEngineerDefs) {
  engineers.push({
    id: def.id,
    fullName: def.fullName,
    kana: def.kana,
    initialName: def.initialName,
    gender: def.gender,
    birthDate: D(1988, 4, 1),
    nationality: '日本',
    station: STATIONS[0][0],
    prefecture: STATIONS[0][1],
    railway: STATIONS[0][2],
    employment: def.employment,
    status: def.status,
    unitPrice: def.unitPrice,
    costCenterId: def.costCenterId,
    organizationId: def.orgId,
    overtimeExempt: null,
    availableDate: def.status === 'Bench' || def.status === '提案中' ? D(2026, 8, 1) : null,
    exp: def.exp,
    japaneseLevel: 'ネイティブ',
    resumeSummary: def.resumeSummary,
    remarks: def.status === 'Bench' ? '長期Benchのため営業フォロー重点。' : null,
    createdBy: 1
  });
}
const skillIdByName = {
  Java: 1, Python: 2, JavaScript: 3, TypeScript: 4, 'C#': 5, PHP: 6, Go: 7, Kotlin: 8, Swift: 9,
  Ruby: 10, 'C++': 11, Scala: 12, SQL: 13, 'Spring Boot': 14, React: 15, 'Vue.js': 16, Angular: 17,
  Django: 18, Flask: 19, '.NET': 20, 'Next.js': 21, 'Express.js': 22, Laravel: 23, MyBatis: 24,
  Hibernate: 25, MySQL: 26, PostgreSQL: 27, Oracle: 28, 'SQL Server': 29, MongoDB: 30, Redis: 31,
  DynamoDB: 32, AWS: 33, Azure: 34, GCP: 35, Docker: 36, Kubernetes: 37, Linux: 38, 'Windows Server': 39,
  macOS: 40, Git: 41, Jenkins: 42, Jira: 43, Confluence: 44, Slack: 45, Teams: 46, SVN: 47, Maven: 48, Gradle: 49
};
const languageSkills = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12];
const frameworkSkills = [14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25];
const dbSkills = [26, 27, 28, 29, 30, 31];
const cloudSkills = [33, 34, 35, 36, 37];

memberUsers.forEach((user, idx) => {
  const legacy = idx >= 252;
  const legacyDef = legacy ? legacyEngineerDefs[idx - 252] : null;
  const engineerId = legacy ? legacyDef.id : 1001 + idx;
  const status = legacy ? legacyDef.status : idx < 165 ? '稼動中' : idx < 205 ? 'Bench' : idx < 235 ? '提案中' : '退場予定';
  const employment = legacy ? legacyDef.employment : idx % 17 === 0 ? 'BP' : idx % 8 === 0 ? '契約社員' : '正社員';
  const exp = legacy ? legacyDef.exp : int(1, 25);
  const org = legacy ? legacyDef.orgId : techOrgs[idx % 3];
  const cc = legacy ? legacyDef.costCenterId : techCostCenters[idx % 3];
  const unitPrice = legacy ? legacyDef.unitPrice : Math.min(950000, roundTo(380000 + exp * 17000 + rnd() * 120000, 10000));
  const station = pick(STATIONS);
  const mainLang = pick(languageSkills);
  const mainLangName = Object.keys(skillIdByName).find((k) => skillIdByName[k] === mainLang);
  if (!legacy) {
    engineers.push({
      id: engineerId,
      fullName: user.realName,
      kana: user.kana,
      initialName: `${user.realName.replace(' ', ' ').split(' ').map((s) => s.charAt(0)).join('.')}.`,
      gender: user.gender,
      birthDate: D(int(1966, 2003), int(1, 12), int(1, 28)),
      nationality: '日本',
      station: station[0],
      prefecture: station[1],
      railway: station[2],
      employment,
      status,
      unitPrice,
      costCenterId: cc,
      organizationId: org,
      overtimeExempt: employment === '正社員' && rnd() < 0.15 ? 1 : null,
      availableDate: status === 'Bench' || status === '提案中' ? D(2026, int(8, 10), int(1, 28)) : null,
      exp,
      japaneseLevel: 'ネイティブ',
      resumeSummary: `${exp}年の開発経験。${mainLangName}を中心に、要件定義から保守運用まで一貫して対応。`,
      remarks: status === 'Bench' ? '長期Benchのため営業フォロー重点。' : null,
      createdBy: 1
    });
  }
  // skills: 4-7 tags
  const skillCount = int(4, 7);
  const chosen = new Set([mainLang]);
  const pools = [languageSkills, frameworkSkills, dbSkills, cloudSkills];
  while (chosen.size < skillCount) {
    const pool = pick(pools);
    chosen.add(pool[int(0, pool.length - 1)]);
  }
  for (const skillId of chosen) {
    engineerSkills.push({
      id: nextEngineerSkillId++,
      engineerId,
      skillId,
      proficiency: pickWeighted([['初級', 2], ['中級', 5], ['上級', 3]]),
      experienceYears: Math.max(1, Math.round(exp * (0.4 + rnd() * 0.6)))
    });
  }
  // careers 2-4
  const careerCount = int(2, 4);
  let careerEndYear = 2026;
  for (let c = 0; c < careerCount; c++) {
    const fromYear = 2018 + c * 3 + int(0, 1);
    const toYear = c === careerCount - 1 ? 2026 : fromYear + 2;
    engineerCareers.push({
      id: nextCareerId++,
      engineerId,
      periodFrom: D(fromYear, 4, 1),
      periodTo: D(toYear, 3, 31),
      projectName: pick(PROJECT_TEMPLATES),
      clientIndustry: pick(['金融', '製造', '通信', '公共', '流通', '医療']),
      role: pick(['開発エンジニア', 'リーダー', 'テスター', '保守運用', '設計担当']),
      description: pick(BUSINESS_CONTENT),
      techStack: pick(['Java/Spring Boot/MySQL', 'Python/Django/AWS', 'TypeScript/React/Node.js', 'C#/.NET/SQL Server', 'Go/GCP/Kubernetes']),
      teamSize: int(3, 15)
    });
    careerEndYear = toYear;
  }
  // sales assignment
  const sales = salesUsers[idx % salesUsers.length];
  engineerSales.push({
    id: nextEngSalesId++,
    engineerId,
    salesUserId: sales.id,
    primaryFlag: 1,
    assignedAt: D(2024, int(1, 12), int(1, 28)),
    releasedAt: null,
    remarks: null,
    deletedFlag: 0
  });
  if (idx % 11 === 0) {
    const oldSales = salesUsers[(idx + 7) % salesUsers.length];
    engineerSales.push({
      id: nextEngSalesId++,
      engineerId,
      salesUserId: oldSales.id,
      primaryFlag: 0,
      assignedAt: D(2023, int(1, 12), int(1, 28)),
      releasedAt: D(2024, int(1, 12), int(1, 28)),
      remarks: '担当変更のため解除',
      deletedFlag: 0
    });
  }
  // accounting history
  engineerAccountingHistory.push({
    id: nextAcctHistId++,
    engineerId,
    organizationId: org,
    organizationHistoryStatus: 'KNOWN',
    costCenterId: cc,
    expectedUnitPrice: unitPrice,
    validFrom: D(2024, 4, 1),
    validTo: null,
    deletedFlag: 0
  });
  // BP affiliation
  if (employment === 'BP') {
    const bpId = 11001 + (idx % 20);
    engineerBpAffiliations.push({
      id: 11801 + idx,
      tenantId: 1,
      engineerId,
      bpCompanyId: bpId,
      validFrom: D(2024, 4, 1),
      validTo: null,
      deletedFlag: 0
    });
  }
  // account link
  engineerAccountLinks.push({
    id: 31001 + idx,
    engineerId,
    sysUserId: user.id,
    linkedBy: 1
  });
});

// ---------- section 5: projects ----------
const projects = [];
const projectSkills = [];
let nextProjectSkillId = 5201;
const projectStatuses = (() => {
  const arr = [];
  for (let i = 0; i < 100; i++) {
    arr.push(i < 30 ? '募集中' : i < 50 ? '選考中' : i < 80 ? '充足' : 'クローズ');
  }
  return arr;
})();
for (let i = 0; i < 100; i++) {
  const id = 5001 + i;
  const customer = customers[int(0, customers.length - 1)];
  const remote = pickWeighted([['フル出社', 30], ['フルリモート', 30], ['ハイブリッド', 40]]);
  const startYear = int(2025, 2026);
  const endYear = startYear + 1;
  const unitMin = roundTo(int(400000, 750000), 10000);
  projects.push({
    id,
    projectName: `${pick(PROJECT_TEMPLATES)}${i % 3 === 0 ? `（Phase${int(1, 3)}）` : ''}`,
    customerId: customer.id,
    flow: customer.flow,
    description: pick(BUSINESS_CONTENT),
    requiredCount: int(1, 5),
    unitMin,
    unitMax: unitMin + roundTo(int(50000, 200000), 10000),
    workLocation: pick(STATIONS)[0],
    remote,
    startDate: D(startYear, int(4, 12), 1),
    endDate: D(endYear, int(3, 12), 28),
    status: projectStatuses[i],
    priority: pickWeighted([['通常', 70], ['急募', 20], ['高利益', 10]]),
    remarks: null,
    sourceOpportunityId: null,
    createdBy: 1
  });
  const skillCount = int(2, 4);
  const chosen = new Set();
  while (chosen.size < skillCount) {
    chosen.add(pick(languageSkills.concat(frameworkSkills, dbSkills, cloudSkills)));
  }
  for (const skillId of chosen) {
    projectSkills.push({
      id: nextProjectSkillId++,
      projectId: id,
      skillId,
      requiredLevel: pickWeighted([['初級', 2], ['中級', 5], ['上級', 3]]),
      isMust: rnd() < 0.5 ? 1 : 0
    });
  }
}

// ---------- section 6: proposals ----------
const proposals = [];
const proposalHistory = [];
let nextProposalHistoryId = 6401;
const proposalStatuses = (() => {
  const arr = [];
  const counts = { '書類選考中': 35, '一次面接': 30, '二次面接': 20, '結果待ち': 20, '成約': 25, '見送り': 20 };
  for (const [st, n] of Object.entries(counts)) {
    for (let i = 0; i < n; i++) arr.push(st);
  }
  return arr;
})();
const proposalEngineerIndexes = (() => {
  // Bench/proposing/active engineers mix
  const arr = [];
  for (let i = 0; i < 150; i++) arr.push((i * 7 + 3) % 255);
  return arr;
})();
for (let i = 0; i < 150; i++) {
  const id = 6001 + i;
  const engineer = engineers[proposalEngineerIndexes[i]];
  const project = projects[int(0, projects.length - 1)];
  const status = proposalStatuses[i];
  const sales = salesUsers[i % salesUsers.length];
  const unitPrice = Math.min(950000, roundTo(engineer.unitPrice * (0.95 + rnd() * 0.15), 10000));
  const proposedAt = DT(2026, int(1, 7), int(1, 28), int(9, 18), int(0, 59));
  proposals.push({
    id,
    engineerId: engineer.id,
    projectId: project.id,
    proposedUnitPrice: unitPrice,
    status,
    skillSheetPath: null,
    proposalEmailText: `「${project.projectName}」へのご提案です。${engineer.fullName}のスキルシートをご確認ください。`,
    aiMatchScore: Math.round((55 + rnd() * 40) * 100) / 100,
    matchReason: `経験年数${engineer.exp}年、${pick(['Java', 'Python', 'TypeScript'])}案件の類似実績があり、スキル要件と高い親和性。`,
    remarks: status === '見送り' ? '客先都合により見送り。' : null,
    proposedBy: sales.id,
    proposedAt,
    closedAt: status === '成約' || status === '見送り' ? proposedAt : null,
    sourceOpportunityId: null,
    deletedFlag: 0
  });
  // history
  const stages = ['書類選考中'];
  if (['一次面接', '二次面接', '結果待ち', '成約', '見送り'].includes(status)) stages.push('一次面接');
  if (['二次面接', '結果待ち', '成約', '見送り'].includes(status)) stages.push('二次面接');
  if (['結果待ち', '成約', '見送り'].includes(status)) stages.push('結果待ち');
  if (['成約', '見送り'].includes(status)) stages.push(status);
  stages.forEach((st, idx) => {
    proposalHistory.push({
      id: nextProposalHistoryId++,
      proposalId: id,
      fromStatus: idx === 0 ? null : stages[idx - 1],
      toStatus: st,
      changedBy: sales.id,
      changedAt: DT(2026, 1 + idx, int(1, 28), int(9, 18), int(0, 59)),
      remarks: null
    });
  });
}

// ---------- section 7: quotations ----------
const quotations = [];
let nextQuotationId = 15001;
for (let i = 0; i < 60; i++) {
  const customer = customers[i % customers.length];
  const project = projects[(i * 3 + 1) % projects.length];
  const engineer = engineers[(i * 5 + 2) % engineers.length];
  const proposal = proposals[(i * 7 + 1) % proposals.length];
  const status = pickWeighted([['下書き', 2], ['提出済', 4], ['受注', 3], ['失注', 1]]);
  quotations.push({
    id: nextQuotationId++,
    quotationNo: `Q-2026-${pad(i + 1, 4)}`,
    customerId: customer.id,
    projectId: project.id,
    engineerId: engineer.id,
    proposalId: proposal.id,
    title: `要員提供のご提案（${engineer.fullName}）`,
    unitPrice: roundTo(engineer.unitPrice * (0.95 + rnd() * 0.1), 10000),
    settlementHoursMin: 150,
    settlementHoursMax: 190,
    validUntil: D(2026, int(8, 9), int(1, 28)),
    status,
    remarks: null,
    sourceOpportunityId: null,
    createdBy: salesUsers[i % salesUsers.length].id,
    version: 1
  });
}

// ---------- section 8: opportunities (before projects linkage is updated later) ----------
const opportunities = [];
let nextOpportunityId = 14501;
const opportunityStages = (() => {
  const arr = [];
  const counts = { '見込': 15, '要件確認': 12, '提案準備': 10, '見積提出': 8, '交渉': 5, '受注': 5, '失注': 5 };
  for (const [st, n] of Object.entries(counts)) {
    for (let i = 0; i < n; i++) arr.push(st);
  }
  return arr;
})();
for (let i = 0; i < 60; i++) {
  const customer = customers[i % customers.length];
  const stage = opportunityStages[i];
  const sales = salesUsers[i % salesUsers.length];
  const unitPrice = roundTo(int(450000, 850000), 10000);
  opportunities.push({
    id: nextOpportunityId++,
    customerId: customer.id,
    title: pick(PROJECT_TEMPLATES),
    stage,
    expectedStartMonth: `2026-${pad(int(9, 12), 2)}`,
    durationMonths: int(6, 24),
    requiredCount: int(1, 4),
    unitPrice,
    expectedAmount: unitPrice * int(6, 24),
    probability: stage === '失注' ? 0 : stage === '受注' ? 100 : int(20, 80),
    ownerUserId: sales.id,
    nextActionDate: stage === '失注' ? null : D(2026, int(8, 10), int(1, 28)),
    competitor: rnd() < 0.5 ? pick(['A社', 'B社', 'C社', 'D社']) : null,
    lostReason: stage === '失注' ? pick(['予算折り合わず', '他社に決定', '案件延期']) : null,
    convertedProjectId: null,
    convertedQuotationId: null,
    version: 1,
    stageChangedAt: DT(2026, int(1, 7), int(1, 28), int(9, 18), int(0, 59))
  });
}

// ---------- section 9: leads ----------
const leads = [];
let nextLeadId = 14001;
const leadStatuses = (() => {
  const arr = [];
  const counts = { '未対応': 18, '対応中': 22, '転換済': 12, '破棄': 8 };
  for (const [st, n] of Object.entries(counts)) {
    for (let i = 0; i < n; i++) arr.push(st);
  }
  return arr;
})();
for (let i = 0; i < 60; i++) {
  const base = customers[int(0, customers.length - 1)];
  const companyName = rnd() < 0.5 ? base.companyName : `株式会社${pick(['テクノ', 'システム', 'データ', 'ソリューション', 'ネットワーク'])}${pick(['企画', '開発', 'サービス', '総合', '情報'])}`;
  const status = leadStatuses[i];
  const sales = salesUsers[i % salesUsers.length];
  leads.push({
    id: nextLeadId++,
    companyName,
    companyNameNormalized: companyName.toLowerCase(),
    contactName: personName().fullName,
    contactEmail: `lead${i + 1}@example.jp`,
    contactEmailNormalized: `lead${i + 1}@example.jp`,
    contactPhone: `0${int(3, 9)}0-${pad(int(100, 999), 3)}-${pad(int(1000, 9999), 4)}`,
    contactPhoneNormalized: null,
    source: pick(['展示会', 'Web問い合わせ', '紹介', 'コールドコール', 'パートナー']),
    ownerUserId: sales.id,
    status,
    convertedCustomerId: status === '転換済' ? customers[i % customers.length].id : null,
    convertedOpportunityId: status === '転換済' ? opportunities[i % opportunities.length].id : null,
    version: 1,
    sourceCost: rnd() < 0.4 ? roundTo(int(50000, 300000), 10000) : null
  });
}

// ---------- section 10: sales orders ----------
const salesOrders = [];
const salesOrderLines = [];
let nextOrderId = 26001;
let nextOrderLineId = 26501;
const orderStatuses = ['下書き', '受領確認', '注文請提出', '契約化', '完了', '取消'];
for (let i = 0; i < 60; i++) {
  const id = nextOrderId++;
  const customer = customers[i % customers.length];
  const status = orderStatuses[i % orderStatuses.length];
  const orderDate = D(2026, int(1, 7), int(1, 28));
  const lineCount = int(1, 3);
  salesOrders.push({
    id,
    tenantId: 'default',
    legalEntityId: null,
    orderNo: `PO-2026-${pad(i + 1, 4)}`,
    customerPoNo: `PO-${pad(int(1000, 9999), 4)}`,
    customerId: customer.id,
    contactId: customerContacts.find((c) => c.customerId === customer.id && c.primaryFlag === 1)?.id ?? null,
    quotationId: quotations[i % quotations.length].id,
    orderDate,
    startDate: D(2026, int(7, 9), 1),
    endDate: D(2027, int(3, 12), 28),
    status,
    totalAmountSnapshot: null,
    paymentTermsSnapshot: '月末締め翌月末払い',
    sourceDocumentId: null,
    acknowledgementDocumentId: null,
    version: 0,
    createdBy: salesUsers[i % salesUsers.length].id
  });
  for (let j = 0; j < lineCount; j++) {
    const engineer = engineers[(i * 3 + j * 7) % engineers.length];
    const project = projects[(i + j) % projects.length];
    const unitPrice = roundTo(engineer.unitPrice * (0.95 + rnd() * 0.1), 10000);
    salesOrderLines.push({
      id: nextOrderLineId++,
      orderId: id,
      lineNo: j + 1,
      projectId: project.id,
      engineerId: engineer.id,
      quantity: 1,
      unitPrice,
      settlementMin: 150,
      settlementMax: 190,
      amount: unitPrice,
      remarks: null
    });
  }
}

// ---------- section 11: contracts ----------
const contracts = [];
const contractPriceHistory = [];
let nextContractId = 7001;
let nextPriceHistoryId = 7401;
let contractNoSeq = 1;
const contractProjects = projects.slice();
const makeContract = (engineer, status, startDate, endDate, proposal, orderLine, isRenewal) => {
  const id = nextContractId++;
  const project = pick(contractProjects);
  const selling = Math.min(980000, roundTo(engineer.unitPrice * (0.96 + rnd() * 0.12), 10000));
  const costRatio = engineer.employment === 'BP' ? 0.82 : 0.62;
  const cost = roundTo(selling * (costRatio + rnd() * 0.05), 10000);
  const contractType = pickWeighted([['準委任', 70], ['請負', 15], ['派遣', 15]]);
  contracts.push({
    id,
    contractNo: `CT-2025-${pad(contractNoSeq++, 4)}`,
    proposalId: proposal?.id ?? null,
    engineerId: engineer.id,
    projectId: project.id,
    customerId: project.customerId,
    salesUserId: engineerSales.find((e) => e.engineerId === engineer.id && e.primaryFlag === 1)?.salesUserId ?? salesUsers[0].id,
    contractType,
    startDate,
    contractDate: startDate,
    jobDescription: pick(BUSINESS_CONTENT),
    workLocation: pick(STATIONS)[0],
    inspectionDueDate: null,
    paymentDueDate: null,
    paymentMethod: '月末締め翌月末払い',
    endDate,
    selling,
    cost,
    costCenterId: engineer.costCenterId,
    settlementHoursMin: pickWeighted([[140, 2], [150, 5], [160, 3]]),
    settlementHoursMax: pickWeighted([[180, 5], [190, 4], [200, 1]]),
    fractionRule: '1日8時間・端数15分単位',
    autoRenew: status === '稼動中' && rnd() < 0.4 ? 1 : 0,
    status,
    remarks: null,
    orderLineId: orderLine?.id ?? null,
    acceptanceRequired: 1,
    acceptanceExemptionReason: null,
    directCommandFlag: contractType === '派遣' && rnd() < 0.5 ? 1 : 0,
    commissionBaseType: rnd() < 0.3 ? pick(['粗利', '売上']) : null,
    commissionRate: rnd() < 0.3 ? Math.round((3 + rnd() * 7) * 100) / 100 : null,
    createdBy: 1,
    renewedFromContractId: null,
    quotationId: proposal ? quotations[int(0, quotations.length - 1)].id : null,
    renewalDecision: null,
    version: 0
  });
  if (rnd() < 0.3) {
    contractPriceHistory.push({
      id: nextPriceHistoryId++,
      contractId: id,
      applyFromMonth: `2026-${pad(int(4, 7), 2)}`,
      sellingPrice: Math.round(selling * 0.95),
      costPrice: Math.round(cost * 0.97),
      reason: '期首単価改定',
      createdBy: 1
    });
  }
  return { contractId: id, engineer, project, selling, cost, status };
};

// active contracts
for (const eng of engineers.filter((e) => e.status === '稼動中' || e.status === '退場予定')) {
  const startYear = int(2024, 2026);
  const start = D(startYear, int(1, 12), int(1, 28));
  const endYear = Math.max(startYear + 1, int(2026, 2027));
  const end = D(endYear, int(1, 12), 28);
  makeContract(eng, '稼動中', start, end, null, null, false);
}
// ended/cancelled for bench & proposing
let benchIdx = 0;
for (const eng of engineers.filter((e) => e.status === 'Bench' || e.status === '提案中')) {
  if (benchIdx >= 45) break;
  const status = benchIdx % 7 === 0 ? '解約' : '終了';
  makeContract(eng, status, D(2024, int(1, 6), 1), D(2026, int(1, 5), 28), null, null, false);
  benchIdx++;
}
// renewal chain sample
const activeContracts = contracts.filter((c) => c.status === '稼動中');
for (let i = 0; i < 20; i++) {
  const base = activeContracts[i * 7 % activeContracts.length];
  const eng = engineers.find((e) => e.id === base.engineerId);
  if (!eng) continue;
  const renewalId = nextContractId++;
  contracts.push({
    ...base,
    id: renewalId,
    contractNo: `CT-2026-${pad(contractNoSeq++, 4)}`,
    startDate: D(2027, 1, 1),
    endDate: D(2027, 12, 28),
    renewedFromContractId: base.id,
    autoRenew: 1
  });
}

// 成約済み提案を対応する稼働中/準備中契約へ紐付け（契約→提案の参照整合）
const contractedProposals = proposals.filter((p) => p.status === '成約');
for (const p of contractedProposals) {
  const candidates = contracts.filter(
    (c) => c.engineerId === p.engineerId && !c.proposalId && (c.status === '稼動中' || c.status === '準備中')
  );
  const match = candidates.find((c) => c.projectId === p.projectId) || candidates[0];
  if (match) {
    match.proposalId = p.id;
  } else {
    // 成約提案に対応する契約が無い場合は準備中契約を補完する
    const eng = engineers.find((e) => e.id === p.engineerId);
    const proj = projects.find((pr) => pr.id === p.projectId);
    makeContract(eng, '準備中', D(2026, 9, 1), D(2027, 8, 28), p, null, false);
    const created = contracts[contracts.length - 1];
    created.projectId = proj.id;
    created.customerId = proj.customerId;
  }
}

// ---------- section 12: work records ----------
const workRecords = [];
const workRecordDaily = [];
const acceptances = [];
let nextWorkRecordId = 8001;
let nextDailyId = 9001;
let nextAcceptanceId = 27001;
const workMonths = [
  { month: '2026-06', year: 2026, mon: 6, days: 22 },
  { month: '2026-07', year: 2026, mon: 7, days: 23 },
  { month: '2026-08', year: 2026, mon: 8, days: 6 }
];
const activeWorkContracts = contracts.filter((c) => c.status === '稼動中').slice(0, 185);
activeWorkContracts.forEach((contract, ci) => {
  const eng = engineers.find((e) => e.id === contract.engineerId);
  workMonths.forEach((wm, mi) => {
    const isCurrent = mi === 2;
    const hours = isCurrent ? int(20, 48) : int(140, 190);
    const hourly = contract.selling / 160;
    const billing = roundTo(hourly * hours, 1000);
    const payment = roundTo((contract.cost / 160) * hours, 1000);
    const status = isCurrent ? (ci % 3 === 0 ? '提出済' : '入力中') : '確定';
    const wrId = nextWorkRecordId++;
    workRecords.push({
      id: wrId,
      contractId: contract.id,
      workMonth: wm.month,
      actualHours: hours,
      billingAmount: billing,
      paymentAmount: payment,
      status,
      remarks: null,
      createdBy: 1,
      rejectComment: null,
      organizationId: eng?.organizationId ?? 3005,
      costCenterId: eng?.costCenterId ?? 4001,
      accountingDimensionFrozen: 0
    });
    // daily rows
    const dayCount = Math.min(wm.days, Math.max(15, Math.round(hours / 8)));
    let remaining = hours;
    for (let d = 1; d <= dayCount; d++) {
      const workHours = d === dayCount ? remaining : Math.min(8, remaining);
      if (workHours <= 0) break;
      remaining -= workHours;
      workRecordDaily.push({
        id: nextDailyId++,
        workRecordId: wrId,
        workDate: D(wm.year, wm.mon, d),
        startTime: '09:00:00',
        endTime: workHours > 8 ? '18:30:00' : '18:00:00',
        breakMinutes: 60,
        workedHours: Math.round(workHours * 100) / 100,
        remarks: d % 9 === 0 ? 'リモート勤務' : null
      });
    }
    // acceptance for confirmed months
    if (status === '確定') {
      acceptances.push({
        id: nextAcceptanceId++,
        contractId: contract.id,
        workRecordId: wrId,
        workMonth: wm.month,
        status: mi === 0 ? '検収済' : '検収済',
        submittedAt: DT(wm.year, wm.mon, 25, 10, 0),
        customerContactId: null,
        customerContactNameSnapshot: '検収担当',
        acceptedAt: DT(wm.year, wm.mon + 1, int(1, 10), 10, 0),
        rejectComment: null,
        documentId: null,
        hoursSnapshot: hours,
        amountSnapshot: billing,
        workRecordUpdatedAt: DT(wm.year, wm.mon + 1, 1, 9, 0),
        version: 0,
        createdBy: 1
      });
    }
  });
});

// ---------- section 13: invoices ----------
const invoices = [];
const invoiceItems = [];
const invoicePayments = [];
let nextInvoiceId = 10001;
let nextInvoiceItemId = 10401;
let nextInvoicePaymentId = 10801;
const invoiceGroups = new Map();
for (const wr of workRecords.filter((r) => r.status === '確定')) {
  const contract = contracts.find((c) => c.id === wr.contractId);
  const key = `${contract.customerId}|${wr.workMonth}`;
  if (!invoiceGroups.has(key)) invoiceGroups.set(key, []);
  invoiceGroups.get(key).push({ wr, contract });
}
let invoiceSeq = 1;
for (const [key, rows] of invoiceGroups) {
  const [customerId, month] = key.split('|');
  const subtotal = rows.reduce((s, r) => s + r.wr.billingAmount, 0);
  const tax = Math.round(subtotal * 0.1);
  const total = subtotal + tax;
  const isJune = month === '2026-06';
  const isJuly = month === '2026-07';
  const status = isJune ? pickWeighted([['入金済', 70], ['送付済', 30]]) : isJuly ? pickWeighted([['送付済', 60], ['一部入金', 15], ['未送付', 25]]) : '未送付';
  const invoiceId = nextInvoiceId++;
  const dueMonth = isJune ? 7 : 8;
  invoices.push({
    id: invoiceId,
    invoiceNo: `INV-${month}-${pad(invoiceSeq++, 4)}`,
    customerId: Number(customerId),
    billingMonth: month,
    subtotal,
    tax,
    total,
    status,
    issuedDate: D(Number(month.slice(0, 4)), Number(month.slice(5, 7)), 28),
    paidDate: status === '入金済' ? D(2026, dueMonth, int(20, 30)) : null,
    remarks: null,
    createdBy: 1,
    dueDate: D(2026, dueMonth, 30),
    taxRate: 0.1,
    costCenterId: 4001,
    version: 0
  });
  rows.forEach(({ wr, contract }) => {
    const eng = engineers.find((e) => e.id === contract.engineerId);
    invoiceItems.push({
      id: nextInvoiceItemId++,
      invoiceId,
      workRecordId: wr.id,
      description: `${eng?.fullName ?? '要員'} ${month} 稼働分`,
      amount: wr.billingAmount
    });
  });
  if (status === '入金済') {
    invoicePayments.push({
      id: nextInvoicePaymentId++,
      invoiceId,
      paidDate: D(2026, dueMonth, int(20, 30)),
      amount: total,
      fee: 0,
      remarks: null,
      createdBy: 1
    });
  } else if (status === '一部入金') {
    invoicePayments.push({
      id: nextInvoicePaymentId++,
      invoiceId,
      paidDate: D(2026, 8, int(1, 10)),
      amount: Math.round(total * 0.8),
      fee: 0,
      remarks: '一部入金',
      createdBy: 1
    });
  }
}

// ---------- section 14: bp payments ----------
const bpPayments = [];
let nextBpPaymentId = 12001;
for (const wr of workRecords) {
  const contract = contracts.find((c) => c.id === wr.contractId);
  const eng = engineers.find((e) => e.id === contract.engineerId);
  if (eng?.employment !== 'BP') continue;
  const bpId = 11001 + ((eng.id - 1001) % 20);
  const isCurrent = wr.workMonth === '2026-08';
  bpPayments.push({
    id: nextBpPaymentId++,
    workRecordId: wr.id,
    layerOrder: 1,
    payeeCompanyName: BP_COMPANIES[(eng.id - 1001) % 20][0],
    bpCompanyId: bpId,
    bpCompanyNameSnapshot: BP_COMPANIES[(eng.id - 1001) % 20][0],
    termsSnapshotJson: '{}',
    parentPaymentId: null,
    amount: wr.paymentAmount,
    status: isCurrent ? '未払' : '支払済',
    paidDate: isCurrent ? null : D(2026, wr.workMonth === '2026-06' ? 7 : 8, int(20, 30)),
    remarks: null,
    deletedFlag: 0,
    costCenterId: eng.costCenterId,
    version: 0
  });
  if ((eng.id - 1001) % 3 === 0 && !isCurrent) {
    bpPayments.push({
      id: nextBpPaymentId++,
      workRecordId: wr.id,
      layerOrder: 2,
      payeeCompanyName: '下請け技術者',
      bpCompanyId: null,
      bpCompanyNameSnapshot: '個人事業主',
      termsSnapshotJson: '{}',
      parentPaymentId: bpPayments[bpPayments.length - 1].id,
      amount: Math.round(wr.paymentAmount * 0.6),
      status: '支払済',
      paidDate: D(2026, wr.workMonth === '2026-06' ? 7 : 8, int(20, 30)),
      remarks: null,
      deletedFlag: 0,
      costCenterId: eng.costCenterId,
      version: 0
    });
  }
}

// ---------- section 15: candidates ----------
const candidates = [];
const candidateActivities = [];
let nextCandidateId = 13001;
let nextCandidateActivityId = 13201;
const candidateStages = (() => {
  const arr = [];
  const counts = { '応募受付': 5, '書類選考': 8, '一次面談': 8, '最終面談': 6, '内定': 5, '内定辞退': 3, '入社': 5, '不採用': 5 };
  for (const [st, n] of Object.entries(counts)) {
    for (let i = 0; i < n; i++) arr.push(st);
  }
  return arr;
})();
for (let i = 0; i < 45; i++) {
  const name = personName();
  const stage = candidateStages[i];
  const id = nextCandidateId++;
  candidates.push({
    id,
    name: name.fullName,
    contactEmail: `candidate${i + 1}@example.jp`,
    contactPhone: `0${int(3, 9)}0-${pad(int(100, 999), 3)}-${pad(int(1000, 9999), 4)}`,
    skillSummary: `${pick(['Java', 'Python', 'TypeScript', 'C#'])}・${pick(['AWS', 'Docker', 'React'])}を中心とした開発経験${int(1, 15)}年。`,
    desiredRate: roundTo(int(350000, 750000), 10000),
    source: pick(['紹介', 'エージェント', '自社応募', 'リファラル']),
    currentStage: stage,
    nextActionDate: stage === '応募受付' || stage === '書類選考' || stage === '一次面談' ? D(2026, int(8, 9), int(1, 28)) : null,
    convertedEngineerId: stage === '入社' ? 1001 + (i % 10) : null,
    remarks: stage === '不採用' ? 'スキル要件不一致のため。' : stage === '内定辞退' ? '他社に決定したため。' : null,
    deletedFlag: 0,
    createdBy: hrUsers[i % hrUsers.length].id
  });
  const seq = ['応募受付'];
  if (['書類選考', '一次面談', '最終面談', '内定', '内定辞退', '入社', '不採用'].includes(stage)) seq.push('書類選考');
  if (['一次面談', '最終面談', '内定', '内定辞退', '入社', '不採用'].includes(stage)) seq.push('一次面談');
  if (['最終面談', '内定', '内定辞退', '入社'].includes(stage)) seq.push('最終面談');
  if (['内定', '内定辞退', '入社'].includes(stage)) seq.push('内定');
  if (['内定辞退', '入社'].includes(stage) || stage === '不採用') seq.push(stage);
  seq.forEach((st, idx) => {
    candidateActivities.push({
      id: nextCandidateActivityId++,
      candidateId: id,
      stage: st,
      reason: st === '不採用' ? 'スキル要件不一致' : st === '内定辞退' ? '他社内定' : null,
      changedBy: hrUsers[idx % hrUsers.length].id,
      changedAt: DT(2026, 5 + idx, int(1, 28), int(9, 18), int(0, 59)),
      remarks: null
    });
  });
}

// ---------- section 16: tasks / notifications / audit ----------
const tasks = [];
let nextTaskId = 16001;
for (let i = 0; i < 100; i++) {
  const assignee = pick(users.filter((u) => u.role !== '要員' || i % 5 === 0));
  const requester = pick(users);
  tasks.push({
    id: nextTaskId++,
    tenantId: 'default',
    title: `${pick(TASK_TITLES)}（${int(1, 99)}）`,
    description: pick(BUSINESS_CONTENT),
    assigneeUserId: assignee.id,
    requesterUserId: requester.id,
    dueDate: D(2026, int(7, 9), int(1, 28)),
    priority: pickWeighted([['LOW', 2], ['MEDIUM', 5], ['HIGH', 3]]),
    status: pickWeighted([['NOT_STARTED', 3], ['IN_PROGRESS', 4], ['COMPLETED', 3], ['CANCELLED', 1]]),
    targetType: pick([null, 'engineer', 'customer', 'contract', 'proposal']),
    targetId: null,
    completedAt: null,
    version: 1
  });
}

const notifications = [];
const notificationReads = [];
let nextNotificationId = 17001;
let nextNotificationReadId = 17201;
const notifTypes = ['CONTRACT_END', 'PROPOSAL_STALE', 'BENCH_LONG', 'PROJECT_URGENT', 'FOLLOW_UP', 'FOLLOWUP_OVERDUE', 'CASHFLOW_ALERT'];
for (let i = 0; i < 60; i++) {
  const type = notifTypes[i % notifTypes.length];
  const recipient = salesUsers[i % salesUsers.length];
  const id = nextNotificationId++;
  notifications.push({
    id,
    type,
    title: type === 'CONTRACT_END' ? '契約終了予告' : type === 'PROPOSAL_STALE' ? '提案停滞' : type === 'BENCH_LONG' ? '長期Bench' : type === 'PROJECT_URGENT' ? '急募案件' : type === 'FOLLOWUP_OVERDUE' ? 'フォロー期日超過' : type === 'CASHFLOW_ALERT' ? '資金ショート警告' : 'フォロー予定',
    message: `担当先の対応が必要です（サンプル${i + 1}）`,
    linkUrl: '/dashboard',
    dedupeKey: `${type}:${id}:2026-08-09`,
    menuKey: type === 'CASHFLOW_ALERT' ? 'dashboard' : null,
    recipientUserId: recipient.id,
    organizationId: 3002
  });
  if (rnd() < 0.7) {
    notificationReads.push({
      id: nextNotificationReadId++,
      notificationId: id,
      userId: recipient.id,
      readAt: DT(2026, 8, int(1, 9), int(9, 18), int(0, 59))
    });
  }
}

const auditLogs = [];
for (let i = 0; i < 200; i++) {
  const user = pick(users);
  const uri = pick(['/api/engineers', '/api/contracts', '/api/proposals', '/api/customers', '/api/invoices', '/api/tasks']);
  auditLogs.push({
    id: 18001 + i,
    username: user.username,
    method: pick(['GET', 'POST', 'PUT', 'DELETE']),
    uri,
    status: int(200, 500) < 460 ? 200 : pick([400, 403, 404, 500]),
    created_at: DT(2026, int(7, 8), int(1, 28), int(9, 18), int(0, 59)),
    applicationCode: null,
    successFlag: 1
  });
}

// ---------- section 17: followups / sales activities ----------
const engineerFollowups = [];
let nextFollowupId = 19001;
const benchEngineers = engineers.filter((e) => e.status === 'Bench' || e.status === '提案中');
benchEngineers.forEach((eng, i) => {
  if (i >= 120) return;
  const followupType = pick(['1on1', '面談', '連絡']);
  engineerFollowups.push({
    id: nextFollowupId++,
    engineerId: eng.id,
    followupType,
    followupDate: D(2026, int(6, 8), int(1, 28)),
    satisfaction: int(2, 5),
    topic: pick(['案件選定の希望', 'スキルアップ希望', '勤務条件の確認', 'キャリア相談']),
    content: '状況確認を実施し、希望案件をヒアリング。',
    nextDate: D(2026, int(8, 9), int(1, 28)),
    createdBy: salesUsers[i % salesUsers.length].id,
    deletedFlag: 0
  });
});

const salesActivities = [];
let nextSalesActivityId = 19501;
for (let i = 0; i < 150; i++) {
  const customer = customers[i % customers.length];
  const contact = customerContacts.find((c) => c.customerId === customer.id) ?? customerContacts[0];
  const opp = opportunities[i % opportunities.length];
  const sales = salesUsers[i % salesUsers.length];
  salesActivities.push({
    id: nextSalesActivityId++,
    customerId: customer.id,
    contactId: contact?.id ?? null,
    opportunityId: rnd() < 0.6 ? opp.id : null,
    activityType: pick(['商談', '訪問', '電話', 'メール', 'その他']),
    activityDate: D(2026, int(6, 8), int(1, 28)),
    title: `${customer.companyName}への${pick(['ヒアリング', '提案', 'フォロー', '課題確認'])}`,
    content: pick(BUSINESS_CONTENT),
    nextActionDate: D(2026, int(8, 9), int(1, 28)),
    completedFlag: rnd() < 0.6 ? 1 : 0,
    version: 1,
    assigneeUserId: sales.id,
    createdBy: sales.id
  });
}

// ---------- section 18: attendance / calendar / leave / overtime ----------
const workCalendars = [[23001, '標準カレンダー', '2026-01-01', '2026-12-31', '有効']];
const workCalendarDays = [];
let nextCalendarDayId = 23010;
const calendarDates = [];
for (let m = 1; m <= 12; m++) {
  const dim = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][m - 1];
  for (let d = 1; d <= dim; d++) {
    const date = new Date(2026, m - 1, d);
    const dow = date.getDay();
    const isHoliday = dow === 0 || dow === 6;
    workCalendarDays.push({
      id: nextCalendarDayId++,
      calendarId: 23001,
      calendarDate: D(2026, m, d),
      dayType: isHoliday ? '休日' : '平日',
      scheduledMinutes: isHoliday ? 0 : 480
    });
  }
}

const employeeAttendance = [];
const attendanceMonths = [];
const leaveRequests = [];
let nextAttendanceId = 20001;
let nextAttendanceMonthId = 21001;
let nextLeaveRequestId = 22001;
const attendanceEngineers = engineers.filter((e) => e.employment !== 'BP' && (e.status === '稼動中' || e.status === '退場予定'));
attendanceEngineers.forEach((eng, ei) => {
  // July full month
  const julyHours = int(150, 210);
  let remaining = julyHours;
  const julyDays = [];
  for (let d = 1; d <= 31; d++) {
    const date = new Date(2026, 6, d);
    const dow = date.getDay();
    if (dow === 0 || dow === 6) continue;
    if (remaining <= 0) break;
    const h = Math.min(8, remaining);
    remaining -= h;
    julyDays.push({ d, h: Math.round(h * 100) / 100 });
  }
  for (const day of julyDays) {
    const overtime = rnd() < 0.12 ? int(15, 60) : 0;
    employeeAttendance.push({
      id: nextAttendanceId++,
      engineerId: eng.id,
      legalEntityId: null,
      organizationId: eng.organizationId,
      workCalendarId: 23001,
      workDate: D(2026, 7, day.d),
      clockIn: '09:00:00',
      clockOut: overtime ? '19:00:00' : '18:00:00',
      breakMinutes: 60,
      regularMinutes: 480,
      overtimeMinutes: overtime,
      holidayMinutes: 0,
      lateNightMinutes: 0,
      workType: '通常',
      workplaceType: pick(['客先常駐', '自社', 'リモート']),
      source: 'manual',
      sourceExternalId: null,
      status: '入力中',
      remarks: null,
      version: 0,
      deletedFlag: 0
    });
  }
  // August up to 09
  for (let d = 1; d <= 9; d++) {
    const date = new Date(2026, 7, d);
    const dow = date.getDay();
    if (dow === 0 || dow === 6) continue;
    employeeAttendance.push({
      id: nextAttendanceId++,
      engineerId: eng.id,
      legalEntityId: null,
      organizationId: eng.organizationId,
      workCalendarId: 23001,
      workDate: D(2026, 8, d),
      clockIn: '09:00:00',
      clockOut: '18:00:00',
      breakMinutes: 60,
      regularMinutes: 480,
      overtimeMinutes: 0,
      holidayMinutes: 0,
      lateNightMinutes: 0,
      workType: '通常',
      workplaceType: pick(['客先常駐', '自社', 'リモート']),
      source: 'manual',
      sourceExternalId: null,
      status: '入力中',
      remarks: null,
      version: 0,
      deletedFlag: 0
    });
  }
  attendanceMonths.push({
    id: nextAttendanceMonthId++,
    engineerId: eng.id,
    legalEntityId: null,
    organizationId: eng.organizationId,
    workMonth: D(2026, 7, 1),
    scheduledMinutes: 21 * 480,
    workedMinutes: julyHours * 60,
    regularMinutes: julyHours * 60,
    overtimeMinutes: int(0, 300),
    holidayMinutes: 0,
    lateNightMinutes: 0,
    leaveMinutes: 0,
    status: '承認済',
    submittedAt: DT(2026, 8, 1, 9, 0),
    submittedBy: eng.id,
    approvedAt: DT(2026, 8, 2, 10, 0),
    approvedBy: managerUsers[0].id,
    closedAt: DT(2026, 8, 3, 11, 0),
    closedBy: managerUsers[0].id,
    closeReason: null,
    version: 0,
    deletedFlag: 0
  });
  attendanceMonths.push({
    id: nextAttendanceMonthId++,
    engineerId: eng.id,
    legalEntityId: null,
    organizationId: eng.organizationId,
    workMonth: D(2026, 8, 1),
    scheduledMinutes: 21 * 480,
    workedMinutes: int(30, 48) * 60,
    regularMinutes: int(30, 48) * 60,
    overtimeMinutes: 0,
    holidayMinutes: 0,
    lateNightMinutes: 0,
    leaveMinutes: 0,
    status: ei % 4 === 0 ? '提出済' : '入力中',
    submittedAt: ei % 4 === 0 ? DT(2026, 8, 9, 12, 0) : null,
    submittedBy: ei % 4 === 0 ? eng.id : null,
    approvedAt: null,
    approvedBy: null,
    closedAt: null,
    closedBy: null,
    closeReason: null,
    version: 0,
    deletedFlag: 0
  });
});
// leave requests
for (let i = 0; i < 80; i++) {
  const eng = engineers[int(0, engineers.length - 1)];
  const startDate = D(2026, int(8, 9), int(1, 28));
  const endDate = rnd() < 0.15 ? D(2026, int(8, 9), int(1, 28)) : startDate;
  leaveRequests.push({
    id: nextLeaveRequestId++,
    engineerId: eng.id,
    legalEntityId: null,
    organizationId: eng.organizationId,
    leaveType: pick(['有給', '半休', '時間休', '代休', '欠勤', '特別休暇']),
    startDate,
    endDate: endDate >= startDate ? endDate : startDate,
    startTime: null,
    endTime: null,
    requestedMinutes: pick([480, 240, 120, 480, 480, 480]),
    reason: pick(['体調不良', '私用', '通院', '家族の用事']),
    status: pick(['承認済', '申請中', '却下', '取消']),
    approvalRequestId: null,
    version: 0,
    createdBy: eng.id,
    deletedFlag: 0
  });
}
const overtimeAgreements = [[24001, 1, '2026-01-01', '2026-12-31', 0, 2700, 21600, 43200, 6000, 4800, 6, 80, 'self,manager,hr', '{}', 0, 0]];
const overtimeFollowups = [];
let nextOvertimeFollowupId = 24501;
for (let i = 0; i < 30; i++) {
  const eng = attendanceEngineers[i % attendanceEngineers.length];
  overtimeFollowups.push({
    id: nextOvertimeFollowupId++,
    engineerId: eng.id,
    periodMonth: D(2026, 7, 1),
    warningCode: pick(['RULE1_MONTH_NORMAL', 'RULE2_YEAR_NORMAL', 'RULE4_MONTH_TOTAL']),
    status: pick(['未対応', '通知済', '対応中', '完了']),
    notifiedAt: rnd() < 0.5 ? DT(2026, 8, int(1, 5), 9, 0) : null,
    healthActionStatus: rnd() < 0.4 ? '面談実施' : null,
    version: 0,
    deletedFlag: 0
  });
}

// ---------- section 19: compliance / workplaces / findings ----------
const complianceProfiles = [];
const complianceFindings = [];
let nextProfileId = 25001;
let nextFindingId = 25501;
const dispatchContracts = contracts.filter((c) => c.contractType === '派遣').slice(0, 35);
dispatchContracts.forEach((contract, i) => {
  const eng = engineers.find((e) => e.id === contract.engineerId);
  const workplace = workplaces[contract.customerId - 2001];
  complianceProfiles.push({
    id: nextProfileId++,
    tenantId: 'default',
    contractId: contract.id,
    contractTypeDetail: '派遣',
    workplaceId: workplace?.id ?? null,
    workDescription: pick(BUSINESS_CONTENT),
    workLocation: pick(STATIONS)[0],
    workTime: '9:00-18:00',
    breakTime: '60分',
    holidayRule: '土日祝',
    overtimeRule: '36協定の範囲内',
    commandPersonContactId: null,
    commandPersonNameSnapshot: '派遣先現場責任者',
    commandPersonTitleSnapshot: '部長',
    clientResponsibleContactId: null,
    clientResponsiblePerson: '派遣先責任者 様',
    clientResponsiblePhone: null,
    dispatchResponsibleUserId: hrUsers[i % hrUsers.length].id,
    dispatchResponsibleNameSnapshot: hrUsers[i % hrUsers.length].realName,
    dispatchResponsibleTitleSnapshot: '派遣元責任者',
    dispatchResponsiblePhoneSnapshot: '03-1234-5678',
    dispatchPeriodStart: contract.startDate,
    dispatchPeriodEnd: contract.endDate,
    limitationDate: D(2026, 12, 31),
    workplaceLimitationDate: D(2026, 12, 31),
    workerLimitationDate: D(2026, 12, 31),
    treatmentScheme: '同種業務従事者と同等',
    complaintContact: '派遣元相談窓口',
    complaintProcessingHistory: null,
    trainingInfo: '入場時研修実施済',
    safetyHealthInfo: '安全衛生管理計画遵守',
    insuranceNotification: '社会保険加入済み',
    welfareInfo: null,
    instructionRoute: '派遣先責任者を経由',
    responsibilityDegree: '指揮命令は派遣先',
    subcontractAllowed: 0,
    acceptanceMethod: '月次検収書',
    dispatchWorkerCount: 1,
    agreementTargetFlag: 1,
    indefiniteTermFlag: 0,
    ageOver60Flag: 0,
    employmentStabilityMeasure: null,
    healthInsuranceStatus: '加入済',
    healthInsuranceMissingReason: null,
    healthInsuranceExpectedDate: null,
    pensionInsuranceStatus: '加入済',
    pensionInsuranceMissingReason: null,
    pensionInsuranceExpectedDate: null,
    employmentInsuranceStatus: '加入済',
    employmentInsuranceMissingReason: null,
    employmentInsuranceExpectedDate: null,
    snapshotJson: '{}',
    workplaceSnapshotJson: '{}',
    workerSnapshotJson: '{}',
    snapshotAt: DT(2026, 8, 1, 10, 0),
    version: 0,
    deletedFlag: 0
  });
  if (i % 3 !== 0) {
    const code = pick(['TIER_EXCEEDED', 'DIRECT_COMMAND', 'DOUBLE_DISPATCH', 'SETTLEMENT_MISMATCH']);
    complianceFindings.push({
      id: nextFindingId++,
      tenantId: 'default',
      contractId: contract.id,
      code,
      severity: code === 'SETTLEMENT_MISMATCH' ? 'WARNING' : 'WARNING',
      status: pick(['OPEN', 'ACKNOWLEDGED', 'IN_PROGRESS', 'RESOLVED', 'EXCEPTION_APPROVED']),
      conditionFingerprint: `s300-${contract.id}-${code}`,
      detectedAt: DT(2026, int(6, 8), int(1, 28), 9, 0),
      dueDate: D(2026, int(8, 10), 30),
      acknowledgedBy: managerUsers[0].id,
      acknowledgedAt: DT(2026, 8, int(1, 5), 9, 0),
      resolutionNote: status2note(code),
      evidenceDocumentId: null,
      version: 0,
      deletedFlag: 0
    });
  }
});
function status2note(code) {
  return code === 'TIER_EXCEEDED' ? '多重下請け階層超過。是正計画中。' : code === 'DIRECT_COMMAND' ? '指揮命令の実態確認を実施中。' : code === 'DOUBLE_DISPATCH' ? '二重派遣の疑い。派遣元へ確認中。' : '精算実態の整合確認を実施。';
}

// ---------- section 20: accounting / budgets ----------
const monthlyDimensions = [];
let nextDimensionId = 28501;
for (const wr of workRecords) {
  const contract = contracts.find((c) => c.id === wr.contractId);
  monthlyDimensions.push({
    id: nextDimensionId++,
    workMonth: D(Number(wr.workMonth.slice(0, 4)), Number(wr.workMonth.slice(5, 7)), 1),
    sourceType: 'work-record',
    sourceId: wr.id,
    organizationId: contract.organizationId ?? 3005,
    costCenterId: contract.costCenterId ?? 4001,
    salesUserId: contract.salesUserId,
    revenue: wr.billingAmount,
    cost: wr.paymentAmount,
    snapshotAt: DT(2026, 8, 9, 12, 0)
  });
}
for (const eng of engineers.filter((e) => e.status === 'Bench')) {
  monthlyDimensions.push({
    id: nextDimensionId++,
    workMonth: D(2026, 8, 1),
    sourceType: 'bench-engineer',
    sourceId: eng.id,
    organizationId: eng.organizationId,
    costCenterId: eng.costCenterId,
    salesUserId: null,
    revenue: 0,
    cost: Math.round(eng.unitPrice * 0.7),
    snapshotAt: DT(2026, 8, 9, 12, 0)
  });
}
const managementBudgets = [];
let nextBudgetId = 29001;
const budgetOrgs = [3002, 3003, 3005, 3006, 3007, 3008];
for (const orgId of budgetOrgs) {
  for (let m = 6; m <= 8; m++) {
    managementBudgets.push({
      id: nextBudgetId++,
      organizationId: orgId,
      costCenterId: costCenters.find((c) => c[3] === orgId)?.[0] ?? 4008,
      budgetMonth: D(2026, m, 1),
      revenue: roundTo(int(15000000, 60000000), 1000000),
      grossProfit: roundTo(int(2500000, 12000000), 1000000),
      utilizationCount: int(20, 80),
      hireCount: int(0, 3),
      version: 0,
      deletedFlag: 0
    });
  }
}
const orgRelationHistory = [];
orgs.forEach((o) => {
  orgRelationHistory.push({
    id: 29501 + (o[0] - 3001),
    organizationId: o[0],
    parentId: o[4],
    status: '有効',
    validFrom: D(2020, 4, 1),
    validTo: null,
    deletedFlag: 0
  });
});

// ---------- section 21: bp company master ----------
const bpCompanies = [];
const bpContacts = [];
const bpBankAccounts = [];
const bpTerms = [];
const bpEvaluations = [];
const bpNegotiations = [];
let nextBpContactId = 11201;
let nextBpBankId = 11401;
let nextBpTermsId = 11601;
let nextBpEvaluationId = 11901;
let nextBpNegotiationId = 11951;
BP_COMPANIES.forEach((c, i) => {
  const id = 11001 + i;
  const sales = salesUsers[i % salesUsers.length];
  bpCompanies.push({
    id,
    tenantId: 1,
    legalName: c[0],
    nameKana: c[1],
    normalizedName: c[0],
    entityType: c[2],
    corporateNumber: String(1000000000000 + i),
    invoiceRegistrationNumber: `T${1000000000000 + i}`,
    capitalBand: pick(['1000万円未満', '1000万円以上5000万円未満', '5000万円以上']),
    employeeBand: pick(['10名未満', '10-50名', '50-100名']),
    address: `東京都千代田区${i + 1}丁目${i + 2}-${i + 3}`,
    representative: personName().fullName,
    status: i % 7 === 0 ? 'SUSPENDED' : 'ACTIVE',
    suspensionReason: i % 7 === 0 ? '契約更新手続き中' : null,
    suspensionStartDate: i % 7 === 0 ? D(2026, 7, 1) : null,
    suspensionEndDate: i % 7 === 0 ? D(2026, 9, 30) : null,
    suspensionApprovedBy: null,
    rating: int(2, 5),
    primarySalesUserId: sales.id,
    complianceApplicability: '適用',
    applicabilityCheckedBy: hrUsers[0].id,
    applicabilityCheckedAt: DT(2026, 7, 1, 10, 0),
    applicabilityNote: '派遣・準委任の取引実績あり',
    version: 1,
    createdBy: adminUsers[0]?.id ?? 1,
    deletedFlag: 0
  });
  const contactName = personName();
  bpContacts.push({
    id: nextBpContactId++,
    tenantId: 1,
    bpCompanyId: id,
    name: contactName.fullName,
    department: '営業部',
    role: '営業担当',
    email: `bp${i + 1}@example.jp`,
    phone: `0${int(3, 9)}0-${pad(int(100, 999), 3)}-${pad(int(1000, 9999), 4)}`,
    primaryFlag: 1,
    deletedFlag: 0
  });
  bpBankAccounts.push({
    id: nextBpBankId++,
    tenantId: 1,
    bpCompanyId: id,
    bankName: pick(['みずほ銀行', '三菱UFJ銀行', '三井住友銀行', 'りそな銀行']),
    branchName: `${pick(['東京', '丸の内', '新宿', '渋谷'])}支店`,
    accountType: 'ORDINARY',
    encryptedAccountNumber: 'enc:seed300',
    accountHolder: c[0],
    maskedLabel: `****${pad(i + 1, 4)}`,
    validFrom: D(2024, 4, 1),
    validTo: null,
    approvalStatus: 'APPROVED',
    approvedBy: adminUsers[0]?.id ?? 1,
    approvedAt: DT(2024, 4, 10, 10, 0),
    deletedFlag: 0
  });
  bpTerms.push({
    id: nextBpTermsId++,
    tenantId: 1,
    bpCompanyId: id,
    effectiveFrom: D(2024, 4, 1),
    effectiveTo: null,
    closingDay: pick([15, 20, 25, 31]),
    paymentMonthOffset: 1,
    paymentDay: pick([25, 30]),
    feeBearer: pick(['PAYEE', 'PAYER']),
    paymentMethod: 'BANK_TRANSFER',
    feeBearerExceptionReason: null,
    feeBearerApprovedBy: adminUsers[0]?.id ?? 1,
    feeBearerApprovedAt: DT(2024, 4, 10, 10, 0),
    maxPaymentDays: 60,
    version: 1,
    deletedFlag: 0
  });
  bpEvaluations.push({
    id: nextBpEvaluationId++,
    tenantId: 1,
    bpCompanyId: id,
    period: '2026-Q2',
    qualityScore: int(3, 5),
    responseScore: int(3, 5),
    retentionScore: int(2, 5),
    complianceScore: int(3, 5),
    billingAccuracyScore: int(3, 5),
    comment: '継続的に安定した提供実績。',
    evaluatedBy: sales.id,
    deletedFlag: 0
  });
  if (i % 4 === 0) {
    bpNegotiations.push({
      id: nextBpNegotiationId++,
      tenantId: 1,
      bpCompanyId: id,
      requestedAt: D(2026, int(5, 7), int(1, 28)),
      respondedAt: rnd() < 0.5 ? D(2026, int(6, 8), int(1, 28)) : null,
      status: rnd() < 0.5 ? 'AGREED' : 'REQUESTED',
      requestedAmount: roundTo(int(6000000, 12000000), 1000000),
      agreedAmount: rnd() < 0.5 ? roundTo(int(5800000, 11500000), 1000000) : null,
      summary: '年間契約単価の見直し',
      documentId: null,
      deletedFlag: 0
    });
  }
});

const bpAvailability = [];
const bpAvailabilityIngestion = [];
let nextAvailabilityId = 30001;
let nextIngestionId = 30201;
for (let i = 0; i < 40; i++) {
  const bp = BP_COMPANIES[i % BP_COMPANIES.length];
  const name = personName();
  bpAvailability.push({
    id: nextAvailabilityId++,
    initialName: `${name.kana.split(' ')[1]} ${name.kana.split(' ')[0]}`,
    bpCompany: bp[0],
    bpCompanyId: 11001 + (i % 20),
    skillsJson: JSON.stringify([pick(['Java', 'Python', 'TypeScript', 'C#', 'Go']), pick(['AWS', 'Docker', 'React', 'Spring Boot'])]),
    unitPrice: roundTo(int(400000, 850000), 10000),
    availableFrom: D(2026, int(8, 10), 1),
    experienceYears: int(3, 20),
    status: i % 12 === 0 ? '要員転換' : i % 5 === 0 ? '失効' : '提案可能',
    promotedEngineerId: i % 12 === 0 ? 1001 + (i % 10) : null,
    remarks: null,
    createdBy: salesUsers[i % salesUsers.length].id
  });
  if (i < 8) {
    bpAvailabilityIngestion.push({
      id: nextIngestionId++,
      originalFileName: `bp_availability_${i + 1}.pdf`,
      storedFileName: `seed-${i + 1}.pdf`,
      fileExt: 'pdf',
      status: '確定済',
      extractedText: null,
      parsedJson: null,
      aiProvider: 'mock',
      aiModel: 'mock',
      errorMessage: null,
      convertedAvailabilityId: 30001 + i,
      reviewNote: 'シード確定済み',
      createdBy: salesUsers[i % salesUsers.length].id
    });
  }
}

// ---------- section 22: user permission groups ----------
const userPermissionGroups = [];
const groupByRole = { '管理者': 1, '営業': 2, 'HR': 3, 'マネージャー': 4, '要員': 5 };
users.forEach((u, i) => {
  userPermissionGroups.push({
    id: 32001 + i,
    tenantId: 'default',
    userId: u.id,
    groupId: groupByRole[u.role],
    assignedBy: null,
    assignedAt: DT(2026, 8, 9, 9, 0)
  });
});

// ---------- write SQL ----------
sql += '-- ============================================================\n';
sql += '-- R3_SCALE_300 シードデータ（生成済み。generate-seed.mjs から出力）\n';
sql += '-- 対象: 300人規模（要員255 + 営業25 + HR8 + マネージャー10 + 管理者2）\n';
sql += '-- ============================================================\n\n';
sql += 'SET NAMES utf8mb4;\n';
sql += 'SET FOREIGN_KEY_CHECKS = 0;\n\n';

// users (existing admin id=1 remains)
emit('sys_user', ['id', 'username', 'password', 'real_name', 'role', 'email', 'status', 'failed_count', 'locked_until'], users.map((u) => [u.id, u.username, u.password, u.realName, u.role, u.email, u.status, 0, null]));
emit('m_organization_unit', ['id', 'tenant_id', 'legal_entity_id', 'code', 'name', 'type', 'parent_id', 'valid_from', 'valid_to', 'status', 'merged_into', 'version', 'deleted_flag'], orgs.map((o) => [o[0], null, null, o[1], o[2], o[3], o[4], D(2020, 4, 1), null, '有効', null, 0, 0]));
emit('m_cost_center', ['id', 'legal_entity_id', 'code', 'name', 'organization_id', 'valid_from', 'valid_to', 'status', 'version', 'deleted_flag'], costCenters.map((c) => [c[0], null, c[1], c[2], c[3], D(2020, 4, 1), null, '有効', 0, 0]));
const userOrgs = [];
users.forEach((u) => {
  const orgId = u.role === '管理者' ? 3001 : u.role === '営業' ? (u.id % 2 === 0 ? 3002 : 3003) : u.role === 'HR' ? 3008 : u.role === 'マネージャー' ? 3004 : techOrgs[u.id % 3];
  userOrgs.push({
    id: 33001 + (u.id - 101),
    userId: u.id,
    organizationId: orgId,
    positionName: u.role === '営業' ? '営業' : u.role === 'HR' ? '人事' : u.role === 'マネージャー' ? '部長' : u.role === '管理者' ? '管理者' : 'エンジニア',
    managerUserId: managerUsers.length ? managerUsers[u.id % managerUsers.length].id : null,
    primaryFlag: 1,
    validFrom: D(2024, 4, 1),
    validTo: null,
    version: 0,
    deletedFlag: 0
  });
});
emit('t_user_organization', ['id', 'user_id', 'organization_id', 'position_name', 'manager_user_id', 'primary_flag', 'valid_from', 'valid_to', 'version', 'deleted_flag'], userOrgs.map((o) => [o.id, o.userId, o.organizationId, o.positionName, o.managerUserId, o.primaryFlag, o.validFrom, o.validTo, o.version, o.deletedFlag]));
emit('t_organization_relation_history', ['id', 'organization_id', 'parent_id', 'status', 'valid_from', 'valid_to', 'deleted_flag'], orgRelationHistory.map((o) => [o.id, o.organizationId, o.parentId, o.status, o.validFrom, o.validTo, o.deletedFlag]));

emit('m_customer', ['id', 'company_name', 'company_name_kana', 'contact_person', 'contact_email', 'contact_phone', 'address', 'commercial_flow', 'trust_level', 'remarks'], customers.map((c) => [c.id, c.companyName, c.kana, null, null, null, c.address, c.flow, c.trust, c.remarks]));
emit('t_customer_contact', ['id', 'customer_id', 'name', 'name_kana', 'department', 'position', 'roles_json', 'email', 'phone', 'primary_flag', 'valid_from', 'valid_to', 'status', 'version', 'deleted_flag'], customerContacts.map((c) => [c.id, c.customerId, c.name, c.kana, c.department, c.position, c.rolesJson, c.email, c.phone, c.primaryFlag, c.validFrom, null, c.status, c.version, 0]));
emit('m_workplace', ['id', 'tenant_id', 'customer_id', 'organization_id', 'name', 'address', 'organization_unit', 'phone', 'valid_from', 'valid_to', 'status', 'version', 'deleted_flag'], workplaces.map((w) => [w.id, w.tenantId, w.customerId, w.organizationId, w.name, w.address, w.organizationUnit, w.phone, w.validFrom, null, w.status, w.version, 0]));

// V2初期マスタの要員3名（id 1-3）は既存のため、追加分のみINSERTする
emit('t_engineer', ['id', 'full_name', 'full_name_kana', 'initial_name', 'gender', 'birth_date', 'nationality', 'nearest_station', 'prefecture', 'railway_company', 'employment_type', 'status', 'expected_unit_price', 'cost_center_id', 'organization_id', 'overtime_exempt_flag', 'available_date', 'experience_years', 'japanese_level', 'resume_summary', 'remarks', 'created_by'], engineers.filter((e) => e.id >= 1001).map((e) => [e.id, e.fullName, e.kana, e.initialName, e.gender, e.birthDate, e.nationality, e.station, e.prefecture, e.railway, e.employment, e.status, e.unitPrice, e.costCenterId, e.organizationId, e.overtimeExempt, e.availableDate, e.exp, e.japaneseLevel, e.resumeSummary, e.remarks, e.createdBy]));
emit('t_engineer_skill', ['id', 'engineer_id', 'skill_id', 'proficiency', 'experience_years'], engineerSkills.map((s) => [s.id, s.engineerId, s.skillId, s.proficiency, s.experienceYears]));
emit('t_engineer_career', ['id', 'engineer_id', 'period_from', 'period_to', 'project_name', 'client_industry', 'role', 'description', 'tech_stack', 'team_size'], engineerCareers.map((c) => [c.id, c.engineerId, c.periodFrom, c.periodTo, c.projectName, c.clientIndustry, c.role, c.description, c.techStack, c.teamSize]));
emit('t_engineer_sales', ['id', 'engineer_id', 'sales_user_id', 'primary_flag', 'assigned_at', 'released_at', 'remarks', 'deleted_flag'], engineerSales.map((s) => [s.id, s.engineerId, s.salesUserId, s.primaryFlag, s.assignedAt, s.releasedAt, s.remarks, s.deletedFlag]));
emit('t_engineer_accounting_history', ['id', 'engineer_id', 'organization_id', 'organization_history_status', 'cost_center_id', 'expected_unit_price', 'valid_from', 'valid_to', 'deleted_flag'], engineerAccountingHistory.map((h) => [h.id, h.engineerId, h.organizationId, h.organizationHistoryStatus, h.costCenterId, h.expectedUnitPrice, h.validFrom, h.validTo, h.deletedFlag]));
emit('t_engineer_bp_affiliation', ['id', 'tenant_id', 'engineer_id', 'bp_company_id', 'valid_from', 'valid_to', 'deleted_flag'], engineerBpAffiliations.map((a) => [a.id, a.tenantId, a.engineerId, a.bpCompanyId, a.validFrom, a.validTo, a.deletedFlag]));
emit('t_engineer_account_link', ['id', 'engineer_id', 'sys_user_id', 'linked_by'], engineerAccountLinks.map((l) => [l.id, l.engineerId, l.sysUserId, l.linkedBy]));

emit('t_project', ['id', 'project_name', 'customer_id', 'commercial_flow', 'description', 'required_count', 'unit_price_min', 'unit_price_max', 'work_location', 'remote_type', 'start_date', 'end_date', 'status', 'priority', 'remarks', 'source_opportunity_id', 'created_by'], projects.map((p) => [p.id, p.projectName, p.customerId, p.flow, p.description, p.requiredCount, p.unitMin, p.unitMax, p.workLocation, p.remote, p.startDate, p.endDate, p.status, p.priority, p.remarks, p.sourceOpportunityId, p.createdBy]));
emit('t_project_skill', ['id', 'project_id', 'skill_id', 'required_level', 'is_must'], projectSkills.map((s) => [s.id, s.projectId, s.skillId, s.requiredLevel, s.isMust]));

emit('t_proposal', ['id', 'engineer_id', 'project_id', 'proposed_unit_price', 'status', 'skill_sheet_path', 'proposal_email_text', 'ai_match_score', 'match_reason', 'remarks', 'proposed_by', 'proposed_at', 'closed_at', 'source_opportunity_id', 'deleted_flag'], proposals.map((p) => [p.id, p.engineerId, p.projectId, p.proposedUnitPrice, p.status, p.skillSheetPath, p.proposalEmailText, p.aiMatchScore, p.matchReason, p.remarks, p.proposedBy, p.proposedAt, p.closedAt, p.sourceOpportunityId, p.deletedFlag]));
emit('t_proposal_history', ['id', 'proposal_id', 'from_status', 'to_status', 'changed_by', 'changed_at', 'remarks'], proposalHistory.map((h) => [h.id, h.proposalId, h.fromStatus, h.toStatus, h.changedBy, h.changedAt, h.remarks]));

emit('t_opportunity', ['id', 'customer_id', 'title', 'stage', 'expected_start_month', 'duration_months', 'required_count', 'unit_price', 'expected_amount', 'probability', 'owner_user_id', 'next_action_date', 'competitor', 'lost_reason', 'converted_project_id', 'converted_quotation_id', 'version', 'stage_changed_at'], opportunities.map((o) => [o.id, o.customerId, o.title, o.stage, o.expectedStartMonth, o.durationMonths, o.requiredCount, o.unitPrice, o.expectedAmount, o.probability, o.ownerUserId, o.nextActionDate, o.competitor, o.lostReason, o.convertedProjectId, o.convertedQuotationId, o.version, o.stageChangedAt]));
emit('t_lead', ['id', 'company_name', 'company_name_normalized', 'contact_name', 'contact_email', 'contact_email_normalized', 'contact_phone', 'contact_phone_normalized', 'source', 'owner_user_id', 'status', 'converted_customer_id', 'converted_opportunity_id', 'version', 'source_cost'], leads.map((l) => [l.id, l.companyName, l.companyNameNormalized, l.contactName, l.contactEmail, l.contactEmailNormalized, l.contactPhone, l.contactPhoneNormalized, l.source, l.ownerUserId, l.status, l.convertedCustomerId, l.convertedOpportunityId, l.version, l.sourceCost]));

emit('t_quotation', ['id', 'quotation_no', 'customer_id', 'project_id', 'engineer_id', 'proposal_id', 'title', 'unit_price', 'settlement_hours_min', 'settlement_hours_max', 'valid_until', 'status', 'remarks', 'source_opportunity_id', 'created_by', 'version'], quotations.map((q) => [q.id, q.quotationNo, q.customerId, q.projectId, q.engineerId, q.proposalId, q.title, q.unitPrice, q.settlementHoursMin, q.settlementHoursMax, q.validUntil, q.status, q.remarks, q.sourceOpportunityId, q.createdBy, q.version]));

emit('t_sales_order', ['id', 'tenant_id', 'legal_entity_id', 'order_no', 'customer_po_no', 'customer_id', 'contact_id', 'quotation_id', 'order_date', 'start_date', 'end_date', 'status', 'total_amount_snapshot', 'payment_terms_snapshot', 'source_document_id', 'acknowledgement_document_id', 'version', 'created_by'], salesOrders.map((o) => [o.id, o.tenantId, o.legalEntityId, o.orderNo, o.customerPoNo, o.customerId, o.contactId, o.quotationId, o.orderDate, o.startDate, o.endDate, o.status, o.totalAmountSnapshot, o.paymentTermsSnapshot, o.sourceDocumentId, o.acknowledgementDocumentId, o.version, o.createdBy]));
emit('t_sales_order_line', ['id', 'order_id', 'line_no', 'project_id', 'engineer_id', 'quantity', 'unit_price', 'settlement_min', 'settlement_max', 'amount', 'remarks'], salesOrderLines.map((l) => [l.id, l.orderId, l.lineNo, l.projectId, l.engineerId, l.quantity, l.unitPrice, l.settlementMin, l.settlementMax, l.amount, l.remarks]));

emit('t_contract', ['id', 'contract_no', 'proposal_id', 'engineer_id', 'project_id', 'customer_id', 'sales_user_id', 'contract_type', 'start_date', 'contract_date', 'job_description', 'work_location', 'inspection_due_date', 'payment_due_date', 'payment_method', 'end_date', 'selling_price', 'cost_price', 'cost_center_id', 'settlement_hours_min', 'settlement_hours_max', 'fraction_rule', 'auto_renew', 'status', 'remarks', 'order_line_id', 'acceptance_required', 'acceptance_exemption_reason', 'direct_command_flag', 'commission_base_type', 'commission_rate', 'created_by', 'renewed_from_contract_id', 'quotation_id', 'renewal_decision', 'version'], contracts.map((c) => [c.id, c.contractNo, c.proposalId, c.engineerId, c.projectId, c.customerId, c.salesUserId, c.contractType, c.startDate, c.contractDate, c.jobDescription, c.workLocation, c.inspectionDueDate, c.paymentDueDate, c.paymentMethod, c.endDate, c.selling, c.cost, c.costCenterId, c.settlementHoursMin, c.settlementHoursMax, c.fractionRule, c.autoRenew, c.status, c.remarks, c.orderLineId, c.acceptanceRequired, c.acceptanceExemptionReason, c.directCommandFlag, c.commissionBaseType, c.commissionRate, c.createdBy, c.renewedFromContractId, c.quotationId, c.renewalDecision, c.version]));
emit('t_contract_price_history', ['id', 'contract_id', 'apply_from_month', 'selling_price', 'cost_price', 'reason', 'created_by'], contractPriceHistory.map((h) => [h.id, h.contractId, h.applyFromMonth, h.sellingPrice, h.costPrice, h.reason, h.createdBy]));

emit('t_work_record', ['id', 'contract_id', 'work_month', 'actual_hours', 'billing_amount', 'payment_amount', 'status', 'remarks', 'created_by', 'reject_comment', 'organization_id', 'cost_center_id', 'accounting_dimension_frozen'], workRecords.map((w) => [w.id, w.contractId, w.workMonth, w.actualHours, w.billingAmount, w.paymentAmount, w.status, w.remarks, w.createdBy, w.rejectComment, w.organizationId, w.costCenterId, w.accountingDimensionFrozen]));
emit('t_work_record_daily', ['id', 'work_record_id', 'work_date', 'start_time', 'end_time', 'break_minutes', 'worked_hours', 'remarks'], workRecordDaily.map((d) => [d.id, d.workRecordId, d.workDate, d.startTime, d.endTime, d.breakMinutes, d.workedHours, d.remarks]));
emit('t_acceptance', ['id', 'contract_id', 'work_record_id', 'work_month', 'status', 'submitted_at', 'customer_contact_id', 'customer_contact_name_snapshot', 'accepted_at', 'reject_comment', 'document_id', 'hours_snapshot', 'amount_snapshot', 'work_record_updated_at', 'version', 'created_by'], acceptances.map((a) => [a.id, a.contractId, a.workRecordId, a.workMonth, a.status, a.submittedAt, a.customerContactId, a.customerContactNameSnapshot, a.acceptedAt, a.rejectComment, a.documentId, a.hoursSnapshot, a.amountSnapshot, a.workRecordUpdatedAt, a.version, a.createdBy]));

emit('t_invoice', ['id', 'invoice_no', 'customer_id', 'billing_month', 'subtotal', 'tax', 'total', 'status', 'issued_date', 'paid_date', 'remarks', 'created_by', 'due_date', 'tax_rate', 'cost_center_id', 'version'], invoices.map((i) => [i.id, i.invoiceNo, i.customerId, i.billingMonth, i.subtotal, i.tax, i.total, i.status, i.issuedDate, i.paidDate, i.remarks, i.createdBy, i.dueDate, i.taxRate, i.costCenterId, i.version]));
emit('t_invoice_item', ['id', 'invoice_id', 'work_record_id', 'description', 'amount'], invoiceItems.map((i) => [i.id, i.invoiceId, i.workRecordId, i.description, i.amount]));
emit('t_invoice_payment', ['id', 'invoice_id', 'paid_date', 'amount', 'fee', 'remarks', 'created_by'], invoicePayments.map((p) => [p.id, p.invoiceId, p.paidDate, p.amount, p.fee, p.remarks, p.createdBy]));
emit('t_bp_payment', ['id', 'work_record_id', 'layer_order', 'payee_company_name', 'bp_company_id', 'bp_company_name_snapshot', 'terms_snapshot_json', 'parent_payment_id', 'amount', 'status', 'paid_date', 'remarks', 'deleted_flag', 'cost_center_id', 'version'], bpPayments.map((p) => [p.id, p.workRecordId, p.layerOrder, p.payeeCompanyName, p.bpCompanyId, p.bpCompanyNameSnapshot, p.termsSnapshotJson, p.parentPaymentId, p.amount, p.status, p.paidDate, p.remarks, p.deletedFlag, p.costCenterId, p.version]));

emit('t_candidate', ['id', 'name', 'contact_email', 'contact_phone', 'skill_summary', 'desired_rate', 'source', 'current_stage', 'next_action_date', 'converted_engineer_id', 'remarks', 'deleted_flag', 'created_by'], candidates.map((c) => [c.id, c.name, c.contactEmail, c.contactPhone, c.skillSummary, c.desiredRate, c.source, c.currentStage, c.nextActionDate, c.convertedEngineerId, c.remarks, c.deletedFlag, c.createdBy]));
emit('t_candidate_activity', ['id', 'candidate_id', 'stage', 'reason', 'changed_by', 'changed_at', 'remarks'], candidateActivities.map((a) => [a.id, a.candidateId, a.stage, a.reason, a.changedBy, a.changedAt, a.remarks]));

emit('t_task', ['id', 'tenant_id', 'title', 'description', 'assignee_user_id', 'requester_user_id', 'due_date', 'priority', 'status', 'target_type', 'target_id', 'completed_at', 'version'], tasks.map((t) => [t.id, t.tenantId, t.title, t.description, t.assigneeUserId, t.requesterUserId, t.dueDate, t.priority, t.status, t.targetType, t.targetId, t.completedAt, t.version]));
emit('t_notification', ['id', 'type', 'title', 'message', 'link_url', 'dedupe_key', 'menu_key', 'recipient_user_id', 'organization_id'], notifications.map((n) => [n.id, n.type, n.title, n.message, n.linkUrl, n.dedupeKey, n.menuKey, n.recipientUserId, n.organizationId]));
emit('t_notification_read', ['id', 'notification_id', 'user_id', 'read_at'], notificationReads.map((r) => [r.id, r.notificationId, r.userId, r.readAt]));
emit('t_audit_log', ['id', 'username', 'method', 'uri', 'status', 'created_at', 'application_code', 'success_flag'], auditLogs.map((a) => [a.id, a.username, a.method, a.uri, a.status, a.created_at, a.applicationCode, a.successFlag]));

emit('t_engineer_followup', ['id', 'engineer_id', 'followup_type', 'followup_date', 'satisfaction', 'topic', 'content', 'next_date', 'created_by', 'deleted_flag'], engineerFollowups.map((f) => [f.id, f.engineerId, f.followupType, f.followupDate, f.satisfaction, f.topic, f.content, f.nextDate, f.createdBy, f.deletedFlag]));
emit('t_sales_activity', ['id', 'customer_id', 'contact_id', 'opportunity_id', 'activity_type', 'activity_date', 'title', 'content', 'next_action_date', 'completed_flag', 'version', 'assignee_user_id', 'created_by'], salesActivities.map((a) => [a.id, a.customerId, a.contactId, a.opportunityId, a.activityType, a.activityDate, a.title, a.content, a.nextActionDate, a.completedFlag, a.version, a.assigneeUserId, a.createdBy]));

emit('m_work_calendar', ['id', 'legal_entity_id', 'organization_id', 'engineer_id', 'name', 'valid_from', 'valid_to', 'status', 'version', 'deleted_flag'], workCalendars.map((c) => [c[0], null, null, null, c[1], c[2], c[3], c[4], 0, 0]));
emit('m_work_calendar_day', ['id', 'calendar_id', 'calendar_date', 'day_type', 'scheduled_minutes'], workCalendarDays.map((d) => [d.id, d.calendarId, d.calendarDate, d.dayType, d.scheduledMinutes]));
emit('t_employee_attendance', ['id', 'engineer_id', 'legal_entity_id', 'organization_id', 'work_calendar_id', 'work_date', 'clock_in', 'clock_out', 'break_minutes', 'regular_minutes', 'overtime_minutes', 'holiday_minutes', 'late_night_minutes', 'work_type', 'workplace_type', 'source', 'source_external_id', 'status', 'remarks', 'version', 'deleted_flag'], employeeAttendance.map((a) => [a.id, a.engineerId, a.legalEntityId, a.organizationId, a.workCalendarId, a.workDate, a.clockIn, a.clockOut, a.breakMinutes, a.regularMinutes, a.overtimeMinutes, a.holidayMinutes, a.lateNightMinutes, a.workType, a.workplaceType, a.source, a.sourceExternalId, a.status, a.remarks, a.version, a.deletedFlag]));
emit('t_attendance_month', ['id', 'engineer_id', 'legal_entity_id', 'organization_id', 'work_month', 'scheduled_minutes', 'worked_minutes', 'regular_minutes', 'overtime_minutes', 'holiday_minutes', 'late_night_minutes', 'leave_minutes', 'status', 'submitted_at', 'submitted_by', 'approved_at', 'approved_by', 'closed_at', 'closed_by', 'close_reason', 'version', 'deleted_flag'], attendanceMonths.map((a) => [a.id, a.engineerId, a.legalEntityId, a.organizationId, a.workMonth, a.scheduledMinutes, a.workedMinutes, a.regularMinutes, a.overtimeMinutes, a.holidayMinutes, a.lateNightMinutes, a.leaveMinutes, a.status, a.submittedAt, a.submittedBy, a.approvedAt, a.approvedBy, a.closedAt, a.closedBy, a.closeReason, a.version, a.deletedFlag]));
emit('t_leave_request', ['id', 'engineer_id', 'legal_entity_id', 'organization_id', 'leave_type', 'start_date', 'end_date', 'start_time', 'end_time', 'requested_minutes', 'reason', 'status', 'approval_request_id', 'version', 'created_by', 'deleted_flag'], leaveRequests.map((l) => [l.id, l.engineerId, l.legalEntityId, l.organizationId, l.leaveType, l.startDate, l.endDate, l.startTime, l.endTime, l.requestedMinutes, l.reason, l.status, l.approvalRequestId, l.version, l.createdBy, l.deletedFlag]));
emit('m_overtime_agreement', ['id', 'legal_entity_id', 'valid_from', 'valid_to', 'special_clause', 'normal_month_limit_minutes', 'normal_year_limit_minutes', 'special_year_limit_minutes', 'total_month_limit_minutes', 'multi_month_average_limit_minutes', 'exceed_month_count_limit', 'warning_threshold_percent', 'warning_recipients', 'config_json', 'version', 'deleted_flag'], overtimeAgreements.map((a) => a));
emit('t_overtime_followup', ['id', 'engineer_id', 'period_month', 'warning_code', 'status', 'notified_at', 'health_action_status', 'version', 'deleted_flag'], overtimeFollowups.map((f) => [f.id, f.engineerId, f.periodMonth, f.warningCode, f.status, f.notifiedAt, f.healthActionStatus, f.version, f.deletedFlag]));

emit('t_contract_compliance_profile', ['id', 'tenant_id', 'contract_id', 'contract_type_detail', 'workplace_id', 'work_description', 'work_location', 'work_time', 'break_time', 'holiday_rule', 'overtime_rule', 'command_person_contact_id', 'command_person_name_snapshot', 'command_person_title_snapshot', 'client_responsible_contact_id', 'client_responsible_person', 'client_responsible_phone', 'dispatch_responsible_user_id', 'dispatch_responsible_name_snapshot', 'dispatch_responsible_title_snapshot', 'dispatch_responsible_phone_snapshot', 'dispatch_period_start', 'dispatch_period_end', 'limitation_date', 'workplace_limitation_date', 'worker_limitation_date', 'treatment_scheme', 'complaint_contact', 'complaint_processing_history', 'training_info', 'safety_health_info', 'insurance_notification', 'welfare_info', 'instruction_route', 'responsibility_degree', 'subcontract_allowed', 'acceptance_method', 'dispatch_worker_count', 'agreement_target_flag', 'indefinite_term_flag', 'age_over_60_flag', 'employment_stability_measure', 'health_insurance_status', 'health_insurance_missing_reason', 'health_insurance_expected_date', 'pension_insurance_status', 'pension_insurance_missing_reason', 'pension_insurance_expected_date', 'employment_insurance_status', 'employment_insurance_missing_reason', 'employment_insurance_expected_date', 'snapshot_json', 'workplace_snapshot_json', 'worker_snapshot_json', 'snapshot_at', 'version', 'deleted_flag'], complianceProfiles.map((p) => [p.id, p.tenantId, p.contractId, p.contractTypeDetail, p.workplaceId, p.workDescription, p.workLocation, p.workTime, p.breakTime, p.holidayRule, p.overtimeRule, p.commandPersonContactId, p.commandPersonNameSnapshot, p.commandPersonTitleSnapshot, p.clientResponsibleContactId, p.clientResponsiblePerson, p.clientResponsiblePhone, p.dispatchResponsibleUserId, p.dispatchResponsibleNameSnapshot, p.dispatchResponsibleTitleSnapshot, p.dispatchResponsiblePhoneSnapshot, p.dispatchPeriodStart, p.dispatchPeriodEnd, p.limitationDate, p.workplaceLimitationDate, p.workerLimitationDate, p.treatmentScheme, p.complaintContact, p.complaintProcessingHistory, p.trainingInfo, p.safetyHealthInfo, p.insuranceNotification, p.welfareInfo, p.instructionRoute, p.responsibilityDegree, p.subcontractAllowed, p.acceptanceMethod, p.dispatchWorkerCount, p.agreementTargetFlag, p.indefiniteTermFlag, p.ageOver60Flag, p.employmentStabilityMeasure, p.healthInsuranceStatus, p.healthInsuranceMissingReason, p.healthInsuranceExpectedDate, p.pensionInsuranceStatus, p.pensionInsuranceMissingReason, p.pensionInsuranceExpectedDate, p.employmentInsuranceStatus, p.employmentInsuranceMissingReason, p.employmentInsuranceExpectedDate, p.snapshotJson, p.workplaceSnapshotJson, p.workerSnapshotJson, p.snapshotAt, p.version, p.deletedFlag]));
emit('t_compliance_finding', ['id', 'tenant_id', 'contract_id', 'code', 'severity', 'status', 'condition_fingerprint', 'detected_at', 'due_date', 'acknowledged_by', 'acknowledged_at', 'resolution_note', 'evidence_document_id', 'version', 'deleted_flag'], complianceFindings.map((f) => [f.id, f.tenantId, f.contractId, f.code, f.severity, f.status, f.conditionFingerprint, f.detectedAt, f.dueDate, f.acknowledgedBy, f.acknowledgedAt, f.resolutionNote, f.evidenceDocumentId, f.version, f.deletedFlag]));

emit('t_monthly_accounting_dimension', ['id', 'work_month', 'source_type', 'source_id', 'organization_id', 'cost_center_id', 'sales_user_id', 'revenue', 'cost', 'snapshot_at'], monthlyDimensions.map((m) => [m.id, m.workMonth, m.sourceType, m.sourceId, m.organizationId, m.costCenterId, m.salesUserId, m.revenue, m.cost, m.snapshotAt]));
emit('t_management_budget', ['id', 'organization_id', 'cost_center_id', 'budget_month', 'revenue', 'gross_profit', 'utilization_count', 'hire_count', 'version', 'deleted_flag'], managementBudgets.map((b) => [b.id, b.organizationId, b.costCenterId, b.budgetMonth, b.revenue, b.grossProfit, b.utilizationCount, b.hireCount, b.version, b.deletedFlag]));

emit('m_bp_company', ['id', 'tenant_id', 'legal_name', 'name_kana', 'normalized_name', 'entity_type', 'corporate_number', 'invoice_registration_number', 'capital_band', 'employee_band', 'address', 'representative', 'status', 'suspension_reason', 'suspension_start_date', 'suspension_end_date', 'suspension_approved_by', 'rating', 'primary_sales_user_id', 'compliance_applicability', 'applicability_checked_by', 'applicability_checked_at', 'applicability_note', 'version', 'created_by', 'deleted_flag'], bpCompanies.map((b) => [b.id, b.tenantId, b.legalName, b.nameKana, b.normalizedName, b.entityType, b.corporateNumber, b.invoiceRegistrationNumber, b.capitalBand, b.employeeBand, b.address, b.representative, b.status, b.suspensionReason, b.suspensionStartDate, b.suspensionEndDate, b.suspensionApprovedBy, b.rating, b.primarySalesUserId, b.complianceApplicability, b.applicabilityCheckedBy, b.applicabilityCheckedAt, b.applicabilityNote, b.version, b.createdBy, b.deletedFlag]));
emit('t_bp_contact', ['id', 'tenant_id', 'bp_company_id', 'name', 'department', 'role', 'email', 'phone', 'primary_flag', 'deleted_flag'], bpContacts.map((c) => [c.id, c.tenantId, c.bpCompanyId, c.name, c.department, c.role, c.email, c.phone, c.primaryFlag, c.deletedFlag]));
emit('t_bp_bank_account', ['id', 'tenant_id', 'bp_company_id', 'bank_name', 'branch_name', 'account_type', 'encrypted_account_number', 'account_holder', 'masked_label', 'valid_from', 'valid_to', 'approval_status', 'approved_by', 'approved_at', 'deleted_flag'], bpBankAccounts.map((a) => [a.id, a.tenantId, a.bpCompanyId, a.bankName, a.branchName, a.accountType, a.encryptedAccountNumber, a.accountHolder, a.maskedLabel, a.validFrom, a.validTo, a.approvalStatus, a.approvedBy, a.approvedAt, a.deletedFlag]));
emit('t_bp_terms', ['id', 'tenant_id', 'bp_company_id', 'effective_from', 'effective_to', 'closing_day', 'payment_month_offset', 'payment_day', 'fee_bearer', 'payment_method', 'fee_bearer_exception_reason', 'fee_bearer_approved_by', 'fee_bearer_approved_at', 'max_payment_days', 'version', 'deleted_flag'], bpTerms.map((t) => [t.id, t.tenantId, t.bpCompanyId, t.effectiveFrom, t.effectiveTo, t.closingDay, t.paymentMonthOffset, t.paymentDay, t.feeBearer, t.paymentMethod, t.feeBearerExceptionReason, t.feeBearerApprovedBy, t.feeBearerApprovedAt, t.maxPaymentDays, t.version, t.deletedFlag]));
emit('t_bp_evaluation', ['id', 'tenant_id', 'bp_company_id', 'period', 'quality_score', 'response_score', 'retention_score', 'compliance_score', 'billing_accuracy_score', 'comment', 'evaluated_by', 'deleted_flag'], bpEvaluations.map((e) => [e.id, e.tenantId, e.bpCompanyId, e.period, e.qualityScore, e.responseScore, e.retentionScore, e.complianceScore, e.billingAccuracyScore, e.comment, e.evaluatedBy, e.deletedFlag]));
emit('t_bp_price_negotiation', ['id', 'tenant_id', 'bp_company_id', 'requested_at', 'responded_at', 'status', 'requested_amount', 'agreed_amount', 'summary', 'document_id', 'deleted_flag'], bpNegotiations.map((n) => [n.id, n.tenantId, n.bpCompanyId, n.requestedAt, n.respondedAt, n.status, n.requestedAmount, n.agreedAmount, n.summary, n.documentId, n.deletedFlag]));
emit('t_bp_availability', ['id', 'initial_name', 'bp_company', 'bp_company_id', 'skills_json', 'unit_price', 'available_from', 'experience_years', 'status', 'promoted_engineer_id', 'remarks', 'created_by'], bpAvailability.map((a) => [a.id, a.initialName, a.bpCompany, a.bpCompanyId, a.skillsJson, a.unitPrice, a.availableFrom, a.experienceYears, a.status, a.promotedEngineerId, a.remarks, a.createdBy]));
emit('t_bp_availability_ingestion', ['id', 'original_file_name', 'stored_file_name', 'file_ext', 'status', 'extracted_text', 'parsed_json', 'ai_provider', 'ai_model', 'error_message', 'converted_availability_id', 'review_note', 'created_by'], bpAvailabilityIngestion.map((j) => [j.id, j.originalFileName, j.storedFileName, j.fileExt, j.status, j.extractedText, j.parsedJson, j.aiProvider, j.aiModel, j.errorMessage, j.convertedAvailabilityId, j.reviewNote, j.createdBy]));

emit('t_user_permission_group', ['id', 'tenant_id', 'user_id', 'group_id', 'assigned_by', 'assigned_at'], userPermissionGroups.map((g) => [g.id, g.tenantId, g.userId, g.groupId, g.assignedBy, g.assignedAt]));

// 商機→案件/見積、案件→商機、見積→商機、提案→商機の相互参照を確定させる
const wonOpportunityIds = opportunities.filter((o) => o.stage === '受注').map((o) => o.id);
if (wonOpportunityIds.length) {
  sql += '\nUPDATE t_opportunity SET converted_project_id = 5001 + ((id - 14501) % 100), converted_quotation_id = 15001 + ((id - 14501) % 60) WHERE id IN (' + wonOpportunityIds.join(',') + ');\n';
}
sql += '\nUPDATE t_project SET source_opportunity_id = 14501 + ((id - 5001) % 60) WHERE id % 7 = 0;\n';
sql += '\nUPDATE t_quotation SET source_opportunity_id = 14501 + ((id - 15001) % 60) WHERE id % 5 = 0;\n';
sql += '\nUPDATE t_proposal SET source_opportunity_id = 14501 + ((id - 6001) % 60) WHERE id % 6 = 0;\n';

sql += '\nSET FOREIGN_KEY_CHECKS = 1;\n';

fs.mkdirSync(path.dirname(OUT_SQL), { recursive: true });
fs.mkdirSync(path.dirname(OUT_MIGRATION), { recursive: true });
fs.writeFileSync(OUT_SQL, sql, 'utf8');
fs.writeFileSync(OUT_MIGRATION, sql, 'utf8');

const summary = Object.entries(counts).map(([t, n]) => `${t}: ${n}`).join('\n');
console.log(`generated ${sql.length} bytes`);
console.log(summary);
