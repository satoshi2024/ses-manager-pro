/**
 * ========================================
 * SES Manager Pro - 共通JavaScriptユーティリティ
 * ========================================
 */

const SES = {
    
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
            // CSRF Token - if using Spring Security default CSRF (disabled for REST in this app, but good practice)
            const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
            const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
            
            if (csrfToken && csrfHeader) {
                options.headers = options.headers || {};
                options.headers[csrfHeader] = csrfToken;
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
                <div id="${toastId}" class="toast align-items-center ${bgClass} ${borderClass} border text-white" role="alert" aria-live="assertive" aria-atomic="true">
                    <div class="d-flex">
                        <div class="toast-body d-flex align-items-center">
                            <i class="bi ${iconClass} ${textClass} fs-5 me-2"></i>
                            <span>${message}</span>
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
        
        updateHeaderDatetime: function() {
            const el = document.getElementById('header-datetime');
            if (!el) return;
            
            const now = new Date();
            const days = ['日', '月', '火', '水', '木', '金', '土'];
            
            const dateStr = `${now.getFullYear()}年${String(now.getMonth()+1).padStart(2,'0')}月${String(now.getDate()).padStart(2,'0')}日`;
            const dayStr = `(${days[now.getDay()]})`;
            const timeStr = `${String(now.getHours()).padStart(2,'0')}:${String(now.getMinutes()).padStart(2,'0')}`;
            
            el.innerHTML = `${dateStr} ${dayStr} <span class="fw-bold ms-1 text-white">${timeStr}</span>`;
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
            
            if (toggleBtn && sidebar) {
                toggleBtn.addEventListener('click', () => {
                    sidebar.classList.add('show');
                });
            }
            
            if (closeBtn && sidebar) {
                closeBtn.addEventListener('click', () => {
                    sidebar.classList.remove('show');
                });
            }
            
            // モバイル時の画面外クリックで閉じる
            document.addEventListener('click', (e) => {
                if (window.innerWidth <= 768 && sidebar && sidebar.classList.contains('show')) {
                    if (!sidebar.contains(e.target) && !toggleBtn.contains(e.target)) {
                        sidebar.classList.remove('show');
                    }
                }
            });
        }
    }
};

// ================== 初期化処理 ==================
document.addEventListener('DOMContentLoaded', function() {
    // 1. 日時表示の更新（1分毎）
    SES.util.updateHeaderDatetime();
    setInterval(SES.util.updateHeaderDatetime, 60000);
    
    // 2. サイドバー初期化（モバイルトグル等）
    SES.sidebar.init();
    
    // 3. ツールチップの初期化 (Bootstrap)
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
