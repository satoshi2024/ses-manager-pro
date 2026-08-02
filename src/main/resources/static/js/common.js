/**
 * ========================================
 * SES Manager Pro - 共通JavaScriptユーティリティ
 * ========================================
 */

const SES = {
    
    /**
     * i18n (Internationalization)
     */
    i18n: {
        t: function(key, ...args) {
            let msg = (window.SES_MESSAGES && window.SES_MESSAGES[key]) ? window.SES_MESSAGES[key] : key;
            if (args.length === 0) return msg;

            // 引数がオブジェクト1つの場合: 名前付きプレースホルダー {key} を置換
            if (args.length === 1 && args[0] !== null && typeof args[0] === 'object' && !Array.isArray(args[0])) {
                var params = args[0];
                Object.keys(params).forEach(function(k) {
                    msg = msg.replace(new RegExp('\\{' + k + '\\}', 'g'), params[k]);
                });
                return msg;
            }

            // 引数が配列1つの場合: 位置プレースホルダー {0},{1},... を置換
            if (args.length === 1 && Array.isArray(args[0])) {
                args = args[0];
            }

            // 位置プレースホルダー {0},{1},{2}... を置換
            for (var i = 0; i < args.length; i++) {
                msg = msg.replace(new RegExp('\\{' + i + '\\}', 'g'), args[i]);
            }
            return msg;
        },
        e: function(group, dbValue) {
            if (!dbValue) return '';
            const code = (window.SES_ENUMS && window.SES_ENUMS[group] && window.SES_ENUMS[group][dbValue]);
            if (!code) return dbValue;
            return this.t('enum.' + group + '.' + code);
        },
        get lang() {
            return window.SES_LANG || 'ja';
        }
    },
    
    /**
     * API呼出ユーティリティ
     * fetch APIのラッパー。CSRF対策、共通エラーハンドリングを含む。
     */
    api: {
        get: async function(url, params = {}) {
            if (Object.keys(params).length > 0) {
                const searchParams = new URLSearchParams(params);
                url += '?' + searchParams.toString();
            }
            return this._fetch(url, { method: 'GET' });
        },
        
        post: async function(url, data) {
            return this._fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
        },
        
        put: async function(url, data) {
            return this._fetch(url, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
        },
        
        delete: async function(url) {
            return this._fetch(url, { method: 'DELETE' });
        },
        
        _fetch: async function(url, options) {
            // CSRF: 更新系リクエストで XSRF-TOKEN Cookie を X-XSRF-TOKEN ヘッダーへ複製
            const method = (options.method || 'GET').toUpperCase();
            if (!/^(GET|HEAD|OPTIONS|TRACE)$/.test(method)) {
                const token = SES.csrf && SES.csrf.token();
                if (token) {
                    options.headers = options.headers || {};
                    options.headers['X-XSRF-TOKEN'] = token;
                }
            }

            try {
                const response = await fetch(url, options);
                
                // Fetch API follows redirects by default. If redirected to login or returns HTML...
                const contentType = response.headers.get('content-type') || '';
                if (response.redirected && (response.url.indexOf('/login') !== -1 || contentType.indexOf('text/html') !== -1)) {
                    window.location.href = '/login?error=timeout';
                    throw new Error('Session timeout');
                }

                // HTTPステータスエラーハンドリング
                if (response.status === 401) {
                    window.location.href = '/login?error=timeout';
                    throw new Error('Unauthorized');
                }
                if (response.status === 403) {
                    SES.toast.error('アクセス権限がありません。');
                    throw new Error('Forbidden');
                }
                if (response.status >= 500) {
                    SES.toast.error('サーバーエラーが発生しました。');
                    throw new Error('Server Error');
                }
                
                // ApiResult形式のパース
                const result = await response.json();
                if (result.code !== 200) {
                    SES.toast.error(result.message || '処理に失敗しました。');
                    throw new Error(result.message);
                }
                
                return result.data;
                
            } catch (error) {
                console.error('API Error:', error);
                throw error;
            }
        }
    },

    /** バイナリダウンロード。APIエラーはページ遷移せずトーストで通知する。 */
    download: async function(url, fallbackName) {
        try {
            const response = await fetch(url, { credentials: 'same-origin', headers: { 'Accept': 'application/octet-stream, application/json' } });
            const contentType = response.headers.get('content-type') || '';
            if (response.redirected && response.url.indexOf('/login') !== -1) {
                window.location.href = '/login?error=timeout';
                return false;
            }
            if (!response.ok) {
                let message = SES.i18n.t('error.networkError');
                if (contentType.indexOf('application/json') !== -1) {
                    try {
                        const result = await response.json();
                        message = result.message || message;
                    } catch (e) {
                        // JSONでないエラーレスポンスは共通メッセージへフォールバックする。
                    }
                }
                SES.toast.error(message);
                return false;
            }

            const disposition = response.headers.get('Content-Disposition') || '';
            const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i);
            const plainName = disposition.match(/filename="?([^";]+)"?/i);
            const filename = encodedName ? decodeURIComponent(encodedName[1]) : (plainName ? plainName[1] : fallbackName);
            const blobUrl = URL.createObjectURL(await response.blob());
            const anchor = document.createElement('a');
            anchor.href = blobUrl;
            anchor.download = filename;
            document.body.appendChild(anchor);
            anchor.click();
            anchor.remove();
            URL.revokeObjectURL(blobUrl);
            return true;
        } catch (error) {
            console.error('Download error:', error);
            SES.toast.error(SES.i18n.t('error.networkError'));
            return false;
        }
    },
    
    /**
     * トースト通知 (Bootstrap 5 Toast利用)
     */
    toast: {
        success: function(message) { this._show(message, 'success', 'bi-check-circle-fill', 'text-success'); },
        error: function(message) { this._show(message, 'danger', 'bi-exclamation-triangle-fill', 'text-danger'); },
        warning: function(message) { this._show(message, 'warning', 'bi-exclamation-circle-fill', 'text-warning'); },
        info: function(message) { this._show(message, 'info', 'bi-info-circle-fill', 'text-info'); },
        
        _show: function(message, type, iconClass, textClass) {
            const container = document.getElementById('toast-container');
            if (!container) return;
            
            const toastId = 'toast-' + Date.now();
            const bgClass = `bg-${type} bg-opacity-10`;
            const borderClass = `border-${type}`;
            
            const html = `
                <div id="${toastId}" class="toast align-items-center ${bgClass} ${borderClass} border text-white" role="alert" aria-live="assertive" aria-atomic="true" style="pointer-events: auto;">
                    <div class="d-flex">
                        <div class="toast-body d-flex align-items-center">
                            <i class="bi ${iconClass} ${textClass} fs-5 me-2"></i>
                            <span>${SES.escapeHtml(message)}</span>
                        </div>
                        <button type="button" class="btn-close btn-close-white me-2 m-auto" data-bs-dismiss="toast" aria-label="Close"></button>
                    </div>
                </div>
            `;
            
            container.insertAdjacentHTML('beforeend', html);
            const toastElement = document.getElementById(toastId);
            const bsToast = new bootstrap.Toast(toastElement, { delay: 3000 });
            bsToast.show();
            
            // クリーンアップ
            toastElement.addEventListener('hidden.bs.toast', () => {
                toastElement.remove();
            });
        }
    },
    
    /**
     * 各種フォーマット/ユーティリティ
     */
    util: {
        formatCurrency: function(amount) {
            if (amount == null) return '¥0';
            return '¥' + parseInt(amount).toLocaleString('ja-JP');
        },
        
        formatDate: function(dateStr) {
            if (!dateStr) return '';
            const d = new Date(dateStr);
            return `${d.getFullYear()}/${String(d.getMonth()+1).padStart(2,'0')}/${String(d.getDate()).padStart(2,'0')}`;
        },
        
        getLocalDateString: function(d = new Date()) {
            return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        },
        
        updateHeaderDatetime: function() {
            const el = document.getElementById('header-datetime');
            if (!el) return;
            
            const now = new Date();
            const locale = document.documentElement.lang || 'ja-JP';
            
            const dateStr = new Intl.DateTimeFormat(locale, { year: 'numeric', month: 'short', day: '2-digit', weekday: 'short' }).format(now);
            const timeStr = new Intl.DateTimeFormat(locale, { hour: '2-digit', minute: '2-digit', hour12: false }).format(now);
            
            el.innerHTML = `${dateStr} <span class="fw-bold ms-1 text-white">${timeStr}</span>`;
        },
        
        // シンプルな確認ダイアログラッパー
        confirm: function(message, onConfirm) {
            if (window.confirm(message)) {
                if (typeof onConfirm === 'function') onConfirm();
            }
        }
    },
    
    /**
     * ページネーション (A7-22 / R7-04)
     */
    pagination: {
        render: function(elementId, current, pages, onClick) {
            const el = document.getElementById(elementId);
            if (!el) return;
            if (pages <= 1) {
                el.innerHTML = '';
                return;
            }
            let html = '<ul class="pagination pagination-sm m-0">';
            html += `<li class="page-item ${current <= 1 ? 'disabled' : ''}"><a class="page-link bg-dark text-secondary border-secondary hover-bg-secondary" href="#" data-page="${current - 1}"><i class="bi bi-chevron-left"></i></a></li>`;
            
            const start = Math.max(1, current - 2);
            const end = Math.min(pages, start + 4);
            
            for (let i = start; i <= end; i++) {
                html += `<li class="page-item ${i === current ? 'active' : ''}"><a class="page-link ${i === current ? 'bg-primary border-primary text-white' : 'bg-dark text-secondary border-secondary hover-bg-secondary'}" href="#" data-page="${i}">${i}</a></li>`;
            }
            
            html += `<li class="page-item ${current >= pages ? 'disabled' : ''}"><a class="page-link bg-dark text-secondary border-secondary hover-bg-secondary" href="#" data-page="${current + 1}"><i class="bi bi-chevron-right"></i></a></li>`;
            html += '</ul>';
            el.innerHTML = html;
            
            el.querySelectorAll('a.page-link').forEach(a => {
                a.addEventListener('click', function(e) {
                    e.preventDefault();
                    if (this.parentElement.classList.contains('disabled')) return;
                    const page = parseInt(this.getAttribute('data-page'));
                    if (typeof onClick === 'function') onClick(page);
                });
            });
        }
    },
    
    /**
     * サイドバー制御
     */
    sidebar: {
        init: function() {
            const toggleBtn = document.getElementById('sidebar-toggle-btn');
            const closeBtn = document.getElementById('sidebar-close-btn');
            const sidebar = document.getElementById('sidebar');
            const backdrop = document.getElementById('sidebar-backdrop');

            const openSidebar = () => {
                sidebar.classList.add('show');
                if (backdrop) backdrop.classList.add('show');
            };
            const closeSidebar = () => {
                sidebar.classList.remove('show');
                if (backdrop) backdrop.classList.remove('show');
            };

            if (toggleBtn && sidebar) {
                toggleBtn.addEventListener('click', openSidebar);
            }

            if (closeBtn && sidebar) {
                closeBtn.addEventListener('click', closeSidebar);
            }

            if (backdrop) {
                backdrop.addEventListener('click', closeSidebar);
            }

            // モバイル/タブレット時の画面外クリックで閉じる（サイドバーのドロワー化は992px以下）
            document.addEventListener('click', (e) => {
                if (window.innerWidth <= 992 && sidebar && sidebar.classList.contains('show')) {
                    if (!sidebar.contains(e.target) && (!toggleBtn || !toggleBtn.contains(e.target))) {
                        closeSidebar();
                    }
                }
            });

            // デスクトップ幅に戻したときにドロワー状態(と背景オーバーレイ)を解除
            window.addEventListener('resize', () => {
                if (window.innerWidth > 992) closeSidebar();
            });

            // ナビリンクをタップしたら自動で閉じる（モバイル/タブレット）
            if (sidebar) {
                sidebar.querySelectorAll('.nav-link').forEach(link => {
                    link.addEventListener('click', () => {
                        if (window.innerWidth <= 992) closeSidebar();
                    });
                });
            }
        }
    },
    /**
     * サジェスト（オートコンプリート）
     *
     * ネイティブの {@code <datalist>} は使わず自前のドロップダウンに置き換えている。datalist には
     * 「入力した文字で始まらない候補が出る」2つの原因があり、最寄り駅の入力で実害が出たため。
     *  1) Chrome/Edge は option の value だけでなく表示テキスト（ラベル）にも一致させる。
     *     最寄り駅は value=駅名 / ラベル=「都道府県 路線」で出していたので、「東」と打つと
     *     駅名が「東」で始まらない候補（東京都の駅、JR東海道本線の駅…）が大量に並んでいた。
     *  2) 絞り込みが前方一致ではなく部分一致で、しかも元データ順に出るため、
     *     「田」で 神田 → 池田 … のように途中一致が先頭に来る。
     * ここでは「前方一致を必ず先に、部分一致はその後ろ」に固定し、照合対象も value（駅名・氏名など）
     * だけに限定する。ラベルは表示専用で照合には使わない。
     */
    autocomplete: {
        /** 候補として一度に表示する最大件数（駅は8千件超あるため必ず打ち切る） */
        LIMIT: 20,

        init: function() {
            this.bind('/api/autocomplete/engineers', 'engineers');
            this.bind('/api/autocomplete/customers', 'customers');
            this.bind('/api/autocomplete/projects', 'projects');
            this.bind('/api/autocomplete/users', 'users');
        },

        /**
         * data-suggest="<key>" が付いた入力欄へ、APIから取得した候補を割り当てる。
         * 対象の入力欄が無いページではAPIを呼ばない。
         */
        bind: async function(url, key) {
            const inputs = document.querySelectorAll('input[data-suggest="' + key + '"]');
            if (inputs.length === 0) return;
            try {
                const names = await SES.api.get(url);
                if (!names || names.length === 0) return;
                const items = names.map(function(name) { return { value: name }; });
                inputs.forEach(function(input) {
                    SES.autocomplete.attach(input, { items: items });
                });
            } catch (e) {
                console.error(`Failed to load autocomplete data for ${key}`, e);
            }
        },

        /**
         * 照合用の正規化。全角英数→半角、半角カナ→全角カナ（NFKC）、大文字小文字、
         * カタカナ/ひらがな、空白・中黒の有無を吸収する。
         * 例: 「ＪＲ河内永和」と「JR河内永和」、「トウキョウ」と「とうきょう」を同一視。
         */
        normalize: function(text) {
            if (text == null) return '';
            let s = String(text);
            if (typeof s.normalize === 'function') s = s.normalize('NFKC');
            s = s.toLowerCase();
            s = s.replace(/[ァ-ヶ]/g, function(c) {
                return String.fromCharCode(c.charCodeAt(0) - 0x60);
            });
            return s.replace(/[\s　・]/g, '');
        },

        /** 候補配列に正規化済み文字列を持たせる（入力のたびに正規化し直さないため） */
        prepare: function(items) {
            const self = this;
            return (items || [])
                .map(function(item) {
                    const obj = (typeof item === 'string') ? { value: item } : (item || {});
                    return { value: obj.value == null ? '' : String(obj.value), label: obj.label || '', norm: self.normalize(obj.value) };
                })
                .filter(function(item) { return item.value !== ''; });
        },

        /**
         * 完全一致 → 前方一致 → 部分一致 の順に並べて返す。照合は value のみ（ラベルは対象外）。
         * 戻り値の先頭は必ず入力文字で始まる候補になる。
         *
         * 候補全件を走査するのは、完全一致（例:「東京」に対する 東京駅）が元データの後方にあっても
         * 先頭に出すため。1万件規模でも indexOf のみなので1キー入力あたりのコストは無視できる。
         */
        match: function(items, query, limit) {
            const max = limit || this.LIMIT;
            const q = this.normalize(query);
            if (!q) return [];
            const exactHits = [];
            const prefixHits = [];
            const partialHits = [];
            for (let i = 0; i < items.length; i++) {
                const item = items[i];
                const norm = (item.norm !== undefined) ? item.norm : this.normalize(item.value);
                const pos = norm.indexOf(q);
                if (pos !== 0) {
                    if (pos > 0 && partialHits.length < max) partialHits.push(item);
                } else if (norm.length === q.length) {
                    exactHits.push(item);
                } else if (prefixHits.length < max) {
                    prefixHits.push(item);
                }
            }
            return exactHits.concat(prefixHits, partialHits).slice(0, max);
        },

        /**
         * 入力欄にサジェストを取り付ける。同じ入力欄に再度呼ぶと候補の差し替えになる。
         * options: { items, limit, minChars, onSelect }
         */
        attach: function(input, options) {
            if (!input) return null;
            const opts = options || {};
            if (input._sesSuggest) {
                input._sesSuggest.setItems(opts.items);
                return input._sesSuggest;
            }

            const api = this;
            const limit = opts.limit || api.LIMIT;
            const minChars = (opts.minChars === undefined) ? 1 : opts.minChars;
            let items = api.prepare(opts.items);
            let results = [];
            let activeIndex = -1;
            let composing = false;
            let suppress = false;

            api._seq = (api._seq || 0) + 1;
            const menuId = 'ses-suggest-' + api._seq;
            const menu = document.createElement('div');
            menu.className = 'ses-suggest-menu';
            menu.id = menuId;
            menu.setAttribute('role', 'listbox');
            menu.hidden = true;
            // モーダル内の overflow に切られないよう body 直下 + position:fixed で描画する
            document.body.appendChild(menu);

            // ネイティブのサジェストと二重に出さない
            input.removeAttribute('list');
            input.setAttribute('autocomplete', 'off');
            input.setAttribute('role', 'combobox');
            input.setAttribute('aria-autocomplete', 'list');
            input.setAttribute('aria-controls', menuId);
            input.setAttribute('aria-expanded', 'false');

            function position() {
                const rect = input.getBoundingClientRect();
                menu.style.left = rect.left + 'px';
                menu.style.width = Math.max(rect.width, 200) + 'px';
                const below = window.innerHeight - rect.bottom;
                if (below < 200 && rect.top > below) {
                    menu.style.top = 'auto';
                    menu.style.bottom = (window.innerHeight - rect.top + 2) + 'px';
                } else {
                    menu.style.bottom = 'auto';
                    menu.style.top = (rect.bottom + 2) + 'px';
                }
            }

            // 入力文字そのものが値のどこに現れるかで太字にする（正規化前の素の文字列で判定）
            function highlight(value) {
                const raw = input.value.trim();
                const idx = raw ? value.toLowerCase().indexOf(raw.toLowerCase()) : -1;
                if (idx < 0) return SES.escapeHtml(value);
                return SES.escapeHtml(value.slice(0, idx))
                    + '<mark class="ses-suggest-hit">' + SES.escapeHtml(value.slice(idx, idx + raw.length)) + '</mark>'
                    + SES.escapeHtml(value.slice(idx + raw.length));
            }

            function close() {
                if (menu.hidden) return;
                menu.hidden = true;
                activeIndex = -1;
                input.setAttribute('aria-expanded', 'false');
            }

            function render() {
                if (results.length === 0) {
                    close();
                    return;
                }
                menu.innerHTML = results.map(function(item, idx) {
                    const label = item.label
                        ? '<span class="ses-suggest-label">' + SES.escapeHtml(item.label) + '</span>' : '';
                    return '<div class="ses-suggest-item' + (idx === activeIndex ? ' active' : '') + '"'
                        + ' role="option" aria-selected="' + (idx === activeIndex) + '" data-index="' + idx + '">'
                        + '<span class="ses-suggest-value">' + highlight(item.value) + '</span>' + label
                        + '</div>';
                }).join('');
                menu.hidden = false;
                input.setAttribute('aria-expanded', 'true');
                position();
            }

            function refresh() {
                const query = input.value.trim();
                if (query.length < minChars) {
                    results = [];
                    close();
                    return;
                }
                results = api.match(items, query, limit);
                activeIndex = -1;
                render();
            }

            function select(index) {
                const item = results[index];
                if (!item) return;
                suppress = true;
                input.value = item.value;
                close();
                // 既存の 'input'/'change' ハンドラ（路線の絞り込み等）へ選択を伝える
                input.dispatchEvent(new Event('input', { bubbles: true }));
                input.dispatchEvent(new Event('change', { bubbles: true }));
                suppress = false;
                if (typeof opts.onSelect === 'function') opts.onSelect(item);
            }

            function moveActive(delta) {
                if (menu.hidden) {
                    refresh();
                    if (menu.hidden) return;
                }
                activeIndex = (activeIndex + delta + results.length) % results.length;
                render();
                const activeEl = menu.querySelector('.ses-suggest-item.active');
                if (activeEl && activeEl.scrollIntoView) activeEl.scrollIntoView({ block: 'nearest' });
            }

            input.addEventListener('compositionstart', function() { composing = true; });
            input.addEventListener('compositionend', function() {
                composing = false;
                refresh();
            });
            input.addEventListener('input', function(e) {
                // IME変換中は確定前の読みで候補が乱れるため、確定(compositionend)まで待つ
                if (suppress || composing || (e && e.isComposing)) return;
                refresh();
            });
            input.addEventListener('focus', function() {
                if (input.value.trim().length >= minChars) refresh();
            });
            input.addEventListener('blur', function() { close(); });
            input.addEventListener('keydown', function(e) {
                if (e.key === 'ArrowDown') { e.preventDefault(); moveActive(1); }
                else if (e.key === 'ArrowUp') { e.preventDefault(); moveActive(-1); }
                else if (e.key === 'Enter') {
                    if (!menu.hidden && activeIndex >= 0) { e.preventDefault(); select(activeIndex); }
                    else { close(); }
                } else if (e.key === 'Escape') {
                    // 候補が開いている間のEscapeは候補を閉じるだけにする。
                    // 伝播させるとモーダル(入力中のフォーム)ごと閉じてしまう。
                    if (!menu.hidden) { e.stopPropagation(); close(); }
                } else if (e.key === 'Tab') { close(); }
            });

            // mousedown を止めて blur より先に選択を確定させる
            menu.addEventListener('mousedown', function(e) { e.preventDefault(); });
            menu.addEventListener('click', function(e) {
                const target = e.target.closest ? e.target.closest('.ses-suggest-item') : null;
                if (target) select(parseInt(target.getAttribute('data-index'), 10));
            });
            window.addEventListener('scroll', function() { if (!menu.hidden) position(); }, true);
            window.addEventListener('resize', function() { if (!menu.hidden) position(); });

            input._sesSuggest = {
                setItems: function(next) { items = api.prepare(next); },
                refresh: refresh,
                close: close
            };
            return input._sesSuggest;
        }
    },
    
    /**
     * HTMLエスケープ（XSS対策）。ユーザー入力由来の文字列をHTMLへ埋め込む際は必ず通すこと。
     */
    escapeHtml: function(s) {
        if (s == null) return '';
        return String(s).replace(/[&<>"']/g, function(c) {
            return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
        });
    },

    notification: {
        load: async function() {
            const container = document.getElementById('notification-list');
            if (!container) return;

            try {
                const list = await SES.api.get('/api/notifications');
                SES.notification.render(list || []);

                const count = await SES.api.get('/api/notifications/unread-count');
                SES.notification.updateBadge(count || 0);
            } catch (e) {
                SES.notification.renderError();
            }
        },

        updateBadge: function(count) {
            const badge = document.getElementById('notification-badge');
            if (!badge) return;
            if (count > 0) {
                badge.innerHTML = (count > 99 ? '99+' : count) + '<span class="visually-hidden">unread messages</span>';
                badge.style.display = 'inline-block';
            } else {
                badge.style.display = 'none';
            }
        },

        render: function(list) {
            const container = $('#notification-list');
            if (!list || list.length === 0) {
                container.html(`<span class="dropdown-item-text text-muted small py-2">${SES.i18n.t('notification.empty')}</span>`);
                return;
            }
            const iconColorMap = {
                'CONTRACT_END': 'text-accent-red',
                'PROPOSAL_STALE': 'text-accent-yellow',
                'BENCH_LONG': 'text-accent-blue',
                'PROJECT_URGENT': 'text-accent-red',
                'RETIRING_ENGINEER': 'text-accent-yellow',
                'AI_MATCHING': 'text-accent-blue',
                'INVOICE_OVERDUE': 'text-accent-red',
                'CONTRACT_DRAFT': 'text-accent-blue',
                'RENEWAL_ESCALATION': 'text-accent-red'
            };
            
            let html = `<div class="d-flex justify-content-end px-3 py-1 border-bottom border-dark"><a href="#" id="mark-all-read" class="small text-muted hover-text-white text-decoration-none">${SES.i18n.t('notification.markAllRead')}</a></div>`;
            
            html += list.map(function(n) {
                const colorClass = iconColorMap[n.type] || 'text-accent-blue';
                const bgClass = !n.isRead ? 'bg-secondary bg-opacity-50 fw-bold' : '';
                // message には要員名等の利用者入力が含まれるため必ずエスケープする（XSS対策）
                return `<a class="dropdown-item notification-item py-2 ${bgClass}" href="#" data-id="${n.id}" data-url="${SES.escapeHtml(n.linkUrl || '#')}">
                            <i class="bi ${SES.escapeHtml(n.icon)} ${colorClass} me-2 mt-1 flex-shrink-0"></i>
                            <span class="notification-item-body">
                                <span class="notification-item-message">${SES.escapeHtml(n.message)}</span>
                                <span class="small text-muted d-block">${SES.escapeHtml(n.date)}</span>
                            </span>
                        </a>`;
            }).join('');
            
            container.html(html);

            container.find('.dropdown-item[data-id]').on('click', async function(e) {
                e.preventDefault();
                const id = $(this).data('id');
                const url = $(this).data('url');
                try {
                    await SES.api.put(`/api/notifications/${id}/read`, {});
                    if (url && url !== '#') {
                        window.location.href = url;
                    } else {
                        SES.notification.load();
                    }
                } catch (err) {
                    console.error(err);
                }
            });

            container.find('#mark-all-read').on('click', async function(e) {
                e.preventDefault();
                try {
                    await SES.api.put('/api/notifications/read-all', {});
                    SES.notification.load();
                } catch (err) {
                    console.error(err);
                }
            });
        },

        renderError: function() {
            $('#notification-list').html(
                '<span class="dropdown-item-text text-danger small py-2">通知を読み込めませんでした</span>'
            );
        }
    },
    
    /**
     * 一覧画面の絞り込みパネルをスマホでは折りたたむ。
     *
     * 一覧画面の検索フォームは項目が最大7つあり、スマホでは全て縦積みになるため
     * データ本体が2画面近く下に押し出されていた。狭い画面のときだけカード見出しを
     * 差し込んで開閉できるようにする。
     *
     * 実装上の要点:
     * - 表示制御はCSS(common.cssの max-width:768px)に一本化し、JSは開閉状態を
     *   data-filter-collapsed 属性で示すだけにする。こうすると画面回転やリサイズで
     *   ブレークポイントを跨いでもJS側の再計算が要らず、広い画面では属性値に関わらず
     *   常に展開表示になる。
     * - 折りたたむと何で絞り込み中か見えなくなるため、適用中の条件数をバッジで出す。
     * - 全一覧画面が同じマークアップ(card > card-body > form#searchForm)なので、
     *   テンプレートは変更せずここで一括して組み立てる。
     */
    filterPanel: {
        init: function() {
            const form = document.getElementById('searchForm');
            if (!form) return;
            const body = form.closest('.card-body');
            const card = body ? body.closest('.card') : null;
            if (!card || card.classList.contains('filter-card')) return;

            card.classList.add('filter-card');
            // 初期状態は折りたたみ。広い画面ではCSS側でこの属性が無視される。
            card.setAttribute('data-filter-collapsed', 'true');
            if (!body.id) body.id = 'filterPanelBody';

            const toggle = document.createElement('button');
            toggle.type = 'button';
            toggle.className = 'filter-toggle';
            toggle.setAttribute('aria-expanded', 'false');
            toggle.setAttribute('aria-controls', body.id);
            toggle.innerHTML =
                '<span class="filter-toggle-label">'
                + '<i class="bi bi-funnel me-2"></i>'
                + '<span></span>'
                + '<span class="filter-toggle-count badge rounded-pill ms-2 d-none"></span>'
                + '</span>'
                + '<i class="bi bi-chevron-down filter-toggle-chevron"></i>';
            // ラベルはテキストとして入れる（翻訳文の取り違えでHTMLが混ざっても壊れないように）
            toggle.querySelector('.filter-toggle-label > span').textContent =
                SES.i18n.t('common.filter.panel');

            toggle.addEventListener('click', function() {
                const collapsed = card.getAttribute('data-filter-collapsed') === 'true';
                card.setAttribute('data-filter-collapsed', collapsed ? 'false' : 'true');
                toggle.setAttribute('aria-expanded', collapsed ? 'true' : 'false');
            });

            card.insertBefore(toggle, body);

            const self = this;
            const refresh = function() { self.updateCount(form, toggle); };
            form.addEventListener('change', refresh);
            form.addEventListener('submit', function() {
                refresh();
                // 検索したら結果を見たいので、狭い画面では自動でたたむ
                if (window.matchMedia('(max-width: 768px)').matches) {
                    card.setAttribute('data-filter-collapsed', 'true');
                    toggle.setAttribute('aria-expanded', 'false');
                }
            });
            refresh();
        },

        /** 値が入っている絞り込み項目の数をバッジに反映する */
        updateCount: function(form, toggle) {
            let count = 0;
            form.querySelectorAll('input, select').forEach(function(el) {
                if (el.disabled || !el.name) return;
                if (['submit', 'button', 'reset', 'file', 'hidden'].indexOf(el.type) !== -1) return;
                if (el.type === 'checkbox' || el.type === 'radio') {
                    if (el.checked) count++;
                    return;
                }
                if (el.value && el.value.trim() !== '') count++;
            });

            const badge = toggle.querySelector('.filter-toggle-count');
            badge.textContent = count;
            badge.title = SES.i18n.t('common.filter.activeCount', count);
            badge.classList.toggle('d-none', count === 0);
        }
    },

    theme: {
        init: function() {
            const savedTheme = localStorage.getItem('ses_theme') || 'light';
            this.applyTheme(savedTheme);

            const toggleBtn = document.getElementById('theme-toggle-btn');
            if (toggleBtn) {
                toggleBtn.addEventListener('click', () => {
                    const currentTheme = document.documentElement.getAttribute('data-bs-theme');
                    const newTheme = currentTheme === 'dark' ? 'light' : 'dark';
                    this.applyTheme(newTheme);
                    localStorage.setItem('ses_theme', newTheme);
                });
            }
        },
        applyTheme: function(theme) {
            document.documentElement.setAttribute('data-bs-theme', theme);

            const iconLight = document.getElementById('theme-icon-light');
            const iconDark = document.getElementById('theme-icon-dark');

            if (iconLight && iconDark) {
                if (theme === 'light') {
                    iconLight.classList.remove('d-none');
                    iconDark.classList.add('d-none');
                } else {
                    iconLight.classList.add('d-none');
                    iconDark.classList.remove('d-none');
                }
            }

            // テーマ変更を各ページモジュールへ通知する（Chart.js の再配色等に利用）
            document.dispatchEvent(new CustomEvent('ses:theme-changed', { detail: { theme: theme } }));
        },

        /**
         * 現在のテーマに応じた Chart.js 用の配色を返す。
         * チャートを持つ各モジュールはこれを参照し、独自に色を定義しないこと。
         */
        chartColors: function() {
            const isLight = document.documentElement.getAttribute('data-bs-theme') === 'light';
            return {
                textColor: isLight ? '#475569' : '#e8eaed',
                gridColor: isLight ? 'rgba(0, 0, 0, 0.1)' : 'rgba(255, 255, 255, 0.1)'
            };
        },

        /**
         * 生成済みチャートへ現在テーマの配色を適用して再描画する。
         * options.scales を実際に存在する軸だけ走査するため、軸構成に依存しない。
         */
        applyChartTheme: function(chart) {
            if (!chart) return;
            const colors = this.chartColors();
            chart.options.color = colors.textColor;
            if (chart.options.plugins && chart.options.plugins.legend && chart.options.plugins.legend.labels) {
                chart.options.plugins.legend.labels.color = colors.textColor;
            }
            const scales = chart.options.scales || {};
            Object.keys(scales).forEach(function(key) {
                const scale = scales[key];
                if (scale.ticks) scale.ticks.color = colors.textColor;
                if (scale.grid) scale.grid.color = colors.gridColor;
                if (scale.title) scale.title.color = colors.textColor;
            });
            chart.update();
        }
    }
};

