/* 要員PWAの最小draft/queue。API/portal/document/給与等のレスポンスは保存しない。 */
'use strict';

(() => {
    const DB_NAME = 'ses-pwa-self-service';
    const DB_VERSION = 2;
    const STORE = 'queue';
    const META_STORE = 'meta';
    const MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;
    const MAX_PAYLOAD_BYTES = 32 * 1024;
    const MAX_PAYLOAD_DEPTH = 8;
    const CLAIM_LEASE_MS = 30000;
    const FORBIDDEN_KEY = /(password|token|secret|payroll|salary|bank|mynumber|receipt|attachment|binary|blob|file|document)/i;
    const ALLOWED_PATHS = [
        /^\/api\/my\/pwa\/attendance\/daily$/,
        /^\/api\/my\/pwa\/timesheet\/daily$/,
        /^\/api\/my\/pwa\/expenses\/drafts(?:\/\d+)?$/,
        /^\/api\/my\/pwa\/change-requests\/drafts$/
    ];
    const state = {
        scope: null,
        paused: false,
        flushing: false,
        initialized: false,
        ready: null,
        inFlight: new Map(),
        resourceLocks: new Map(),
        revocationEpoch: null,
        quarantined: false,
        documentNonce: newDocumentNonce(),
        documentGeneration: 0,
        candidateRestoreScope: null,
        lockedDraftCount: 0,
        newRequestIds: new Set(),
        restoredRequestIds: new Set(),
        restoreProof: null,
        restoreScope: null,
        permitDeadline: null,
        permitServerTime: null,
        permitWallTime: null,
        permitMonotonicTime: null
    };

    const REVOCATION_KEY = 'ses_pwa_revocation_epoch';
    const SCOPE_EPOCH_KEY = 'ses_pwa_scope_epoch';
    const TAB_ID = newRequestId();

    function newDocumentNonce() {
        const bytes = new Uint8Array(16);
        crypto.getRandomValues(bytes);
        return btoa(String.fromCharCode(...bytes)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }

    function clearRestorePermit() {
        state.restoreProof = null;
        state.restoreScope = null;
        state.restoredRequestIds.clear();
        state.permitDeadline = null;
        state.permitServerTime = null;
        state.permitWallTime = null;
        state.permitMonotonicTime = null;
    }

    function clearDraftDom() {
        const conflictBox = document.getElementById('pwa-conflicts');
        if (conflictBox) conflictBox.replaceChildren();
        const panel = document.getElementById('pwa-queue-panel');
        if (panel) panel.hidden = true;
    }

    function restorePermitIsCurrent() {
        if (!state.permitDeadline || !state.restoreScope) return false;
        const elapsed = performance.now() - state.permitMonotonicTime;
        const wallElapsed = Date.now() - state.permitWallTime;
        if (elapsed < 0 || wallElapsed < 0 || Math.abs(elapsed - wallElapsed) > 5000) {
            state.quarantined = true;
            state.paused = true;
            clearRestorePermit();
            clearDraftDom();
            window.dispatchEvent(new CustomEvent('ses:pwa-revoked'));
            return false;
        }
        if (performance.now() >= state.permitDeadline) {
            const scope = state.scope;
            clearRestorePermit();
            state.candidateRestoreScope = scope;
            countLockedScope(scope).then(count => {
                if (scope === state.scope) {
                    state.lockedDraftCount = count;
                    render();
                }
            }).catch(() => undefined);
            clearDraftDom();
            window.dispatchEvent(new CustomEvent('ses:pwa-revoked'));
            return false;
        }
        return true;
    }

    function readRevocationEpoch() {
        let localEpoch = null;
        try {
            localEpoch = localStorage.getItem(REVOCATION_KEY);
        } catch (_) { /* cookieの撤回記録も照合する */ }
        try {
            const match = document.cookie.match(/(?:^|;\s*)ses_pwa_revocation_epoch=([^;]+)/);
            const cookieEpoch = match ? decodeURIComponent(match[1]) : null;
            if (!localEpoch && !cookieEpoch) return 'uninitialized';
            if (!localEpoch || !cookieEpoch || localEpoch !== cookieEpoch) return 'unverified';
            return localEpoch;
        } catch (_) { /* 保存状態を検証できない場合はfail closedにする */ }
        return 'unverified';
    }

    function newRevocationEpoch() {
        try { return crypto.randomUUID(); }
        catch (_) { return `${newRequestId()}-${Math.random().toString(36).slice(2)}`; }
    }

    function persistRevocationEpoch(epoch) {
        try { localStorage.setItem(REVOCATION_KEY, epoch); } catch (_) { /* cookie側の記録を照合する */ }
        try { document.cookie = `ses_pwa_revocation_epoch=${encodeURIComponent(epoch)}; Max-Age=31536000; Path=/; SameSite=Strict`; }
        catch (_) { /* localStorage側の記録を照合する */ }
        return readRevocationEpoch() === epoch;
    }

    function scopeTicket() {
        return { scope: state.scope, epoch: readRevocationEpoch() };
    }

    function ticketIsCurrent(ticket) {
        return Boolean(ticket && !state.quarantined && !state.paused
            && ticket.scope && ticket.scope === state.scope
            && ticket.epoch === readRevocationEpoch());
    }

    function acceptRevocation(epoch) {
        state.documentGeneration++;
        state.newRequestIds.clear();
        state.candidateRestoreScope = null;
        state.lockedDraftCount = 0;
        clearRestorePermit();
        clearDraftDom();
        window.dispatchEvent(new CustomEvent('ses:pwa-revoked'));
        state.revocationEpoch = epoch || newRevocationEpoch();
        state.scope = null;
        state.paused = true;
        state.quarantined = true;
        deleteAll().catch(() => undefined);
        render();
    }

    function quarantineDocument() {
        state.documentGeneration++;
        state.newRequestIds.clear();
        state.candidateRestoreScope = null;
        state.lockedDraftCount = 0;
        clearRestorePermit();
        state.scope = null;
        state.paused = true;
        state.quarantined = true;
        clearDraftDom();
        window.dispatchEvent(new CustomEvent('ses:pwa-revoked'));
        render();
    }

    function broadcastRevocation(epoch) {
        try {
            if ('BroadcastChannel' in window) {
                const channel = new BroadcastChannel('ses-pwa-revocation');
                channel.postMessage({ type: 'REVOKE', epoch });
                channel.close();
            }
        } catch (_) { /* storage eventを他tabへの通知手段として維持する */ }
    }

    async function revokeScope() {
        state.documentGeneration++;
        state.newRequestIds.clear();
        state.candidateRestoreScope = null;
        state.lockedDraftCount = 0;
        clearRestorePermit();
        clearDraftDom();
        window.dispatchEvent(new CustomEvent('ses:pwa-revoked'));
        const epoch = newRevocationEpoch();
        state.revocationEpoch = epoch;
        state.scope = null;
        state.paused = true;
        state.quarantined = true;
        const durableEpoch = persistRevocationEpoch(epoch);
        try { localStorage.removeItem('ses_pwa_user_scope'); } catch (_) { /* 現在tabは引き続き隔離する */ }
        try { localStorage.removeItem(SCOPE_EPOCH_KEY); } catch (_) { /* epoch不一致で隔離する */ }
        broadcastRevocation(epoch);
        try {
            await withStore('readwrite', store => store.clear());
            await withStore('readwrite', store => store.put({ key: 'revocationEpoch', value: epoch }), META_STORE);
            return { cleared: durableEpoch, epoch };
        } catch (_) {
            // queue DBのclearに失敗しても、DB外の撤回記録で旧scopeを隔離する。
            return { cleared: false, epoch };
        }
    }

    function openDb() {
        return new Promise((resolve, reject) => {
            if (!window.indexedDB) return reject(new Error('このブラウザではoffline保存を利用できません'));
            const request = indexedDB.open(DB_NAME, DB_VERSION);
            request.onupgradeneeded = event => {
                const db = request.result;
                const tx = request.transaction;
                if (!db.objectStoreNames.contains(STORE)) {
                    const store = db.createObjectStore(STORE, { keyPath: 'clientRequestId' });
                    store.createIndex('userScope', 'userScope', { unique: false });
                    store.createIndex('status', 'status', { unique: false });
                    store.createIndex('createdAt', 'createdAt', { unique: false });
                }
                if (!db.objectStoreNames.contains(META_STORE)) db.createObjectStore(META_STORE, { keyPath: 'key' });
                if (event.oldVersion < 2 && db.objectStoreNames.contains(STORE)) {
                    const cursorRequest = tx.objectStore(STORE).openCursor();
                    cursorRequest.onsuccess = () => {
                        const cursor = cursorRequest.result;
                        if (!cursor) return;
                        const record = cursor.value;
                        if (!record.revocationEpoch) {
                            const migrationEpoch = readRevocationEpoch();
                            // epoch契約導入前のrecordは未撤回初期値(0)のときだけ引き継ぐ。
                            record.revocationEpoch = migrationEpoch === '0' ? '0' : 'legacy-revoked';
                        }
                        if (record.conflict || JSON.stringify(record).length > MAX_PAYLOAD_BYTES) {
                            record.status = 'ERROR';
                            record.error = '安全な競合情報へ更新できないため、この下書きは破棄して再入力してください。';
                            record.payload = null;
                            record.payloadHash = null;
                            delete record.conflict;
                            cursor.update(record);
                        }
                        cursor.continue();
                    };
                }
            };
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error || new Error('offline保存を初期化できません'));
        });
    }

    function withStore(mode, operation, storeName = STORE) {
        return openDb().then(db => new Promise((resolve, reject) => {
            let result;
            let failed = false;
            let tx;
            try {
                tx = db.transaction(storeName, mode);
                result = operation(tx.objectStore(storeName));
            } catch (error) {
                failed = true;
                db.close();
                reject(error);
                return;
            }
            tx.oncomplete = () => { db.close(); if (!failed) resolve(result); };
            tx.onerror = () => { failed = true; db.close(); reject(tx.error || new Error('offline保存に失敗しました')); };
            tx.onabort = () => { failed = true; db.close(); reject(tx.error || new Error('offline保存を中断しました')); };
        }));
    }

    function put(record, ticket) {
        if (ticket && (!ticketIsCurrent(ticket) || record.userScope !== ticket.scope
                || record.revocationEpoch !== ticket.epoch)) {
            return Promise.resolve(false);
        }
        return withStore('readwrite', store => {
            if (ticket && (!ticketIsCurrent(ticket) || record.userScope !== ticket.scope
                    || record.revocationEpoch !== ticket.epoch)) return false;
            store.put(record);
            return record;
        });
    }

    function remove(clientRequestId) {
        return withStore('readwrite', store => { store.delete(clientRequestId); });
    }

    async function putClaimed(record, ticket, claim) {
        if (!ticketIsCurrent(ticket) || record.userScope !== ticket.scope
                || record.revocationEpoch !== ticket.epoch) return false;
        const db = await openDb();
        try {
            return await new Promise((resolve, reject) => {
                const tx = db.transaction([STORE, META_STORE], 'readwrite');
                const leaseRequest = tx.objectStore(META_STORE).get(claim.key);
                let wrote = false;
                leaseRequest.onsuccess = () => {
                    const lease = leaseRequest.result;
                    if (ticketIsCurrent(ticket) && lease && lease.ownerToken === claim.token
                            && lease.epoch === ticket.epoch && lease.expiresAt >= now()) {
                        tx.objectStore(STORE).put(record);
                        wrote = true;
                    }
                };
                tx.oncomplete = () => resolve(wrote);
                tx.onerror = tx.onabort = () => reject(tx.error || new Error('offline保存に失敗しました'));
            });
        } finally { db.close(); }
    }

    async function removeClaimed(clientRequestId, ticket, claim) {
        if (!ticketIsCurrent(ticket)) return false;
        const db = await openDb();
        try {
            return await new Promise((resolve, reject) => {
                const tx = db.transaction([STORE, META_STORE], 'readwrite');
                const leaseRequest = tx.objectStore(META_STORE).get(claim.key);
                let removed = false;
                leaseRequest.onsuccess = () => {
                    const lease = leaseRequest.result;
                    if (ticketIsCurrent(ticket) && lease && lease.ownerToken === claim.token
                            && lease.epoch === ticket.epoch && lease.expiresAt >= now()) {
                        tx.objectStore(STORE).delete(clientRequestId);
                        removed = true;
                    }
                };
                tx.oncomplete = () => resolve(removed);
                tx.onerror = tx.onabort = () => reject(tx.error || new Error('offline削除に失敗しました'));
            });
        } finally { db.close(); }
    }

    function readRestoreScope(scope) {
        return withStore('readonly', store => {
            const request = store.index('userScope').getAll(scope);
            request.onsuccess = () => { request._pwaResult = request.result || []; };
            return request;
        }).then(request => request._pwaResult || []);
    }

    function readById(clientRequestId) {
        return withStore('readonly', store => {
            const request = store.get(clientRequestId);
            request.onsuccess = () => { request._pwaResult = request.result || null; };
            return request;
        }).then(request => request._pwaResult || null);
    }

    async function readAllowedRecords() {
        const allowedIds = new Set([...state.newRequestIds, ...state.restoredRequestIds]);
        const records = await Promise.all([...allowedIds].map(readById));
        return scopeRecords(records.filter(Boolean));
    }

    function countScope(scope) {
        if (!scope) return Promise.resolve(0);
        return withStore('readonly', store => {
            const request = store.index('userScope').count(IDBKeyRange.only(scope));
            request.onsuccess = () => { request._pwaResult = request.result || 0; };
            return request;
        }).then(request => request._pwaResult || 0);
    }

    function countLockedScope(scope) {
        if (!scope) return Promise.resolve(0);
        const allowedIds = new Set([...state.newRequestIds, ...state.restoredRequestIds]);
        return withStore('readonly', store => {
            const request = store.index('userScope').openKeyCursor(IDBKeyRange.only(scope));
            let count = 0;
            request.onsuccess = () => {
                const cursor = request.result;
                if (!cursor) { request._pwaResult = count; return; }
                if (!allowedIds.has(cursor.primaryKey)) count++;
                cursor.continue();
            };
            return request;
        }).then(request => request._pwaResult || 0);
    }

    function deleteAll() {
        return withStore('readwrite', store => { store.clear(); });
    }

    async function deleteScope(scope) {
        if (!scope) return;
        return withStore('readwrite', store => {
            const request = store.index('userScope').openKeyCursor(IDBKeyRange.only(scope));
            request.onsuccess = () => {
                const cursor = request.result;
                if (!cursor) return;
                cursor.delete();
                cursor.continue();
            };
            return request;
        });
    }

    async function rebindScope(oldScope, newScope, allowedRequestIds = null, guard = () => true) {
        return withStore('readwrite', store => {
            const ids = allowedRequestIds ? [...allowedRequestIds] : [];
            if (!guard()) { store.transaction.abort(); return null; }
            ids.forEach(id => {
                const request = store.get(id);
                request.onsuccess = () => {
                    if (!guard()) { store.transaction.abort(); return; }
                    const record = request.result;
                    if (record && record.userScope === oldScope) {
                        record.userScope = newScope;
                        store.put(record);
                    }
                };
            });
            return null;
        });
    }

    function canonical(value) {
        if (Array.isArray(value)) return value.map(canonical);
        if (value !== null && typeof value === 'object') {
            const result = {};
            Object.keys(value).sort().forEach(key => { result[key] = canonical(value[key]); });
            return result;
        }
        return value;
    }

    async function sha256(value) {
        const bytes = new TextEncoder().encode(value);
        const digest = await crypto.subtle.digest('SHA-256', bytes);
        return Array.from(new Uint8Array(digest)).map(byte => byte.toString(16).padStart(2, '0')).join('');
    }

    function commandFor(record) {
        return {
            baseVersion: Number(record.baseVersion),
            month: record.month || null,
            operation: operationFor(record),
            payload: record.payload,
            screen: record.screen
        };
    }

    async function commandHash(record) {
        return sha256(JSON.stringify(canonical(commandFor(record))));
    }

    function operationFor(record) {
        return `${record.screen}:${String(record.method).toUpperCase()}:${record.path}`;
    }

    function newRequestId() {
        if (crypto.randomUUID) return crypto.randomUUID();
        return 'pwa-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
    }

    function validPath(path) {
        return typeof path === 'string' && ALLOWED_PATHS.some(pattern => pattern.test(path));
    }

    function validMethod(screen, path, method) {
        if (screen === 'attendance' && path === '/api/my/pwa/attendance/daily') {
            return method === 'POST' || method === 'DELETE';
        }
        if (screen === 'timesheet' && path === '/api/my/pwa/timesheet/daily') {
            return method === 'POST' || method === 'DELETE';
        }
        if (screen === 'expense' && path === '/api/my/pwa/expenses/drafts') return method === 'POST';
        if (screen === 'expense' && /^\/api\/my\/pwa\/expenses\/drafts\/\d+$/.test(path)) {
            return method === 'PUT';
        }
        return screen === 'change-request'
                && path === '/api/my/pwa/change-requests/drafts'
                && method === 'POST';
    }

    function isPlainObject(value) {
        if (value === null || typeof value !== 'object') return false;
        const prototype = Object.getPrototypeOf(value);
        return prototype === Object.prototype || prototype === null;
    }

    function rejectPayload(message) {
        throw new Error(`offline保存できないpayloadです: ${message}`);
    }

    function validateSafeValue(value, path, depth) {
        if (depth > MAX_PAYLOAD_DEPTH) rejectPayload('階層が深すぎます');
        if (value === null || typeof value === 'string' || typeof value === 'boolean') {
            if (typeof value === 'string' && value.length > 8192) rejectPayload(`${path}が長すぎます`);
            return;
        }
        if (typeof value === 'number') {
            if (!Number.isFinite(value)) rejectPayload(`${path}が不正です`);
            return;
        }
        if (typeof value !== 'object') rejectPayload(`${path}の型は保存できません`);
        if ((typeof Blob !== 'undefined' && value instanceof Blob)
            || (typeof File !== 'undefined' && value instanceof File)
            || (typeof ArrayBuffer !== 'undefined' && value instanceof ArrayBuffer)
            || (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView(value))) {
            rejectPayload(`${path}にbinaryを含められません`);
        }
        if (Array.isArray(value)) {
            value.forEach((item, index) => validateSafeValue(item, `${path}[${index}]`, depth + 1));
            return;
        }
        if (!isPlainObject(value)) rejectPayload(`${path}の型は保存できません`);
        Object.keys(value).forEach(key => {
            if (FORBIDDEN_KEY.test(key)) rejectPayload(`${path}.${key}は保存できません`);
            validateSafeValue(value[key], `${path}.${key}`, depth + 1);
        });
    }

    function assertExactKeys(value, allowed, path) {
        if (!isPlainObject(value)) rejectPayload(`${path}はobjectが必要です`);
        const unknown = Object.keys(value).filter(key => !allowed.includes(key));
        if (unknown.length) rejectPayload(`${path}の項目がoffline許可外です`);
    }

    function validatePayload(spec, method, payload) {
        if (!validMethod(spec.screen, spec.path, method)) {
            rejectPayload('screen/method/pathの組み合わせが不正です');
        }
        validateSafeValue(payload, 'payload', 0);
        const path = spec.path;
        if (spec.screen === 'attendance' && path === '/api/my/pwa/attendance/daily') {
            assertExactKeys(payload, method === 'DELETE'
                ? ['month', 'workDate']
                : ['workDate', 'clockIn', 'clockOut', 'breaks', 'breakMinutes', 'workType', 'workplaceType', 'remarks'], 'attendance');
            if (method === 'POST' && payload.breaks !== undefined && payload.breaks !== null) {
                if (!Array.isArray(payload.breaks)) rejectPayload('attendance.breaksが不正です');
                payload.breaks.forEach(item => assertExactKeys(item, ['startTime', 'endTime'], 'attendance.breaks'));
            }
        } else if (spec.screen === 'timesheet' && path === '/api/my/pwa/timesheet/daily') {
            assertExactKeys(payload, method === 'DELETE'
                ? ['contractId', 'workMonth', 'workDate']
                : ['contractId', 'workMonth', 'workDate', 'startTime', 'endTime', 'breakMinutes', 'remarks'], 'timesheet');
        } else if (spec.screen === 'expense' && /^\/api\/my\/pwa\/expenses\/drafts(?:\/\d+)?$/.test(path)) {
            assertExactKeys(payload, method === 'POST'
                ? ['expenseDate', 'category', 'amount', 'customerId', 'projectId', 'description']
                : ['id', 'expenseDate', 'category', 'amount', 'customerId', 'projectId', 'description'], 'expense');
            if (method === 'PUT') {
                const pathId = path.match(/\/(\d+)$/)[1];
                if (payload.id === undefined || String(payload.id) !== pathId) {
                    rejectPayload('expenseのURL IDとpayload.idが一致しません');
                }
            }
        } else if (spec.screen === 'change-request' && path === '/api/my/pwa/change-requests/drafts') {
            assertExactKeys(payload, ['requestType', 'payload', 'reason'], 'change-request');
            const requestType = payload.requestType;
            const nested = payload.payload;
            if (requestType === 'profile.change') {
                assertExactKeys(nested, ['fullName', 'fullNameKana', 'initialName', 'gender', 'birthDate', 'nationality',
                    'nearestStation', 'prefecture', 'railwayCompany', 'expectedUnitPrice', 'availableDate',
                    'experienceYears', 'japaneseLevel', 'resumeSummary', 'email', 'phone'], 'change-request.payload');
            } else if (requestType === 'skill.change') {
                assertExactKeys(nested, ['skills'], 'change-request.payload');
                if (!Array.isArray(nested.skills)) rejectPayload('change-request.skillsが不正です');
                nested.skills.forEach(item => assertExactKeys(item, ['skillId', 'proficiency', 'experienceYears'], 'change-request.skills'));
            } else if (requestType === 'career.change') {
                assertExactKeys(nested, ['careers'], 'change-request.payload');
                if (!Array.isArray(nested.careers)) rejectPayload('change-request.careersが不正です');
                nested.careers.forEach(item => assertExactKeys(item, ['periodFrom', 'periodTo', 'projectName', 'clientIndustry',
                    'role', 'description', 'techStack', 'teamSize'], 'change-request.careers'));
            } else {
                rejectPayload('change-request.requestTypeが不正です');
            }
        } else {
            rejectPayload('screen/method/pathの組み合わせが不正です');
        }
        const bytes = new TextEncoder().encode(JSON.stringify(canonical(payload)).replace(/\u2028|\u2029/g, '')).byteLength;
        if (bytes > MAX_PAYLOAD_BYTES) rejectPayload('payloadが大きすぎます');
    }

    function now() { return Date.now(); }

    function persistedScope() {
        try { return localStorage.getItem('ses_pwa_user_scope'); } catch (_) { return null; }
    }

    function persistScope(scope) {
        try {
            if (scope) localStorage.setItem('ses_pwa_user_scope', scope);
            else localStorage.removeItem('ses_pwa_user_scope');
        } catch (_) { /* private browsingではメモリ上だけで扱う */ }
    }

    function sanitizeConflict(record, data) {
        const source = data && typeof data === 'object' ? data : {};
        const changeRequest = record.screen === 'change-request';
        const allowed = changeRequest ? ((record.payload || {}).payload || {}) : (record.payload || {});
        const fieldNames = new Set(Object.keys(allowed));
        Object.values(allowed).forEach(value => {
            if (Array.isArray(value)) Object.keys(value[0] || {}).forEach(key => fieldNames.add(key));
            else if (value && typeof value === 'object') Object.keys(value).forEach(key => fieldNames.add(key));
        });
        const safeValue = (value, depth = 0) => {
            if (depth > MAX_PAYLOAD_DEPTH) return null;
            if (typeof value === 'string') return value.slice(0, 8192);
            if (value === null || typeof value === 'boolean' || typeof value === 'number') return value;
            if (Array.isArray(value)) return value.slice(0, 100).map(item => safeValue(item, depth + 1));
            if (!value || typeof value !== 'object') return null;
            const result = {};
            Object.keys(value).filter(key => fieldNames.has(key) && !FORBIDDEN_KEY.test(key))
                .forEach(key => { result[key] = safeValue(value[key], depth + 1); });
            return result;
        };
        const project = value => {
            if (!value || typeof value !== 'object' || Array.isArray(value)) return null;
            const result = {};
            Object.keys(allowed).forEach(key => {
                if (!Object.prototype.hasOwnProperty.call(value, key)) return;
                const allowedValue = allowed[key];
                if (Array.isArray(allowedValue)) {
                    const nestedKeys = Object.keys(allowedValue[0] || {});
                    result[key] = Array.isArray(value[key]) ? value[key].slice(0, 100).map(item => {
                        if (!item || typeof item !== 'object' || Array.isArray(item)) return null;
                        const projected = {};
                        nestedKeys.forEach(nestedKey => {
                            if (Object.prototype.hasOwnProperty.call(item, nestedKey)) projected[nestedKey] = safeValue(item[nestedKey]);
                        });
                        return projected;
                    }).filter(Boolean) : [];
                } else if (allowedValue && typeof allowedValue === 'object') {
                    const nested = {};
                    Object.keys(allowedValue).forEach(nestedKey => {
                        if (Object.prototype.hasOwnProperty.call(value[key] || {}, nestedKey)) nested[nestedKey] = safeValue(value[key][nestedKey]);
                    });
                    result[key] = nested;
                } else result[key] = safeValue(value[key]);
            });
            return result;
        };
        const fields = Array.isArray(source.fields) ? source.fields.filter(item => item && fieldNames.has(item.name))
            .map(item => ({ name: item.name, serverValue: safeValue(item.serverValue), clientValue: safeValue(item.clientValue) })) : [];
        const sanitized = {
            type: typeof source.type === 'string' ? source.type.slice(0, 40) : 'CONFLICT',
            resource: record.screen,
            resourceId: Number.isFinite(Number(source.resourceId)) ? Number(source.resourceId) : null,
            serverVersion: Number.isFinite(Number(source.serverVersion)) ? Number(source.serverVersion) : undefined,
            clientBaseVersion: record.baseVersion,
            serverRefreshAt: typeof source.serverRefreshAt === 'string' ? source.serverRefreshAt.slice(0, 40) : undefined,
            server: project(source.server),
            client: project(source.client) || project(allowed),
            fields
        };
        if (new TextEncoder().encode(JSON.stringify(sanitized)).byteLength > MAX_PAYLOAD_BYTES) {
            return { type: sanitized.type, resource: sanitized.resource, serverVersion: sanitized.serverVersion,
                clientBaseVersion: sanitized.clientBaseVersion };
        }
        return sanitized;
    }

    async function bootstrapScope() {
        let bootstrapEpoch = readRevocationEpoch();
        const oldScope = state.scope || persistedScope();
        if (!navigator.onLine) {
            if (!oldScope || bootstrapEpoch === 'uninitialized' || bootstrapEpoch === 'unverified'
                    || localStorage.getItem(SCOPE_EPOCH_KEY) !== bootstrapEpoch) {
                state.quarantined = true;
                throw new Error('logout後の下書きは復元できません');
            }
            state.candidateRestoreScope = oldScope;
            state.scope = oldScope;
            state.revocationEpoch = bootstrapEpoch;
            state.lockedDraftCount = await countLockedScope(state.candidateRestoreScope);
            if (!state.scope) throw new Error('offlineで利用するには一度オンラインでログインしてください');
            return state.scope;
        }
        try {
            const contextHeaders = { 'Accept': 'application/json' };
            if (oldScope) contextHeaders['X-User-Scope'] = oldScope;
            const response = await fetch('/api/my/session-context', {
                method: 'GET', cache: 'no-store', credentials: 'same-origin',
                headers: contextHeaders
            });
            if (response.status === 401) throw Object.assign(new Error('Session timeout'), { sessionExpired: true });
            if (!response.ok) throw new Error('ユーザーscopeを確認できません');
            const result = await response.json();
            if (bootstrapEpoch !== readRevocationEpoch() || state.quarantined) {
                throw new Error('logout後のscope再確認が必要です');
            }
            const scope = result && result.data && result.data.userScope;
            if (!scope || typeof scope !== 'string') throw new Error('ユーザーscopeが不正です');
            if (bootstrapEpoch === 'uninitialized' || bootstrapEpoch === 'unverified') {
                bootstrapEpoch = newRevocationEpoch();
                if (!persistRevocationEpoch(bootstrapEpoch)) throw new Error('撤回状態を永続化できません');
            }
            const preserveQueue = Boolean(result && result.data && result.data.preserveQueue);
            if (!preserveQueue) state.candidateRestoreScope = null;
            else if (oldScope !== scope || !state.candidateRestoreScope) state.candidateRestoreScope = oldScope;
            if (oldScope && oldScope !== scope) {
                // 通常のcontext GETでは旧draftを再束縛・読取対象へ移さない。
                state.scope = null;
                if (!preserveQueue) await deleteScope(oldScope);
            }
            state.scope = scope;
            state.revocationEpoch = bootstrapEpoch;
            persistScope(scope);
            try { localStorage.setItem(SCOPE_EPOCH_KEY, readRevocationEpoch()); } catch (_) {}
            state.quarantined = false;
            state.paused = false;
            state.lockedDraftCount = await countLockedScope(state.candidateRestoreScope);
            return scope;
        } catch (error) {
            if (error.sessionExpired) {
                state.paused = true;
                if (window.SES && SES.pwa) SES.pwa.sessionExpired();
                throw error;
            }
            // オンラインでcontextを再検証できない場合は、古いscopeで送信しない。
            state.paused = true;
            throw error;
        }
    }

    async function cleanupExpired() {
        const threshold = now() - MAX_AGE_MS;
        await withStore('readwrite', store => {
            const request = store.index('createdAt').openKeyCursor(IDBKeyRange.upperBound(threshold, true));
            request.onsuccess = () => {
                const cursor = request.result;
                if (!cursor) return;
                cursor.delete();
                cursor.continue();
            };
            return request;
        });
    }

    function scopeRecords(records) {
        const epoch = readRevocationEpoch();
        const permit = restorePermitIsCurrent();
        return records.filter(record => record.userScope === state.scope && record.revocationEpoch === epoch
            && (state.newRequestIds.has(record.clientRequestId)
                || (permit && state.restoredRequestIds.has(record.clientRequestId))));
    }

    function safeJson(value) {
        try { return JSON.stringify(value, null, 2); } catch (_) { return String(value); }
    }

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>'"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char]));
    }

    async function render() {
        let records = [];
        try { records = await readAllowedRecords(); } catch (_) { return; }
        const pending = records.filter(record => record.status === 'PENDING');
        const conflicts = records.filter(record => record.status === 'CONFLICT');
        const errors = records.filter(record => record.status === 'ERROR');
        const panel = document.getElementById('pwa-queue-panel');
        const status = document.getElementById('pwa-queue-status');
        const conflictBox = document.getElementById('pwa-conflicts');
        const restoreLink = document.getElementById('pwa-restore-link');
        if (panel && status && conflictBox) {
            panel.hidden = pending.length === 0 && conflicts.length === 0 && errors.length === 0
                && state.lockedDraftCount === 0;
            if (restoreLink) restoreLink.hidden = state.lockedDraftCount === 0;
            if (state.quarantined) status.textContent = '下書きはロックされています。オンラインで再認証し、明示的に復元してください。';
            else if (state.paused) status.textContent = 'セッション期限切れ。再認証後に同じユーザーで同期を再開します。';
            else if (state.lockedDraftCount) status.textContent = `ロック中の下書き ${state.lockedDraftCount} 件。再認証後に明示的に復元できます。`;
            else if (!navigator.onLine && pending.length) status.textContent = `オフライン: 未送信 ${pending.length} 件`;
            else if (state.flushing) status.textContent = `同期中: ${pending.length} 件`;
            else if (pending.length) status.textContent = `未送信 ${pending.length} 件`;
            else if (conflicts.length) status.textContent = `競合 ${conflicts.length} 件。サーバー版と端末版を確認してください。`;
            else if (errors.length) status.textContent = `同期停止 ${errors.length} 件。再認証または破棄を確認してください。`;
            else status.textContent = '';
            conflictBox.innerHTML = conflicts.concat(errors).map(renderConflict).join('');
        }
        if (window.SES && SES.pwa) {
            const nextState = state.paused || errors.length ? 'error'
                : conflicts.length ? 'conflict'
                    : state.flushing ? 'syncing'
                        : pending.length ? 'pending' : (navigator.onLine ? 'online' : 'offline');
            SES.pwa.setStatus(nextState);
        }
    }

    function renderConflict(record) {
        if (record.status === 'ERROR') {
            return `<div class="pwa-queue-conflict" data-request-id="${escapeHtml(record.clientRequestId)}">` +
                `<div class="fw-bold">同期エラー: ${escapeHtml(record.screen)} / ${escapeHtml(record.month || '')}</div>` +
                `<div class="small mt-1">${escapeHtml(record.error || '同期できません')}</div>` +
                `<button type="button" class="btn btn-sm btn-outline-danger" data-pwa-discard="${escapeHtml(record.clientRequestId)}">破棄して再入力</button>` +
                `</div>`;
        }
        const conflict = record.conflict || {};
        const title = record.status === 'CONFLICT' ? '競合' : '同期エラー';
        const server = conflict.server === undefined ? { hash: conflict.serverHash } : conflict.server;
        const client = conflict.client === undefined ? record.payload : conflict.client;
        const fields = Array.isArray(conflict.fields) ? conflict.fields : [];
        const fieldMarkup = fields.length
            ? `<div class="small mt-1">差分項目</div><ul class="small">${fields.map(field =>
                `<li><code>${escapeHtml(field.name)}</code>: server=${escapeHtml(safeJson(field.serverValue))}, client=${escapeHtml(safeJson(field.clientValue))}</li>`).join('')}</ul>`
            : '';
        const refreshed = conflict.serverRefreshAt
            ? `<div class="small text-muted">server再取得: ${escapeHtml(conflict.serverRefreshAt)} / version ${escapeHtml(conflict.serverVersion)}</div>` : '';
        const reapply = conflict.serverVersion === undefined ? ''
            : `<button type="button" class="btn btn-sm btn-outline-primary me-1" data-pwa-reapply="${escapeHtml(record.clientRequestId)}">server版を確認して再適用</button>`;
        return `<div class="pwa-queue-conflict" data-request-id="${escapeHtml(record.clientRequestId)}">` +
            `<div class="fw-bold">${title}: ${escapeHtml(record.screen)} / ${escapeHtml(record.month || '')}</div>` +
            `<div class="small text-muted">clientRequestId: ${escapeHtml(record.clientRequestId)}</div>` +
            `<div class="small text-muted">resource: ${escapeHtml(conflict.resource || record.resourceKey || '')} / ${escapeHtml(conflict.resourceId || '')}</div>` +
            refreshed + fieldMarkup +
            `<div class="small mt-1">サーバー版</div><pre>${escapeHtml(safeJson(server))}</pre>` +
            `<div class="small">端末版</div><pre>${escapeHtml(safeJson(client))}</pre>` +
            `<button type="button" class="btn btn-sm btn-outline-secondary me-1" data-pwa-refresh="${escapeHtml(record.clientRequestId)}">server版を再取得</button>` +
            reapply +
            `<button type="button" class="btn btn-sm btn-outline-danger" data-pwa-discard="${escapeHtml(record.clientRequestId)}">端末版を破棄</button>` +
            `</div>`;
    }

    function setPendingStatus() { render(); }

    async function updateFollowingVersions(record, responseData, ticket, claim) {
        if (ticket && !ticketIsCurrent(ticket)) return;
        const version = responseData && Number.isInteger(Number(responseData.version))
            ? Number(responseData.version) : null;
        if (version === null) return;
        const records = await readAllowedRecords();
        await Promise.all(records
            .filter(candidate => candidate.status === 'PENDING'
                && candidate.resourceKey === record.resourceKey
                && (!ticket || candidate.revocationEpoch === ticket.epoch)
                && candidate.createdAt >= record.createdAt
                && candidate.baseVersion === record.baseVersion)
            .map(async candidate => {
                candidate.baseVersion = version;
                candidate.payloadHash = await commandHash(candidate);
                if (ticket && !ticketIsCurrent(ticket)) return false;
                return claim ? putClaimed(candidate, ticket, claim) : put(candidate, ticket);
            }));
    }

    async function claimAggregate(resourceKey, ticket) {
        const key = `aggregate:${resourceKey}`;
        const expiresAt = now() + CLAIM_LEASE_MS;
        const token = newRequestId();
        const db = await openDb();
        try {
            return await new Promise((resolve, reject) => {
                const tx = db.transaction(META_STORE, 'readwrite');
                const store = tx.objectStore(META_STORE);
                let claimed = false;
                const request = store.get(key);
                request.onsuccess = () => {
                    if (!ticketIsCurrent(ticket)) return;
                    const current = request.result;
                    if (!current || current.epoch !== ticket.epoch || current.expiresAt < now()) {
                        store.put({ key, owner: TAB_ID, ownerToken: token, epoch: ticket.epoch, expiresAt });
                        claimed = { key, token, resourceKey };
                    }
                };
                tx.oncomplete = () => resolve(claimed);
                tx.onerror = tx.onabort = () => reject(tx.error || new Error('同期claimを取得できません'));
            });
        } finally { db.close(); }
    }

    async function releaseAggregate(claim) {
        try {
            const db = await openDb();
            await new Promise((resolve, reject) => {
                const tx = db.transaction(META_STORE, 'readwrite');
                const store = tx.objectStore(META_STORE);
                const request = store.get(claim.key);
                request.onsuccess = () => {
                    if (request.result && request.result.ownerToken === claim.token) store.delete(claim.key);
                };
                tx.oncomplete = resolve;
                tx.onerror = tx.onabort = () => reject(tx.error);
            });
            db.close();
        } catch (_) { /* lease失効後はserverのidempotency/CASを最終境界とする */ }
    }

    async function markConflict(record, data, ticket, claim) {
        record.status = 'CONFLICT';
        record.conflict = sanitizeConflict(record, data);
        if (await putClaimed(record, ticket, claim) === false) return { skipped: true };
        await render();
        return { conflict: true, data: record.conflict, clientRequestId: record.clientRequestId };
    }

    function conflictRefreshPath(record) {
        const month = encodeURIComponent(record.month || '');
        if (record.screen === 'attendance') return `/api/my/attendance?month=${month}`;
        if (record.screen === 'timesheet') return `/api/my/timesheet?month=${month}`;
        if (record.screen === 'expense') return '/api/my/expenses?current=1&size=100';
        if (record.screen === 'change-request') return '/api/my/profile';
        return null;
    }

    function refreshedVersion(data, record) {
        const root = data && data.data !== undefined ? data.data : data;
        if (record.screen === 'attendance' && root && Array.isArray(root.months)) {
            const row = root.months.find(item => item && String(item.workMonth || '').startsWith(String(record.month || '')));
            return row && Number.isInteger(Number(row.version)) ? Number(row.version) : 0;
        }
        if (record.screen === 'timesheet' && root && Array.isArray(root.rows)) {
            const row = root.rows.find(item => item && String(item.contractId) === String(record.payload.contractId));
            return row && Number.isInteger(Number(row.version)) ? Number(row.version) : 0;
        }
        if (record.screen === 'expense' && root && root.records && Array.isArray(root.records)) {
            const row = root.records.find(item => item && String(item.id) === String(record.payload.id));
            return row && Number.isInteger(Number(row.version)) ? Number(row.version) : 0;
        }
        if (record.screen === 'change-request' && root && Number.isInteger(Number(root.version))) return Number(root.version);
        return null;
    }

    function refreshedSnapshot(data, record) {
        const root = data && data.data !== undefined ? data.data : data;
        if (!root) return null;
        if (record.screen === 'attendance' && Array.isArray(root.months)) {
            const row = root.months.find(item => item && String(item.workMonth || '').startsWith(String(record.month || '')));
            if (!row) return { exists: false, version: 0 };
            const daily = Array.isArray(row.days)
                ? row.days.find(item => item && String(item.workDate) === String(record.payload.workDate))
                : null;
            return daily ? Object.assign({}, daily, { exists: true, version: Number(row.version || 0) })
                : { exists: false, version: Number(row.version || 0) };
        }
        if (record.screen === 'timesheet' && Array.isArray(root.rows)) {
            const row = root.rows.find(item => item && String(item.contractId) === String(record.payload.contractId));
            if (!row) return { exists: false, version: 0 };
            const daily = Array.isArray(row.dailies)
                ? row.dailies.find(item => item && String(item.workDate) === String(record.payload.workDate))
                : null;
            return daily ? Object.assign({}, daily, { exists: true, version: Number(row.version || 0) })
                : { exists: false, version: Number(row.version || 0) };
        }
        if (record.screen === 'expense' && Array.isArray(root.records)) {
            const row = root.records.find(item => item && String(item.id) === String(record.payload.id));
            return row || { exists: false, version: 0 };
        }
        if (record.screen === 'change-request' && typeof root === 'object') return root;
        return null;
    }

    function refreshedFields(server, client, baseVersion) {
        const serverObject = server && typeof server === 'object' ? server : {};
        const clientObject = client && typeof client === 'object' ? client : {};
        const names = Array.from(new Set(['version', ...Object.keys(serverObject), ...Object.keys(clientObject)]));
        return names.map(name => ({
            name,
            serverValue: serverObject[name],
            clientValue: name === 'version' ? baseVersion : clientObject[name]
        }));
    }

    async function refreshConflict(clientRequestId) {
        const ticket = scopeTicket();
        const record = (await readAllowedRecords()).find(candidate => candidate.clientRequestId === clientRequestId);
        if (!ticketIsCurrent(ticket) || !record || record.userScope !== state.scope || record.status !== 'CONFLICT') return;
        if (!navigator.onLine) { await render(); return; }
        const path = conflictRefreshPath(record);
        if (!path) return;
        try {
            const response = await fetch(path, { method: 'GET', cache: 'no-store', credentials: 'same-origin',
                headers: { 'Accept': 'application/json' } });
            if (!ticketIsCurrent(ticket)) return;
            if (response.status === 401 || (response.redirected && /\/login(?:[/?#]|$)/.test(response.url))) {
                state.paused = true;
                if (window.SES && SES.pwa) SES.pwa.sessionExpired();
                await render();
                return;
            }
            const result = await response.json();
            if (!ticketIsCurrent(ticket)) return;
            if (!response.ok || !result || result.code !== 200) throw new Error('server版を再取得できません');
            const server = refreshedSnapshot(result, record);
            const version = server && Number.isInteger(Number(server.version))
                ? Number(server.version) : refreshedVersion(result, record);
            if (version === null) throw new Error('server versionを再取得できません');
            record.conflict = sanitizeConflict(record, Object.assign({}, record.conflict, {
                server: server || record.conflict.server,
                serverVersion: version,
                fields: refreshedFields(server || record.conflict.server, record.payload,
                    record.baseVersion),
                serverRefreshAt: new Date().toISOString()
            }));
            await put(record, ticket);
        } catch (error) {
            if (!ticketIsCurrent(ticket)) return;
            record.error = error.message || 'server版を再取得できません';
            await put(record, ticket);
        }
        await render();
    }

    async function reapplyConflict(clientRequestId) {
        const ticket = scopeTicket();
        const record = (await readAllowedRecords()).find(candidate => candidate.clientRequestId === clientRequestId);
        if (!ticketIsCurrent(ticket) || !record || record.userScope !== state.scope || record.status !== 'CONFLICT') return;
        const serverVersion = Number(record.conflict && record.conflict.serverVersion);
        if (!Number.isInteger(serverVersion) || serverVersion < 0) return;
        if (!window.confirm('server版との差分を確認しました。端末版を新しいbase versionで再適用しますか？')) return;
        record.baseVersion = serverVersion;
        record.payloadHash = await commandHash(record);
        if (!ticketIsCurrent(ticket)) return;
        record.status = 'PENDING';
        delete record.conflict;
        delete record.error;
        if (await put(record, ticket) === false) return;
        await render();
        await flush();
    }

    async function markError(record, message, ticket, claim) {
        record.status = 'ERROR';
        record.error = message || '同期できません';
        if (await putClaimed(record, ticket, claim) === false) return;
        await render();
    }

    async function markExpired(record, message, ticket, claim) {
        record.status = 'ERROR';
        record.error = message || '保持期間（30日）を過ぎたため送信できません。破棄して再入力してください。';
        record.payload = null;
        record.payloadHash = null;
        if (await putClaimed(record, ticket, claim) === false) return { skipped: true };
        await render();
        return { expired: true, clientRequestId: record.clientRequestId };
    }

    function sendRecord(record) {
        const key = record.resourceKey;
        const previous = state.resourceLocks.get(key) || Promise.resolve();
        const current = previous.catch(() => undefined).then(() => sendRecordAcrossTabs(record));
        state.resourceLocks.set(key, current);
        current.finally(() => {
            if (state.resourceLocks.get(key) === current) state.resourceLocks.delete(key);
        });
        return current;
    }

    async function sendRecordAcrossTabs(record) {
        const ticket = scopeTicket();
        if (record.userScope !== state.scope || !ticketIsCurrent(ticket)) return { skipped: true };
        let claim = await claimAggregate(record.resourceKey, ticket);
        while (!claim && ticketIsCurrent(ticket) && navigator.onLine) {
            await new Promise(resolve => setTimeout(resolve, 40));
            claim = await claimAggregate(record.resourceKey, ticket);
        }
        if (!claim || !ticketIsCurrent(ticket)) return { skipped: true };
        try {
            const latest = await readById(record.clientRequestId);
            if (!ticketIsCurrent(ticket) || !latest || latest.status !== 'PENDING'
                    || latest.revocationEpoch !== ticket.epoch
                    || latest.userScope !== ticket.scope) return { skipped: true };
            record = latest;
            return await sendClaimedRecord(record, ticket, claim);
        } finally { await releaseAggregate(claim); }
    }

    async function sendClaimedRecord(record, ticket, claim) {
        if (state.restoredRequestIds.has(record.clientRequestId) && !restorePermitIsCurrent()) {
            return { skipped: true };
        }
        if (!record.createdAt || record.createdAt < now() - MAX_AGE_MS) {
            return markExpired(record, undefined, ticket, claim);
        }
        if (!navigator.onLine || !ticketIsCurrent(ticket)) return { queued: true, clientRequestId: record.clientRequestId };
        const headers = Object.assign({
            'Accept': 'application/json',
            'Content-Type': 'application/json',
            'X-Client-Request-Id': record.clientRequestId,
            'X-Client-Payload-Hash': record.payloadHash,
            'X-Client-Base-Version': String(record.baseVersion),
            'X-Client-Created-At': String(record.createdAt),
            'X-User-Scope': record.userScope
        }, SES.csrf.header());
        try {
            if (!ticketIsCurrent(ticket)) return { skipped: true };
            const response = await fetch(record.path, {
                method: record.method, cache: 'no-store', credentials: 'same-origin',
                headers, body: JSON.stringify({ screen: record.screen, month: record.month, payload: record.payload })
            });
            if (!ticketIsCurrent(ticket) || (state.restoredRequestIds.has(record.clientRequestId)
                    && !restorePermitIsCurrent())) return { skipped: true };
            const contentType = response.headers.get('content-type') || '';
            const sessionRedirect = (response.redirected && /\/login(?:[/?#]|$)/.test(response.url))
                || contentType.toLowerCase().includes('text/html');
            if (response.status === 401 || sessionRedirect) {
                state.paused = true;
                if (window.SES && SES.pwa) SES.pwa.sessionExpired();
                await render();
                window.location.href = '/login?error=timeout';
                return { paused: true };
            }
            let result = null;
            try { result = await response.json(); } catch (_) { /* 非JSONは下記で処理 */ }
            if (!ticketIsCurrent(ticket)) return { skipped: true };
            if (response.status === 409 || (result && result.code === 409)) {
                if (result && result.data && result.data.type === 'QUEUE_EXPIRED') {
                    const fallback = '保持期間（30日）を過ぎたため送信できません。破棄して再入力してください。';
                    const message = window.SES && SES.i18n
                        ? SES.i18n.t('error.pwa.queueExpired', fallback) : fallback;
                    return markExpired(record, message, ticket, claim);
                }
                return markConflict(record, result && result.data, ticket, claim);
            }
            if (!response.ok || !result || result.code !== 200) {
                const message = (result && result.message) || `HTTP ${response.status}`;
                if (response.status >= 500 || response.status === 0) {
                    await render();
                    return { queued: true, clientRequestId: record.clientRequestId };
                }
                await markError(record, message, ticket, claim);
                throw new Error(message);
            }
            if (!ticketIsCurrent(ticket)) return { skipped: true };
            if (!await removeClaimed(record.clientRequestId, ticket, claim)) return { skipped: true };
            await updateFollowingVersions(record, result.data, ticket, claim);
            await render();
            return { queued: false, data: result.data, clientRequestId: record.clientRequestId };
        } catch (error) {
            if (error && (error.name === 'TypeError' || error.name === 'NetworkError')) {
                if (ticketIsCurrent(ticket)) await putClaimed(record, ticket, claim);
                await render();
                return { queued: true, clientRequestId: record.clientRequestId };
            }
            throw error;
        }
    }

    async function request(spec) {
        if (!spec || !validPath(spec.path) || !spec.screen || !spec.method) {
            throw new Error('offline対象外の操作です');
        }
        const method = String(spec.method).toUpperCase();
        const payload = spec.payload || {};
        const baseVersion = Number(spec.baseVersion);
        if (!Number.isInteger(baseVersion) || baseVersion < 0) throw new Error('baseVersionが不正です');
        validatePayload(spec, method, payload);
        const dedupeKey = JSON.stringify(canonical({
            screen: spec.screen, month: spec.month || null, method, path: spec.path,
            baseVersion, payload
        }));
        if (state.inFlight.has(dedupeKey)) return state.inFlight.get(dedupeKey);
        const operation = (async () => {
            await state.ready;
            if (state.paused || state.quarantined || !state.scope) throw new Error('オンラインでユーザーを確認してください');
            if (!state.scope) throw new Error('ユーザーscopeを確認できません');
            const ticket = scopeTicket();
            const record = {
                clientRequestId: spec.clientRequestId || newRequestId(),
                payloadHash: '', baseVersion, userScope: state.scope, revocationEpoch: ticket.epoch,
                screen: spec.screen, month: spec.month || null, method,
                path: spec.path, payload, resourceKey: spec.resourceKey || `${spec.screen}:${spec.month || ''}`,
                createdAt: now(), expiresAt: now() + MAX_AGE_MS, status: 'PENDING'
            };
            record.payloadHash = await commandHash(record);
            if (!ticketIsCurrent(ticket) || await put(record, ticket) === false) return { skipped: true };
            state.newRequestIds.add(record.clientRequestId);
            if (!navigator.onLine) {
                await render();
                return { queued: true, clientRequestId: record.clientRequestId };
            }
            return sendRecord(record);
        })();
        state.inFlight.set(dedupeKey, operation);
        operation.finally(() => setTimeout(() => state.inFlight.delete(dedupeKey), 250));
        return operation;
    }

    async function flush() {
        if (state.flushing || state.paused || !navigator.onLine) return;
        state.flushing = true;
        try {
            await bootstrapScope();
            let records = (await readAllowedRecords())
                .filter(record => record.status === 'PENDING')
                .sort((a, b) => a.createdAt - b.createdAt);
            await render();
            const blocked = new Set();
            for (const record of records) {
                if (state.paused || !navigator.onLine) break;
                if (blocked.has(record.resourceKey)) continue;
                const result = await sendRecord(record);
                if (result && result.conflict) blocked.add(record.resourceKey);
                if (result && result.paused) break;
            }
        } catch (error) {
            console.warn('PWA queue sync paused', error);
        } finally {
            state.flushing = false;
            await render();
        }
    }

    async function resume() {
        state.paused = false;
        try { await bootstrapScope(); } catch (_) { state.paused = true; }
        if (!state.paused) await flush();
    }

    function bind() {
        window.addEventListener('offline', () => { render(); });
        window.addEventListener('online', () => { if (!state.paused) flush(); else render(); });
        window.addEventListener('ses:session-expired', quarantineDocument);
        window.addEventListener('pageshow', event => {
            if (event.persisted) quarantineDocument();
        });
        document.addEventListener('click', event => {
            const toggle = event.target.closest('#pwa-panel-toggle');
            if (toggle) {
                const body = document.getElementById('pwa-panel-body');
                const icon = document.getElementById('pwa-panel-toggle-icon');
                if (body) {
                    const isHidden = body.hidden;
                    body.hidden = !isHidden;
                    toggle.setAttribute('aria-expanded', String(isHidden));
                    if (icon) {
                        icon.className = isHidden ? 'bi bi-chevron-down' : 'bi bi-chevron-up';
                    }
                }
                return;
            }
            const discard = event.target.closest('[data-pwa-discard]');
            if (discard) remove(discard.dataset.pwaDiscard).then(render);
            const refresh = event.target.closest('[data-pwa-refresh]');
            if (refresh) refreshConflict(refresh.dataset.pwaRefresh);
            const reapply = event.target.closest('[data-pwa-reapply]');
            if (reapply) reapplyConflict(reapply.dataset.pwaReapply);
            if (event.target.closest('#pwa-sync-now')) resume();
        });
    }

    function init() {
        if (state.initialized) return;
        state.initialized = true;
        bind();
        state.ready = cleanupExpired().catch(() => undefined)
            .then(() => bootstrapScope())
            .then(() => render())
            .catch(error => { if (!error.sessionExpired) render(); });
    }

    const targetSes = (typeof SES !== 'undefined' && SES) ? SES : (window.SES = window.SES || {});
    window.SES = targetSes;
    window.addEventListener('ses:pwa-revoked', () => render());
    window.addEventListener('storage', event => {
        if (event.key === REVOCATION_KEY && event.newValue && event.newValue !== state.revocationEpoch) {
            acceptRevocation(event.newValue);
        }
    });
    try {
        if ('BroadcastChannel' in window) {
            const channel = new BroadcastChannel('ses-pwa-revocation');
            channel.onmessage = event => {
                if (event.data && event.data.type === 'REVOKE' && event.data.epoch !== state.revocationEpoch) {
                    acceptRevocation(event.data.epoch);
                }
            };
        }
    } catch (_) { /* BroadcastChannelが使えない場合はstorage eventを使う */ }

    window.setInterval(() => {
        if (state.permitDeadline) restorePermitIsCurrent();
    }, 1000);

    targetSes.pwaQueue = {
        init,
        whenReady: () => state.ready,
        request,
        flush,
        resume,
        render,
        clear: deleteAll,
        reset: revokeScope,
        getScope: () => state.scope,
        getOfflineDrafts: async () => {
            await state.ready;
            if (!restorePermitIsCurrent()) return [];
            return (await readAllowedRecords()).filter(record => ['PENDING', 'CONFLICT'].includes(record.status))
                .map(record => ({ screen: record.screen, month: record.month, status: record.status,
                    createdAt: record.createdAt, payload: record.payload }));
        },
        prepareRestore: async password => {
            await state.ready;
            if (!navigator.onLine || !password || state.quarantined) throw new Error('オンラインでログインし直してください');
            const generation = state.documentGeneration;
            const oldScope = state.candidateRestoreScope;
            if (generation !== state.documentGeneration || !oldScope
                    || await countScope(oldScope) === 0) throw new Error('復元対象を一意に確認できません');
            const response = await fetch('/api/my/pwa/restore/reauthenticate', {
                method: 'POST', cache: 'no-store', credentials: 'same-origin',
                headers: Object.assign({ 'Accept': 'application/json', 'Content-Type': 'application/json' }, SES.csrf.header()),
                body: JSON.stringify({ password, documentNonce: state.documentNonce, userScope: oldScope })
            });
            if (generation !== state.documentGeneration) throw new Error('復元が取り消されました。最初からやり直してください');
            const result = await response.json().catch(() => null);
            if (!response.ok || !result || result.code !== 200 || !result.data || !result.data.proof) {
                throw new Error('再認証できませんでした。入力を確認してください');
            }
            state.restoreProof = result.data.proof;
            state.restoreScope = oldScope;
            return true;
        },
        confirmRestore: async () => {
            await state.ready;
            const generation = state.documentGeneration;
            if (!state.restoreProof || !state.restoreScope || !navigator.onLine) throw new Error('再認証からやり直してください');
            const response = await fetch('/api/my/pwa/restore/confirm', {
                method: 'POST', cache: 'no-store', credentials: 'same-origin',
                headers: Object.assign({ 'Accept': 'application/json', 'Content-Type': 'application/json' }, SES.csrf.header()),
                body: JSON.stringify({ proof: state.restoreProof, documentNonce: state.documentNonce, userScope: state.restoreScope })
            });
            const result = await response.json().catch(() => null);
            const oldScope = state.restoreScope;
            state.restoreProof = null;
            if (generation !== state.documentGeneration || !response.ok || !result || result.code !== 200
                    || !result.data || result.data.documentNonce !== state.documentNonce
                    || result.data.userScope !== oldScope || !result.data.serverTime || !result.data.permitExpiresAt) {
                clearRestorePermit();
                throw new Error('復元を確認できませんでした。再認証からやり直してください');
            }
            const serverTime = Date.parse(result.data.serverTime);
            const expiresAt = Date.parse(result.data.permitExpiresAt);
            const wallTime = Date.now();
            const monotonicTime = performance.now();
            if (!Number.isFinite(serverTime) || !Number.isFinite(expiresAt) || expiresAt <= serverTime
                    || expiresAt - serverTime > 15 * 60 * 1000 || Math.abs(wallTime - serverTime) > 5000) {
                clearRestorePermit();
                throw new Error('時刻を安全に確認できません。再認証からやり直してください');
            }
            state.restoreProof = null;
            state.restoreScope = oldScope;
            state.permitServerTime = serverTime;
            state.permitWallTime = wallTime;
            state.permitMonotonicTime = monotonicTime;
            state.permitDeadline = monotonicTime + Math.min(expiresAt - serverTime, 15 * 60 * 1000);
            const context = await fetch('/api/my/session-context', {
                method: 'GET', cache: 'no-store', credentials: 'same-origin',
                headers: { 'Accept': 'application/json', 'X-User-Scope': oldScope }
            });
            const contextResult = await context.json().catch(() => null);
            if (generation !== state.documentGeneration || !restorePermitIsCurrent() || !context.ok
                    || !contextResult || !contextResult.data || !contextResult.data.userScope
                    || !contextResult.data.preserveQueue) {
                clearRestorePermit();
                throw new Error('保存した下書きのユーザーを確認できませんでした');
            }
            const newScope = contextResult.data.userScope;
            const records = await readRestoreScope(oldScope);
            const restorable = [];
            for (const record of records) {
                if (record.userScope !== oldScope || record.revocationEpoch !== readRevocationEpoch()
                        || !['PENDING', 'CONFLICT'].includes(record.status) || !record.payload
                        || !record.createdAt || record.createdAt > Date.now() + 5 * 60 * 1000
                        || record.createdAt < Date.now() - MAX_AGE_MS) continue;
                try {
                    validatePayload(record, String(record.method).toUpperCase(), record.payload);
                    if (await commandHash(record) !== record.payloadHash) continue;
                    restorable.push(record.clientRequestId);
                } catch (_) { /* 不正recordは復元候補に含めない */ }
            }
            if (generation !== state.documentGeneration || !restorePermitIsCurrent()) {
                clearRestorePermit();
                throw new Error('復元が取り消されました。最初からやり直してください');
            }
            try {
                await rebindScope(oldScope, newScope, new Set(restorable),
                    () => generation === state.documentGeneration && restorePermitIsCurrent()
                        && !state.quarantined && readRevocationEpoch() === state.revocationEpoch);
            } catch (_) {
                clearRestorePermit();
                throw new Error('復元が取り消されました。最初からやり直してください');
            }
            if (generation !== state.documentGeneration || !restorePermitIsCurrent() || state.quarantined
                    || readRevocationEpoch() !== state.revocationEpoch) {
                clearRestorePermit();
                throw new Error('復元が取り消されました。最初からやり直してください');
            }
            if (oldScope !== newScope) await deleteScope(oldScope);
            state.scope = newScope;
            state.candidateRestoreScope = newScope;
            state.lockedDraftCount = 0;
            state.restoredRequestIds = new Set(restorable);
            state.quarantined = false;
            state.paused = false;
            persistScope(newScope);
            await render();
            await flush();
            return true;
        },
        canonicalHash: record => commandHash(record)
    };

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
