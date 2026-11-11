import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const queue = await readFile('src/main/resources/static/js/pwa-queue.js', 'utf8');
const offline = await readFile('src/main/resources/static/offline.html', 'utf8');
const worker = await readFile('src/main/resources/static/service-worker.js', 'utf8');

function extractFunction(source, name) {
    const start = source.indexOf(`function ${name}(`);
    assert.notEqual(start, -1, `${name} exists`);
    const open = source.indexOf('{', start);
    let depth = 0;
    for (let index = open; index < source.length; index++) {
        if (source[index] === '{') depth++;
        if (source[index] === '}' && --depth === 0) return source.slice(start, index + 1);
    }
    throw new Error(`${name} has an unmatched block`);
}

const csrfFactory = new Function('document', `${extractFunction(queue, 'csrfHeader')}; return csrfHeader;`);
const csrf = csrfFactory({ cookie: 'XSRF-TOKEN=csrf%2Bvalue; Path=/' });
assert.deepEqual(csrf(), { 'X-XSRF-TOKEN': 'csrf+value' });
assert.throws(() => csrfFactory({ cookie: '' })(), /CSRF/);

const state = {
    scope: 'scope-a', revocationEpoch: 'epoch-a', documentGeneration: 4, quarantined: false,
    newRequestIds: new Set(['new-id']), restoredRequestIds: new Set(['restored-id'])
};
let permitValid = true;
const ticketIsCurrent = ticket => ticket.scope === state.scope && ticket.epoch === state.revocationEpoch
    && !state.quarantined;
const restorePermitIsCurrent = () => {
    if (!permitValid) state.restoredRequestIds.clear();
    return permitValid;
};
const helperFactory = new Function('state', 'ticketIsCurrent', 'restorePermitIsCurrent', 'readRevocationEpoch',
    `${extractFunction(queue, 'hasCurrentSendAuthorization')};${extractFunction(queue, 'restoreOperationIsCurrent')};`
    + 'return {hasCurrentSendAuthorization, restoreOperationIsCurrent};');
const helpers = helperFactory(state, ticketIsCurrent, restorePermitIsCurrent, () => state.revocationEpoch);
const ticket = { scope: 'scope-a', epoch: 'epoch-a' };
const record = id => ({ clientRequestId: id, userScope: 'scope-a', revocationEpoch: 'epoch-a' });
assert.equal(helpers.hasCurrentSendAuthorization(record('new-id'), ticket), true);
assert.equal(helpers.hasCurrentSendAuthorization(record('restored-id'), ticket), true);
permitValid = false;
assert.equal(helpers.hasCurrentSendAuthorization(record('restored-id'), ticket), false,
    '期限切れpermitは復元IDが同期消去された後も送信を拒否する');
assert.equal(helpers.hasCurrentSendAuthorization(record('unknown-id'), ticket), false);
assert.equal(helpers.hasCurrentSendAuthorization({ ...record('new-id'), revocationEpoch: 'old' }, ticket), false);
permitValid = true;
assert.equal(helpers.restoreOperationIsCurrent(4, 'epoch-a'), true);
state.documentGeneration++;
assert.equal(helpers.restoreOperationIsCurrent(4, 'epoch-a'), false,
    '非同期deleteScope中の撤回後は旧confirmを再開しない');
assert.equal(helpers.restoreOperationIsCurrent(5, 'old-epoch'), false);

const sendBody = queue.slice(queue.indexOf('async function sendClaimedRecord('), queue.indexOf('async function request(', queue.indexOf('async function sendClaimedRecord(')));
assert.ok(sendBody.indexOf('hasCurrentSendAuthorization(record, ticket)') < sendBody.indexOf('await fetch(record.path'),
    'fetch直前に新規IDまたは有効permit+復元IDを検証する');
const confirmBody = queue.slice(queue.indexOf('confirmRestore: async () =>'), queue.indexOf('canonicalHash: record =>'));
const deleteAt = confirmBody.indexOf('await deleteScope(oldScope)');
const postDeleteGuard = confirmBody.indexOf('restoreOperationIsCurrent(generation, operationEpoch)', deleteAt);
const unlockAt = confirmBody.indexOf('state.scope = newScope', deleteAt);
assert.ok(deleteAt >= 0 && postDeleteGuard > deleteAt && unlockAt > postDeleteGuard,
    'scope削除をawaitした後、generation・epoch・permitを再確認してからunlockする');