window.Toast = SES.toast;

// ================== 初期化処理 ==================
document.addEventListener('DOMContentLoaded', function() {
    // 1. 日時表示の更新（1分毎）
    SES.util.updateHeaderDatetime();
    setInterval(SES.util.updateHeaderDatetime, 60000);
    
    // 2. サイドバー初期化（モバイルトグル等）
    SES.sidebar.init();
    
    // 3. 通知の初期化
    SES.notification.load();
    
    // テーマ設定の初期化
    SES.theme.init();
    
    // 4. サジェストの初期化
    SES.autocomplete.init();

    // 5. 絞り込みパネルの折りたたみ（スマホのみ有効。CSS側で制御）
    SES.filterPanel.init();
    
    // 5. ツールチップの初期化 (Bootstrap)
    const tooltipTriggerList = document.querySelectorAll('[data-bs-toggle="tooltip"]');
    [...tooltipTriggerList].map(tooltipTriggerEl => new bootstrap.Tooltip(tooltipTriggerEl));
    
    // 4. 動的ページタイトル設定 (Header Breadcrumb)
    const pageTitleHeader = document.querySelector('.page-title-header');
    if (pageTitleHeader) {
        const titleText = document.title.split(' | ')[0]; // <title>から取得
        if (titleText) {
            pageTitleHeader.textContent = titleText;
        }
    }
});



