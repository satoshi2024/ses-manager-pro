/**
 * ToDo・通知一覧画面用JavaScript
 */

// 初期化
document.addEventListener('DOMContentLoaded', function() {
    loadTodos(1);
});

// ToDo（通知）一覧を読み込む
async function loadTodos(page) {
    const type = document.getElementById('searchType').value;
    const unreadOnly = document.getElementById('searchUnreadOnly') ? document.getElementById('searchUnreadOnly').checked : document.getElementById('unreadOnly').checked;

    const params = {
        current: page,
        size: 10
    };
    if (type) params.type = type;
    if (unreadOnly) params.unreadOnly = true;

    try {
        const pageData = await SES.api.get('/api/notifications/page', params);
        if (pageData) {
            renderTable(pageData.records);
            renderPagination(pageData);
        }
    } catch (e) {
        console.error('Failed to load todos', e);
    }
}

// テーブル描画
function renderTable(records) {
    const tbody = document.getElementById('todo-table-body');
    if (!records || records.length === 0) {
        tbody.innerHTML = `<tr><td colspan="4" class="text-center text-muted py-4"></td></tr>`;
        return;
    }

    const iconColorMap = {
        'CONTRACT_END': 'text-accent-red',
        'PROPOSAL_STALE': 'text-accent-yellow',
        'BENCH_LONG': 'text-accent-blue',
        'PROJECT_URGENT': 'text-accent-red',
        'RETIRING_ENGINEER': 'text-accent-yellow',
        'AI_MATCHING': 'text-accent-blue'
    };

    let html = '';
    records.forEach(item => {
        const colorClass = iconColorMap[item.type] || 'text-accent-blue';
        const isRead = item.isRead;
        const rowClass = !isRead ? 'fw-bold bg-secondary bg-opacity-10' : '';
        const badgeHtml = isRead 
            ? `<span class="badge bg-secondary"></span>` 
            : `<span class="badge bg-accent-red"></span>`;

        // message には要員名等の利用者入力が含まれるため必ずエスケープする（XSS対策）
        const safeUrl = SES.escapeHtml(item.linkUrl || '#');
        html += `
            <tr class="${rowClass} cursor-pointer" onclick="handleRowClick(event, ${item.id}, this.dataset.url)" data-url="${safeUrl}">
                <td class="ps-4">${badgeHtml}</td>
                <td class="small text-muted">${SES.escapeHtml(item.date || '')}</td>
                <td>
                    <div class="d-flex align-items-center">
                        <i class="bi ${SES.escapeHtml(item.icon)} ${colorClass} me-2 fs-5"></i>
                        <span>${SES.escapeHtml(item.message)}</span>
                    </div>
                </td>
                <td class="text-end pe-4">
                    ${!isRead ? `<button class="btn btn-sm btn-outline-primary" onclick="markAsRead(event, ${item.id})"></button>` : ''}
                </td>
            </tr>
        `;
    });

    tbody.innerHTML = html;
}

// ページネーション描画
function renderPagination(pageData) {
    const info = document.getElementById('pagination-info');
    const controls = document.getElementById('pagination-controls');

    if (pageData.total === 0) {
        info.textContent = SES.i18n.t('common.page.totalZero');
        controls.innerHTML = '';
        return;
    }

    const start = (pageData.current - 1) * pageData.size + 1;
    const end = Math.min(pageData.current * pageData.size, pageData.total);
    info.textContent = SES.i18n.t('common.page.info', [pageData.total, start, end]);

    let html = '';
    
    // Prev
    if (pageData.current > 1) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="#" onclick="event.preventDefault(); loadTodos(${pageData.current - 1})"><i class="bi bi-chevron-left"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" href="#" tabindex="-1" aria-disabled="true"><i class="bi bi-chevron-left"></i></a></li>`;
    }

    // Pages (Simplified)
    for (let i = 1; i <= pageData.pages; i++) {
        if (i === pageData.current) {
            html += `<li class="page-item active"><a class="page-link bg-primary border-primary" href="#" onclick="event.preventDefault()">${i}</a></li>`;
        } else {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="#" onclick="event.preventDefault(); loadTodos(${i})">${i}</a></li>`;
        }
    }

    // Next
    if (pageData.current < pageData.pages) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="#" onclick="event.preventDefault(); loadTodos(${pageData.current + 1})"><i class="bi bi-chevron-right"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" href="#" tabindex="-1" aria-disabled="true"><i class="bi bi-chevron-right"></i></a></li>`;
    }

    controls.innerHTML = html;
}

// 行クリックハンドラ
async function handleRowClick(e, id, url) {
    // ボタンクリック時は行クリックの遷移を発火させない
    if (e.target.tagName === 'BUTTON') return;
    
    try {
        await SES.api.put(`/api/notifications/${id}/read`, {});
        if (url && url !== '#') {
            window.location.href = url;
        } else {
            // 現在のページをリロードし、ヘッダーバッジも更新
            loadTodos(1);
            SES.notification.load();
        }
    } catch (err) {
        console.error(err);
    }
}

// 既読にするボタンハンドラ
async function markAsRead(e, id) {
    e.stopPropagation();
    try {
        await SES.api.put(`/api/notifications/${id}/read`, {});
        loadTodos(1);
        SES.notification.load();
    } catch (err) {
        console.error(err);
    }
}

// すべて既読にする
async function markAllAsRead() {
    try {
        await SES.api.put('/api/notifications/read-all', {});
        SES.toast.success(SES.i18n.t('todo.msg.mark_all_read_success'));
        loadTodos(1);
        SES.notification.load();
    } catch (err) {
        console.error(err);
    }
}
