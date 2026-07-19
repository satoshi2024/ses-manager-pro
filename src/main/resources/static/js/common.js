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
                
                // HTTPステータスエラーハンドリング
                if (response.status === 401) {
                    window.location.href = '/login?error=timeout';
                    return null;
                }
                if (response.status === 403) {
                    SES.toast.error('アクセス権限がありません。');
                    return null;
                }
                if (response.status >= 500) {
                    SES.toast.error('サーバーエラーが発生しました。');
                    return null;
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
    autocomplete: {
        init: function() {
            this.loadDatalist('/api/autocomplete/engineers', 'engineer-list');
            this.loadDatalist('/api/autocomplete/customers', 'customer-list');
            this.loadDatalist('/api/autocomplete/projects', 'project-list');
            this.loadDatalist('/api/autocomplete/users', 'user-list');
        },
        loadDatalist: async function(url, listId) {
            const datalist = document.getElementById(listId);
            if (!datalist) return;
            try {
                const names = await SES.api.get(url);
                if (names && names.length > 0) {
                    let html = '';
                    names.forEach(name => {
                        // 全特殊文字をエスケープ（XSS対策強化）
                        const safeName = SES.escapeHtml(name);
                        html += `<option value="${safeName}"></option>`;
                    });
                    datalist.innerHTML = html;
                }
            } catch (e) {
                console.error(`Failed to load autocomplete data for ${listId}`, e);
            }
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
                'CONTRACT_DRAFT': 'text-accent-blue'
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

// モックアップ用グローバル関数（実装前の画面デモ用）
window.matchAI = function(engineerId) {
    SES.toast.info('AIマッチング処理を実行しています...');
    setTimeout(() => {
        SES.toast.success('マッチングが完了しました。最適な案件が見つかりました。');
    }, 2000);
};

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