// ================== CSRF (Cookie → ヘッダー) ==================
// CookieCsrfTokenRepository が発行する XSRF-TOKEN Cookie を読み、
// 更新系リクエストで X-XSRF-TOKEN ヘッダーへ複製する。
SES.csrf = {
    token: function() {
        const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
        return m ? decodeURIComponent(m[1]) : null;
    },
    header: function() {
        const t = this.token();
        return t ? { 'X-XSRF-TOKEN': t } : {};
    }
};

// ================== jQuery グローバルAjaxハンドラー ==================
// セッション切れ・未認証時に自動でログインページへリダイレクトする
$(function() {
    $.ajaxSetup({
        beforeSend: function(xhr, settings) {
            // GET/HEAD/OPTIONS 以外にCSRFヘッダーを付与
            const method = (settings.type || settings.method || 'GET').toUpperCase();
            if (!/^(GET|HEAD|OPTIONS|TRACE)$/.test(method)) {
                const token = SES.csrf.token();
                if (token) {
                    xhr.setRequestHeader('X-XSRF-TOKEN', token);
                }
            }
        },
        complete: function(xhr, status) {
            // レスポンスが JSON ではなく HTML (ログインページ) だった場合
            // → セッション切れの可能性が高い
            const contentType = xhr.getResponseHeader('Content-Type') || '';
            if (xhr.status === 200 && contentType.indexOf('text/html') !== -1) {
                // APIエンドポイントへのリクエストがHTMLを返した = ログインへリダイレクトされた
                // ※ /api/ を含むリクエストに限定してセッション切れを判定する
                //   (OR条件の第2項は「/loginを含まないURL全般」を誤判定するため削除)
                const url = this.url || '';
                if (url.indexOf('/api/') !== -1) {
                    Toast.error('セッションが切れました。再ログインしてください。');
                    setTimeout(function() {
                        window.location.href = '/login';
                    }, 1500);
                }
            }
            // 401 Unauthorized
            if (xhr.status === 401) {
                Toast.error('セッションが切れました。再ログインしてください。');
                setTimeout(function() {
                    window.location.href = '/login';
                }, 1500);
                return;
            }
            // API errors use the same JSON envelope as successful responses.
            // Surface the server message even when a module did not provide an
            // error callback (for example, a failed modal submit).
            if (xhr.status >= 400 && xhr.status !== 401 && (this.url || '').indexOf('/api/') !== -1) {
                let message = null;
                try {
                    const payload = xhr.responseJSON || JSON.parse(xhr.responseText || '{}');
                    message = payload && payload.message;
                    const isScopeViolation = xhr.getResponseHeader('X-SES-Error-Code') === 'BREAK_GLASS_SCOPE_VIOLATION' ||
                        (payload && (payload.data === 'BREAK_GLASS_SCOPE_VIOLATION' || payload.message === 'error.breakGlass.scopeViolation' || payload.message === 'break-glass scope違反です'));
                    if (isScopeViolation) {
                        // break-glass scope違反の受動API要求は画面を描画継続するためToastを抑止する
                        return;
                    }
                } catch (e) { /* non-JSON error pages are handled by CustomErrorController */ }
                const fallback = {
                    400: '入力内容に誤りがあります。',
                    403: 'アクセス権限がありません。',
                    404: '対象データが見つかりません。',
                    409: '他の処理と競合しました。',
                    500: 'サーバーエラーが発生しました。'
                }[xhr.status] || '処理に失敗しました。';
                Toast.error(message || fallback);
            }
        }
    });
});

