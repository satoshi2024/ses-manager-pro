import fs from 'fs';
import path from 'path';

const controllerDir = 'c:/Users/satos/OneDrive/文档/ses-manager-pro/src/main/java/com/ses/controller/api';
const files = fs.readdirSync(controllerDir).filter(f => f.endsWith('.java'));

const resourceNames = new Set([
  'ai', 'analytics', 'autocomplete', 'audit-logs', 'approval',
  'bp-availabilities', 'bp-companies', 'bp-availability-ingestions',
  'candidates', 'cashflow', 'compliance', 'compliance-gate',
  'contract-documents', 'contracts', 'crm', 'customers', 'dashboard',
  'documents', 'email-templates', 'engineer-change-requests',
  'expense-requests', 'one-on-ones', 'surveys', 'engineers', 'files',
  'identity-providers', 'invoices', 'leave', 'management-accounting',
  'monthly-closing', 'my', 'notifications', 'organizations', 'payroll',
  'permission-groups', 'portal-admin', 'profile', 'project-ingestions',
  'projects', 'proposals', 'quotations', 'reconciliation', 'resume-ingestions',
  'role-menus', 'sales-orders', 'acceptances', 'sales-performance',
  'skill-tags', 'skillsheet-templates', 'system-configs', 'users',
  'work-records', 'search', 'tasks', 'saved-views', 'batch-operations'
]);

const missing = [];
for (const file of files) {
  const content = fs.readFileSync(path.join(controllerDir, file), 'utf-8');
  const match = content.match(/@RequestMapping\(\s*\"(\/api\/[^\"]+)\"\s*\)/);
  if (match) {
    const route = match[1];
    const root = route.replace(/^\/api\//, '').split('/')[0];
    if (!resourceNames.has(root)) {
      missing.push({ file, route, root });
    }
  }
}

console.log('Missing resource prefixes in ActionPermissionResolver:');
console.log(JSON.stringify(missing, null, 2));
