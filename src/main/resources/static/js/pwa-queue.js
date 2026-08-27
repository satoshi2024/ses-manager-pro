/* 要員PWAの最小draft/queue。API/portal/document/給与等のレスポンスは保存しない。 */
'use strict';

(() => {
    const DB_NAME = 'ses-pwa-self-service';
    const DB_VERSION = 1;
    const STORE = 'queue';
    const MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000;
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
        inFlight: new Map()
    };

    function openDb() {
        return new Promise((resolve, reject) => {
            if (!window.indexedDB) return reject(new Error('このブラウザではoffline保存を利用できません'));
            const request = indexedDB.open(DB_NAME, DB_VERSION);
            request.onupgradeneeded = () => {
                const db = request.result;
                if (!db.objectStoreNames.contains(STORE)) {
                    const store = db.createObjectStore(STORE, { keyPath: 'clientRequestId' });
                    store.createIndex('userScope', 'userScope', { unique: false });
                    store.createIndex('status', 'status', { unique: false });
                    store.createIndex('createdAt', 'createdAt', { unique: false });
                }
            };
            request.onsuccess = () => resolve(request.result);
            request.onerror = () => reject(request.error || new Error('offline保存を初期化できません'));
        });
    }

    function withStore(mode, operation) {
        return openDb().then(db => new Promise((resolve, reject) => {
            let result;
            let failed = false;
            let tx;
            try {
                tx = db.transaction(STORE, mode);
                result = operation(tx.objectStore(STORE));
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

    function put(record) {
        return withStore('readwrite', store => { store.put(record); return record; });
    }

    function remove(clientRequestId) {
        return withStore('readwrite', store => { store.delete(clientRequestId); });
    }

    function readAll() {
        return withStore('readonly', store => {
            const request = store.getAll();
            request.onsuccess = () => { request._pwaResult = request.result || []; };
            return request;
        }).then(request => request._pwaResult || []);
    }

    function deleteAll() {
        return withStore('readwrite', store => { store.clear(); });
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
            payload: record.payload,
            screen: record.screen
        };
    }

    async function commandHash(record) {
        return sha256(JSON.stringify(canonical(commandFor(record))));
    }

    function newRequestId() {
        if (crypto.randomUUID) return crypto.randomUUID();
        return 'pwa-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 12);
    }

    function validPath(path) {
        return typeof path === 'string' && ALLOWED_PATHS.some(pattern => pattern.test(path));
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

    async function bootstrapScope() {
        const oldScope = state.scope || persistedScope();
        if (!navigator.onLine) {
            state.scope = oldScope;
            if (!state.scope) throw new Error('offlineで利用するには一度オンラインでログインしてください');
            return state.scope;
        }
        try {
            const response = await fetch('/api/my/session-context', {
                method: 'GET', cache: 'no-store', credentials: 'same-origin',
                headers: { 'Accept': 'application/json' }
            });
            if (response.status === 401) throw Object.assign(new Error('Session timeout'), { sessionExpired: true });
            if (!response.ok) throw new Error('ユーザーscopeを確認できません');
            const result = await response.json();
            const scope = result && result.data && result.data.userScope;
            if (!scope || typeof scope !== 'string') throw new Error('ユーザーscopeが不正です');
            if (oldScope && oldScope !== scope) await deleteAll();
            state.scope = scope;
            persistScope(scope);
            state.paused = false;
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
        const records = await readAll();
        await Promise.all(records.filter(record => !record.createdAt || record.createdAt < threshold)
            .map(record => remove(record.clientRequestId)));
    }

    function scopeRecords(records) {
        return records.filter(record => record.userScope === state.scope && record.createdAt >= now() - MAX_AGE_MS);
    }

    function safeJson(value) {
        try { return JSON.stringify(value, null, 2); } catch (_) { return String(value); }
    }

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>'"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char]));
    }

    async function render() {
        let records = [];
        try { records = scopeRecords(await readAll()); } catch (_) { return; }
        const pending = records.filter(record => record.status === 'PENDING');
        const conflicts = records.filter(record => record.status === 'CONFLICT');
        const errors = records.filter(record => record.status === 'ERROR');
        const panel = document.getElementById('pwa-queue-panel');
        const status = document.getElementById('pwa-queue-status');
        const conflictBox = document.getElementById('pwa-conflicts');
        if (panel && status && conflictBox) {
            panel.hidden = pending.length === 0 && conflicts.length === 0 && errors.length === 0;
            if (state.paused) status.textContent = 'セッション期限切れ。再認証後に同じユーザーで同期を再開します。';
            else if (!navigator.onLine && pending.length) status.textContent = `オフライン: 未送信 ${pending.length} 件`;
            else if (state.flushing) status.textContent = `同期中: ${pending.length} 件`;
            else if (pending.length) status.textContent = `未送信 ${pending.length} 件`;
            else if (conflicts.length) status.textContent = `競合 ${conflicts.length} 件。サーバー版と端末版を確認してください。`;
            else status.textContent = `同期停止 ${errors.length} 件。再認証または破棄を確認してください。`;
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
        return `<div class="pwa-queue-conflict" data-request-id="${escapeHtml(record.clientRequestId)}">` +
            `<div class="fw-bold">${title}: ${escapeHtml(record.screen)} / ${escapeHtml(record.month || '')}</div>` +
            `<div class="small text-muted">clientRequestId: ${escapeHtml(record.clientRequestId)}</div>` +
            `<div class="small mt-1">サーバー版</div><pre>${escapeHtml(safeJson(server))}</pre>` +
            `<div class="small">端末版</div><pre>${escapeHtml(safeJson(client))}</pre>` +
            `<button type="button" class="btn btn-sm btn-outline-danger" data-pwa-discard="${escapeHtml(record.clientRequestId)}">端末版を破棄</button>` +
            `</div>`;
    }

    function setPendingStatus() { render(); }

    async function updateFollowingVersions(record, responseData) {
        const version = responseData && Number.isInteger(Number(responseData.version))
            ? Number(responseData.version) : null;
        if (version === null) return;
        const records = scopeRecords(await readAll());
        await Promise.all(records
            .filter(candidate => candidate.status === 'PENDING'
                && candidate.resourceKey === record.resourceKey
                && candidate.createdAt >= record.createdAt
                && candidate.baseVersion === record.baseVersion)
            .map(async candidate => {
                candidate.baseVersion = version;
                candidate.payloadHash = await commandHash(candidate);
                return put(candidate);
            }));
    }

    async function markConflict(record, data) {
        record.status = 'CONFLICT';
        record.conflict = data || { type: 'CONFLICT' };
        await put(record);
        await render();
        return { conflict: true, data: record.conflict, clientRequestId: record.clientRequestId };
    }

    async function markError(record, message) {
        record.status = 'ERROR';
        record.error = message || '同期できません';
        await put(record);
        await render();
    }

    async function markExpired(record, message) {
        record.status = 'ERROR';
        record.error = message || '保持期間（30日）を過ぎたため送信できません。破棄して再入力してください。';
        await put(record);
        await render();
        return { expired: true, clientRequestId: record.clientRequestId };
    }

    async function sendRecord(record) {
        if (record.userScope !== state.scope) return { skipped: true };
        if (!navigator.onLine || state.paused) return { queued: true, clientRequestId: record.clientRequestId };
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
            const response = await fetch(record.path, {
                method: record.method, cache: 'no-store', credentials: 'same-origin',
                headers, body: JSON.stringify({ screen: record.screen, month: record.month, payload: record.payload })
            });
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
            if (response.status === 409 || (result && result.code === 409)) {
                if (result && result.data && result.data.type === 'QUEUE_EXPIRED') {
                    const fallback = '保持期間（30日）を過ぎたため送信できません。破棄して再入力してください。';
                    const message = window.SES && SES.i18n
                        ? SES.i18n.t('error.pwa.queueExpired', fallback) : fallback;
                    return markExpired(record, message);
                }
                return markConflict(record, result && result.data);
            }
            if (!response.ok || !result || result.code !== 200) {
                const message = (result && result.message) || `HTTP ${response.status}`;
                if (response.status >= 500 || response.status === 0) {
                    await render();
                    return { queued: true, clientRequestId: record.clientRequestId };
                }
                await markError(record, message);
                throw new Error(message);
            }
            await remove(record.clientRequestId);
            await updateFollowingVersions(record, result.data);
            await render();
            return { queued: false, data: result.data, clientRequestId: record.clientRequestId };
        } catch (error) {
            if (error && (error.name === 'TypeError' || error.name === 'NetworkError')) {
                await put(record);
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
        const dedupeKey = JSON.stringify(canonical({
            screen: spec.screen, month: spec.month || null, method, path: spec.path,
            baseVersion, payload
        }));
        if (state.inFlight.has(dedupeKey)) return state.inFlight.get(dedupeKey);
        const operation = (async () => {
            await state.ready;
            if (state.paused) throw new Error('セッションを再認証してください');
            if (!state.scope) throw new Error('ユーザーscopeを確認できません');
            const record = {
                clientRequestId: spec.clientRequestId || newRequestId(),
                payloadHash: '', baseVersion, userScope: state.scope,
                screen: spec.screen, month: spec.month || null, method,
                path: spec.path, payload, resourceKey: spec.resourceKey || `${spec.screen}:${spec.month || ''}`,
                createdAt: now(), expiresAt: now() + MAX_AGE_MS, status: 'PENDING'
            };
            record.payloadHash = await commandHash(record);
            await put(record);
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
            let records = scopeRecords(await readAll())
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
        window.addEventListener('ses:session-expired', () => { state.paused = true; render(); });
        document.addEventListener('click', event => {
            const discard = event.target.closest('[data-pwa-discard]');
            if (discard) remove(discard.dataset.pwaDiscard).then(render);
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

    window.SES = window.SES || {};
    window.SES.pwaQueue = {
        init,
        request,
        flush,
        resume,
        render,
        clear: deleteAll,
        reset: () => { state.scope = null; state.paused = false; },
        getScope: () => state.scope,
        canonicalHash: record => commandHash(record)
    };

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', init);
    else init();
})();