// ================== 横断検索 (Global Search / Command Palette) ==================
SES.globalSearch = {
    timer: null,
    init: function() {
        const $input = $('#global-search-input');
        const $results = $('#global-search-results');
        if (!$input.length) return;

        $(document).on('keydown', function(e) {
            if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
                e.preventDefault();
                $('#globalSearchModal').modal('show');
            }
        });

        $('#globalSearchModal').on('shown.bs.modal', function() {
            $input.focus().select();
        });

        $input.on('input', function() {
            const query = $(this).val().trim();
            clearTimeout(SES.globalSearch.timer);
            SES.globalSearch.requestId = (SES.globalSearch.requestId || 0) + 1;
            if (query.length < 2) {
                $results.html('<div class="text-center text-muted py-5"><i class="bi bi-search fs-1 mb-2 d-block opacity-50"></i>' + SES.escapeHtml(SES.i18n.t('header.globalSearch.hint')) + '</div>');
                return;
            }
            SES.globalSearch.timer = setTimeout(function() {
                SES.globalSearch.execute(query, SES.globalSearch.requestId);
            }, 300);
        });
    },
    requestId: 0,
    execute: function(query, requestId) {
        const $results = $('#global-search-results');
        $results.html('<div class="text-center text-muted py-5"><div class="spinner-border spinner-border-sm me-2" role="status"></div>' + SES.escapeHtml(SES.i18n.t('header.globalSearch.searching')) + '</div>');
        $.ajax({
            url: '/api/search',
            type: 'GET',
            data: { q: query },
            success: function(res) {
                // 入力中に後続のリクエストが発行されていたら、古い応答は破棄して結果の逆転を防ぐ
                if (requestId !== SES.globalSearch.requestId) return;
                if (res.code === 200) {
                    SES.globalSearch.renderResults(res.data);
                } else {
                    $results.html('<div class="text-center text-danger py-4">' + SES.escapeHtml(res.message || SES.i18n.t('header.globalSearch.error')) + '</div>');
                }
            },
            error: function() {
                if (requestId !== SES.globalSearch.requestId) return;
                $results.html('<div class="text-center text-danger py-4">' + SES.escapeHtml(SES.i18n.t('header.globalSearch.error')) + '</div>');
            }
        });
    },
    renderResults: function(dataMap) {
        const $results = $('#global-search-results');
        let html = '';
        let totalCount = 0;

        const typeLabels = {
            'ENGINEER': { label: SES.i18n.t('search.type.engineer'), icon: 'bi-person', color: 'text-primary' },
            'CUSTOMER': { label: SES.i18n.t('search.type.customer'), icon: 'bi-building', color: 'text-info' },
            'PROJECT': { label: SES.i18n.t('search.type.project'), icon: 'bi-briefcase', color: 'text-success' },
            'CONTRACT': { label: SES.i18n.t('search.type.contract'), icon: 'bi-file-earmark-text', color: 'text-warning' },
            'INVOICE': { label: SES.i18n.t('search.type.invoice'), icon: 'bi-receipt', color: 'text-danger' },
            'PROPOSAL': { label: SES.i18n.t('search.type.proposal'), icon: 'bi-kanban', color: 'text-purple' },
            'QUOTATION': { label: SES.i18n.t('search.type.quotation'), icon: 'bi-calculator', color: 'text-cyan' },
            'BP_COMPANY': { label: SES.i18n.t('search.type.bpCompany'), icon: 'bi-people', color: 'text-secondary' }
        };

        for (const typeKey in dataMap) {
            const list = dataMap[typeKey];
            if (!list || list.length === 0) continue;
            totalCount += list.length;
            const meta = typeLabels[typeKey] || { label: typeKey, icon: 'bi-tag', color: 'text-light' };

            html += '<div class="mb-3"><h6 class="text-muted border-bottom border-dark pb-1 mb-2 small"><i class="bi ' + meta.icon + ' me-1 ' + meta.color + '"></i>' + SES.escapeHtml(meta.label) + ' (' + list.length + ')</h6>';
            html += '<div class="list-group list-group-flush bg-transparent">';
            list.forEach(function(item) {
                html += '<a href="' + SES.escapeHtml(item.url || '#') + '" class="list-group-item list-group-item-action bg-dark text-white border-secondary rounded mb-1 py-2 px-3 hover-bg-secondary">';
                html += '<div class="d-flex w-100 justify-content-between align-items-center">';
                html += '<div><span class="fw-bold me-2">' + SES.escapeHtml(item.title || '') + '</span>';
                if (item.subtitle) {
                    html += '<span class="text-muted small ms-1">' + SES.escapeHtml(item.subtitle) + '</span>';
                }
                html += '</div>';
                if (item.status) {
                    html += '<span class="badge bg-secondary text-light">' + SES.escapeHtml(item.status) + '</span>';
                }
                html += '</div></a>';
            });
            html += '</div></div>';
        }

        if (totalCount === 0) {
            html = '<div class="text-center text-muted py-5"><i class="bi bi-emoji-neutral fs-2 mb-2 d-block opacity-50"></i>該当するデータが見つかりませんでした</div>';
        }
        $results.html(html);
    }
};

$(function() {
    SES.globalSearch.init();
});