const inline = [...offline.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(match => match[1]).at(-1);
assert.ok(inline, 'offline.html recovery handler exists');
const elements = new Map();
function element(id) {
    if (!elements.has(id)) elements.set(id, {
        id, hidden: id === 'offline-drafts' || id === 'offline-confirm-restore', disabled: false,
        value: '', textContent: '', children: [], handlers: {}, dataset: {},
        addEventListener(type, handler) { this.handlers[type] = handler; },
        replaceChildren(...children) { this.children = children; this.textContent = ''; },
        appendChild(child) { this.children.push(child); this.textContent += child.textContent || ''; },
        append(...children) { this.children.push(...children); this.textContent += children.map(child => child.textContent || '').join(''); }
    });
    return elements.get(id);
}
const document = {
    readyState: 'loading',
    addEventListener(type, handler) { if (type === 'DOMContentLoaded') this.ready = handler; },
    getElementById: element,
    createElement: tag => ({ tag, textContent: '', children: [],
        appendChild(child) { this.children.push(child); this.textContent += child.textContent || ''; },
        append(...children) { this.children.push(...children); this.textContent += children.map(child => child.textContent || '').join(''); } })
};
let prepared = 0;
let confirmed = 0;
const windowEvents = {};
const window = { SES: { pwaQueue: {
    async prepareRestore(password) { assert.equal(password, 'secret'); prepared++; },
    async confirmRestore() { confirmed++; },
    async getOfflineDrafts() { return [{ screen: 'attendance', status: 'PENDING', payload: { workDate: '2026-10-07' } }]; }
} }, addEventListener(type, handler) { windowEvents[type] = handler; } };
vm.runInNewContext(inline, { document, window, navigator: { onLine: true }, Intl, Number, String, Object });
document.ready();
element('offline-restore-password').value = 'secret';
await element('offline-reauth').handlers.click();
assert.equal(prepared, 1);
assert.equal(element('offline-restore-password').value, '');
assert.equal(element('offline-confirm-restore').hidden, false);
assert.equal(element('offline-drafts').hidden, true, '再認証だけではDOMへ下書きを出さない');
await element('offline-confirm-restore').handlers.click();
assert.equal(confirmed, 1);
assert.equal(element('offline-drafts').hidden, false, '明示確認後だけDOMへ下書きを出す');
assert.match(element('offline-draft-list').textContent, /2026-10-07/);

const workerEvents = new Map();
const cacheData = new Map([['ses-pwa-shell-v1', new Map()], ['ses-pwa-shell-v2', new Map()], ['other-cache', new Map()]]);
const caches = {
    async open(name) {
        if (!cacheData.has(name)) cacheData.set(name, new Map());
        const entries = cacheData.get(name);
        return { async put(key, value) { entries.set(String(key), value); } };
    },
    async keys() { return [...cacheData.keys()]; },
    async delete(name) { return cacheData.delete(name); },
    async match(key) {
        for (const entries of cacheData.values()) if (entries.has(String(key))) return entries.get(String(key));
        return undefined;
    }
};
const self = {
    location: { origin: 'https://ses.test' }, clients: { async claim() {} },
    addEventListener(type, handler) { workerEvents.set(type, handler); }
};
let networkAvailable = true;
const fakeFetch = async () => {
    if (!networkAvailable) throw new TypeError('offline');
    return { ok: true, redirected: false, type: 'basic', clone() { return this; } };
};
vm.runInNewContext(worker, { self, caches, fetch: fakeFetch, URL, Promise });
let installPromise;
workerEvents.get('install')({ waitUntil(promise) { installPromise = promise; } });
await installPromise;
let activatePromise;
workerEvents.get('activate')({ waitUntil(promise) { activatePromise = promise; } });
await activatePromise;
assert.deepEqual([...cacheData.keys()].sort(), ['other-cache', 'ses-pwa-shell-v3'].sort(),
    'v3 worker activation purges v1/v2 shell caches and retains unrelated caches');
networkAvailable = false;
let offlineResponse;
workerEvents.get('fetch')({
    request: { url: 'https://ses.test/my/timesheet', method: 'GET', mode: 'navigate' },
    respondWith(promise) { offlineResponse = promise; }
});
assert.ok(await offlineResponse, 'offline navigation returns the cached shell page');
let apiIntercepted = false;
workerEvents.get('fetch')({
    request: { url: 'https://ses.test/api/my/pwa/attendance/daily', method: 'POST', mode: 'cors' },
    respondWith() { apiIntercepted = true; }
});
assert.equal(apiIntercepted, false, 'API mutation stays network-only');
console.log('NF04 PWA boundary checks passed');
