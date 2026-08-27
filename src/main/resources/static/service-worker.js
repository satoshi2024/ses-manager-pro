/* SES Manager Pro 要員ポータル PWA。動的レスポンスは決してCache Storageへ保存しない。 */
'use strict';

const CACHE_PREFIX = 'ses-pwa-shell-';
const CACHE_NAME = CACHE_PREFIX + 'v2';
const SHELL_ASSETS = [
    '/manifest.webmanifest',
    '/offline.html',
    '/favicon.svg',
    '/css/common.css',
    '/lib/bootstrap/bootstrap.min.css',
    '/lib/bootstrap-icons/bootstrap-icons.min.css',
    '/lib/bootstrap/bootstrap.bundle.min.js',
    '/lib/jquery/jquery.min.js',
    '/lib/sweetalert2/sweetalert2.min.css',
    '/lib/sweetalert2/sweetalert2.all.min.js',
    '/js/common.js',
    '/js/pwa-queue.js',
    '/js/profile.js',
    '/js/modules/my-timesheet.js',
    '/js/modules/attendance-my.js',
    '/js/modules/my-expenses.js',
    '/js/modules/my-profile.js'
];

function isSameOrigin(request) {
    return new URL(request.url).origin === self.location.origin;
}

function isStaticAsset(request) {
    if (request.method !== 'GET' || !isSameOrigin(request)) return false;
    const path = new URL(request.url).pathname;
    if (path === '/js/i18n.js') return false;
    return SHELL_ASSETS.indexOf(path) !== -1;
}

function isDynamicOrSensitive(request) {
    const path = new URL(request.url).pathname;
    return path.startsWith('/api/') || path.startsWith('/my/') || path.startsWith('/portal/')
        || /(?:document|payroll|bank|attachment|receipt|files|pdf|reconciliation)/i.test(path);
}

function isSafeShellResponse(response) {
    return response && response.ok && !response.redirected && response.type !== 'opaque';
}

self.addEventListener('install', event => {
    event.waitUntil(
        caches.open(CACHE_NAME).then(cache =>
            Promise.all(SHELL_ASSETS.map(asset =>
                fetch(asset, { credentials: 'same-origin', cache: 'reload', redirect: 'error' })
                    .then(response => isSafeShellResponse(response)
                        ? cache.put(asset, response.clone()) : undefined)
                    .catch(() => undefined)
            ))
        )
    );
});

self.addEventListener('activate', event => {
    event.waitUntil(
        caches.keys().then(keys => Promise.all(
            keys.filter(key => key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME)
                .map(key => caches.delete(key))
        )).then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', event => {
    const request = event.request;
    if (isStaticAsset(request)) {
        event.respondWith(
            caches.match(request).then(cached => cached || fetch(request).then(response => {
                if (isSafeShellResponse(response)) {
                    return caches.open(CACHE_NAME).then(cache => {
                        cache.put(request, response.clone());
                        return response;
                    });
                }
                return response;
            }))
        );
        return;
    }

    // API、portal、document、給与、銀行、添付、PDF、その他PIIはnetwork-only。
    // ナビゲーションもHTMLをcacheせず、オフライン時だけ汎用ページを返す。
    if (request.method === 'GET' && isSameOrigin(request) && !isDynamicOrSensitive(request)
            && request.mode === 'navigate') {
        event.respondWith(fetch(request).catch(() => caches.match('/offline.html')));
        return;
    }
    // network-only: ここではCache Storageへ書き込まない。
});

self.addEventListener('message', event => {
    if (event.data && event.data.type === 'SKIP_WAITING') {
        self.skipWaiting();
    }
    if (event.data && event.data.type === 'CLEAR_USER_SCOPE') {
        // ユーザー単位データはIndexedDBを使用し、SWのCache Storageには保存しない。
        event.ports && event.ports[0] && event.ports[0].postMessage({ type: 'USER_SCOPE_CLEARED' });
    }
});
