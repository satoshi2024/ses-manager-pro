import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');

function getAllFiles(dir, filter) {
  let results = [];
  if (!fs.existsSync(dir)) return results;
  const list = fs.readdirSync(dir, { withFileTypes: true });
  for (const entry of list) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      results = results.concat(getAllFiles(fullPath, filter));
    } else if (!filter || filter(entry.name)) {
      results.push(fullPath);
    }
  }
  return results;
}

// 1. Page Routes
const pageControllers = getAllFiles(path.join(ROOT, 'src/main/java/com/ses/controller/page'), f => f.endsWith('.java'));
const pageRoutes = [];
for (const file of pageControllers) {
  const content = fs.readFileSync(file, 'utf8');
  const className = path.basename(file, '.java');
  const reqMatch = content.match(/@RequestMapping\(\s*["']([^"']+)["']\s*\)/);
  const baseRoute = reqMatch ? reqMatch[1] : '';

  const methodRegex = /@(Get|Post|Request)Mapping(?:\(\s*(?:value\s*=\s*)?(?:\{([^}]+)\}|["']([^"']*)["'])[^)]*\))?/g;
  let match;
  while ((match = methodRegex.exec(content)) !== null) {
    const verb = match[1].toUpperCase();
    const rawPaths = match[2] || match[3] || '';
    const paths = rawPaths ? rawPaths.split(',').map(s => s.replace(/["'\s]/g, '')) : [''];
    for (const p of paths) {
      let full = (baseRoute + '/' + p).replace(/\/+/g, '/');
      if (full.length > 1 && full.endsWith('/')) full = full.slice(0, -1);
      if (full === '') full = '/';
      pageRoutes.push({
        controller: className,
        verb: verb === 'REQUEST' ? 'ANY' : verb,
        path: full
      });
    }
  }
}
pageRoutes.sort((a, b) => a.path.localeCompare(b.path));

// 2. API Endpoints
const apiControllers = getAllFiles(path.join(ROOT, 'src/main/java/com/ses/controller'), f => f.endsWith('.java'))
  .filter(f => !f.includes('controller\\page\\') && !f.includes('controller/page/'));
const apiEndpoints = [];
for (const file of apiControllers) {
  const content = fs.readFileSync(file, 'utf8');
  const className = path.basename(file, '.java');
  const reqMatch = content.match(/@RequestMapping\(\s*(?:value\s*=\s*)?["']([^"']+)["']\s*\)/);
  const baseRoute = reqMatch ? reqMatch[1] : '';

  const methodRegex = /@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\(\s*(?:value\s*=\s*)?(?:\{([^}]+)\}|["']([^"']*)["'])[^)]*\))?/g;
  let match;
  while ((match = methodRegex.exec(content)) !== null) {
    const verb = match[1].toUpperCase();
    const rawPaths = match[2] || match[3] || '';
    const paths = rawPaths ? rawPaths.split(',').map(s => s.replace(/["'\s]/g, '')) : [''];
    for (const p of paths) {
      let full = (baseRoute + '/' + p).replace(/\/+/g, '/');
      if (full.length > 1 && full.endsWith('/')) full = full.slice(0, -1);
      if (full === '') full = '/';
      apiEndpoints.push({
        controller: className,
        verb: verb === 'REQUEST' ? 'ANY' : verb,
        path: full
      });
    }
  }
}
apiEndpoints.sort((a, b) => a.path.localeCompare(b.path) || a.verb.localeCompare(b.verb));

// 3. Templates
const templateFiles = getAllFiles(path.join(ROOT, 'src/main/resources/templates'), f => f.endsWith('.html'))
  .map(f => path.relative(path.join(ROOT, 'src/main/resources/templates'), f).replace(/\\/g, '/'))
  .sort();

// 4. Entities, Tables, @Version & State fields
const entityFiles = getAllFiles(path.join(ROOT, 'src/main/java/com/ses/entity'), f => f.endsWith('.java'));
const entities = [];
for (const file of entityFiles) {
  const content = fs.readFileSync(file, 'utf8');
  const className = path.basename(file, '.java');
  const tableMatch = content.match(/@TableName\(\s*["']([^"']+)["']\s*\)/);
  const tableName = tableMatch ? tableMatch[1] : null;

  const hasVersion = /@Version\b/.test(content);
  let versionField = null;
  if (hasVersion) {
    const vMatch = content.match(/@Version\s+(?:private|protected|public)\s+\w+\s+(\w+);/);
    versionField = vMatch ? vMatch[1] : 'version';
  }

  // Find status / state fields
  const statusFields = [];
  const statusMatches = content.matchAll(/(?:private|protected|public)\s+(\w+)\s+(status|state|dispatchState|contractStatus|reviewStatus|claimStatus)\b/gi);
  for (const sm of statusMatches) {
    statusFields.push({ field: sm[2], type: sm[1] });
  }

  entities.push({
    entity: className,
    tableName: tableName,
    hasOptimisticLock: hasVersion,
    versionField: versionField,
    statusFields: statusFields
  });
}
entities.sort((a, b) => a.entity.localeCompare(b.entity));

// 5. DataScope assertions
const javaFiles = getAllFiles(path.join(ROOT, 'src/main/java/com/ses'), f => f.endsWith('.java'));
const dataScopeCalls = [];
for (const file of javaFiles) {
  const content = fs.readFileSync(file, 'utf8');
  const relPath = path.relative(ROOT, file).replace(/\\/g, '/');
  const matches = content.matchAll(/dataScopeService\.(assert\w+)\(([^)]+)\)/g);
  for (const m of matches) {
    dataScopeCalls.push({
      file: relPath,
      method: m[1],
      args: m[2].trim()
    });
  }
}
dataScopeCalls.sort((a, b) => a.file.localeCompare(b.file));

// Assemble Inventory Object
const buildSha = 'f00360f95d3875b30d0f343ed9cc47e76d72b803';
const inventory = {
  freeze_metadata: {
    frozen_at: '2026-08-16T15:37:00+09:00',
    build_sha: buildSha,
    run_id: 'CI-20260816-001',
    summary: {
      total_page_routes: pageRoutes.length,
      total_api_endpoints: apiEndpoints.length,
      total_templates: templateFiles.length,
      total_entities: entities.length,
      total_tables_mapped: entities.filter(e => e.tableName).length,
      total_version_locked_entities: entities.filter(e => e.hasOptimisticLock).length,
      total_datascope_assertions: dataScopeCalls.length
    },
    definition_scope_notes: {
      principle: "本インベントリは 300 人規模結合テスト計画における coverage/分母算出の唯一の計画正本である（計画正本原則）。",
      page_routes: {
        count: pageRoutes.length,
        rule: "src/main/java/com/ses/controller/page 配下の全コントローラで宣言された @GetMapping, @RequestMapping を抽出。配列で複数 path が指定された mapping はすべて個別 path として展開計上。/my/**, /error 等の画面もすべて含む。"
      },
      api_endpoints: {
        count: apiEndpoints.length,
        rule: "src/main/java/com/ses/controller/api 配下および全 REST コントローラ（OAuth, Freee 等を含む）で定義された @GetMapping, @PostMapping, @PutMapping, @DeleteMapping, @PatchMapping, @RequestMapping を網羅抽出。複数 path 指定は展開計上。/api/** プレフィックス外のコールバック(/integrations/**)も含む。"
      },
      templates: {
        count: templateFiles.length,
        rule: "src/main/resources/templates 配下の全 .html ファイル（layout 断片, error 画面, my 画面, 各機能 area 画面）を相対パスで計上。"
      },
      entities: {
        count: entities.length,
        rule: "src/main/java/com/ses/entity 配下の全 Java クラスを計上。@TableName が付与された RDB マッピング対象（142件）および Entity パッケージ内の Value Object / 複合キー / 埋め込みモデル（5件）を含む。"
      },
      optimistic_locking_version: {
        count: entities.filter(e => e.hasOptimisticLock).length,
        rule: "全 Entity クラスのうち、MyBatis-Plus の @Version アノテーションが付与された楽観ロック対象フィールドを保持するクラスを抽出。"
      },
      datascope_assertions: {
        count: dataScopeCalls.length,
        rule: "全 Java ソースコード（Controller, Service）において呼び出されている dataScopeService.assert*（assertAllowedCustomer, assertAllowedEngineer, assertAllowedProposal, assertAllowedContract 等）の呼出箇所を抽出。"
      },
      coverage_gate_confirmation: "今後のすべての ITa/ITb/E2E/UI/Monkey/Security/Coverage の分母・網羅率計算は、本 freeze インベントリ（frozen-inventory.json）を正本として機械集計・判定する。"
    }
  },
  page_routes: pageRoutes,
  api_endpoints: apiEndpoints,
  templates: templateFiles,
  entities: entities,
  datascope_calls: dataScopeCalls
};

const jsonStr = JSON.stringify(inventory, null, 2);
const sha256 = crypto.createHash('sha256').update(jsonStr, 'utf8').digest('hex');

const outJsonPath = path.join(ROOT, '.kiro/specs/integration-test-plan/frozen-inventory.json');
const outShaPath = path.join(ROOT, '.kiro/specs/integration-test-plan/frozen-inventory-sha256.txt');

fs.writeFileSync(outJsonPath, jsonStr, 'utf8');
fs.writeFileSync(outShaPath, sha256 + '\n', 'utf8');

console.log('=== Inventory Freeze Complete ===');
console.log(`Page routes: ${pageRoutes.length}`);
console.log(`API endpoints: ${apiEndpoints.length}`);
console.log(`Templates: ${templateFiles.length}`);
console.log(`Entities: ${entities.length} (with @Version: ${entities.filter(e => e.hasOptimisticLock).length})`);
console.log(`DataScope assertions: ${dataScopeCalls.length}`);
console.log(`SHA-256: ${sha256}`);
console.log(`Saved to: ${outJsonPath}`);
