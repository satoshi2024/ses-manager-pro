/**
 * ToDo・通知一覧画面用JavaScript
 */

// 初期化
document.addEventListener('DOMContentLoaded', function() {
    loadTasks();
    loadTodos(1);
});

// タスク一覧の読み込み
async function loadTasks() {
    try {
        const res = await $.ajax({ url: '/api/tasks', type: 'GET' });
        if (res && res.code === 200) {
            renderTaskTable(res.data);
        }
    } catch (e) {
        console.error('Failed to load tasks', e);
    }
}

function renderTaskTable(tasks) {
    const tbody = document.getElementById('task-table-body');
    if (!tbody) return;
    if (!tasks || tasks.length === 0) {
        tbody.innerHTML = `<tr><td colspan="5" class="text-center text-muted py-4">登録されたタスクはありません</td></tr>`;
        return;
    }

    const priorityBadge = {
        'HIGH': '<span class="badge bg-danger">高</span>',
        'MEDIUM': '<span class="badge bg-warning text-dark">中</span>',
        'LOW': '<span class="badge bg-secondary">低</span>'
    };

    const statusBadge = {
        'NOT_STARTED': '<span class="badge bg-secondary">未着手</span>',
        'IN_PROGRESS': '<span class="badge bg-info text-dark">進行中</span>',
        'COMPLETED': '<span class="badge bg-success">完了</span>',
        'CANCELLED': '<span class="badge bg-dark text-muted">取消</span>'
    };

    let html = '';
    tasks.forEach(t => {
        const isTerminal = t.status === 'COMPLETED' || t.status === 'CANCELLED';
        const isOverdue = t.dueDate && new Date(t.dueDate) < new Date().setHours(0,0,0,0) && !isTerminal;
        const dueText = t.dueDate ? (isOverdue ? `<span class="text-danger fw-bold"><i class="bi bi-exclamation-triangle me-1"></i>${t.dueDate} (超過)</span>` : t.dueDate) : '<span class="text-muted">期限なし</span>';

        html += `
            <tr class="${isTerminal ? 'opacity-75' : ''}">
                <td class="ps-4">${statusBadge[t.status] || t.status}</td>
                <td>${priorityBadge[t.priority] || t.priority}</td>
                <td>
                    <div class="fw-bold text-light">${SES.escapeHtml(t.title)}</div>
                    ${t.description ? `<div class="small text-muted">${SES.escapeHtml(t.description)}</div>` : ''}
                </td>
                <td>${dueText}</td>
                <td class="text-end pe-4">
                    ${!isTerminal ? `
                        ${t.status === 'NOT_STARTED' ? `<button class="btn btn-sm btn-outline-info me-1" onclick="updateTaskStatus(${t.id}, 'IN_PROGRESS')">進行中へ</button>` : ''}
                        ${t.status === 'IN_PROGRESS' ? `<button class="btn btn-sm btn-success me-1" onclick="updateTaskStatus(${t.id}, 'COMPLETED')">完了</button>` : ''}
                        <button class="btn btn-sm btn-outline-secondary" onclick="updateTaskStatus(${t.id}, 'CANCELLED')">取消</button>
                    ` : '<span class="small text-muted">処理完了</span>'}
                </td>
            </tr>
        `;
    });
    tbody.innerHTML = html;
}

function openNewTaskModal() {
    document.getElementById('taskForm').reset();
    document.getElementById('task-id').value = '';
    loadUserOptions();
}

function loadUserOptions() {
    const $select = $('#task-assignee-user-id');
    if (!$select.length) return;
    $.ajax({
        url: '/api/autocomplete/assignable-users',
        type: 'GET',
        success: function(res) {
            if (res && res.code === 200 && res.data) {
                let html = '';
                res.data.forEach(u => {
                    html += `<option value="${u.id}">${SES.escapeHtml(u.realName || u.username)}</option>`;
                });
                $select.html(html);
            }
        },
        error: function() {
            $select.html('<option value="">読み込み失敗</option>');
        }
    });
}

async function saveTask() {
    const title = document.getElementById('task-title').value.trim();
    if (!title) {
        Toast.error('タスク件名を入力してください');
        return;
    }
    const assigneeUserId = document.getElementById('task-assignee-user-id').value;
    if (!assigneeUserId) {
        Toast.error('担当者を選択してください');
        return;
    }
    const description = document.getElementById('task-description').value.trim();
    const priority = document.getElementById('task-priority').value;
    const dueDate = document.getElementById('task-due-date').value || null;

    try {
        const res = await $.ajax({
            url: '/api/tasks',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({
                title: title,
                assigneeUserId: parseInt(assigneeUserId),
                description: description,
                priority: priority,
                dueDate: dueDate
            })
        });
        if (res && res.code === 200) {
            Toast.success('タスクを登録しました');
            const modalEl = document.getElementById('taskModal');
            const modalInstance = bootstrap.Modal.getInstance(modalEl);
            if (modalInstance) modalInstance.hide();
            loadTasks();
        } else {
            Toast.error(res.message || 'タスクの登録に失敗しました');
        }
    } catch (e) {
        let msg = 'タスクの登録に失敗しました';
        try {
            if (e.responseJSON && e.responseJSON.message) msg = e.responseJSON.message;
        } catch(err) {}
        Toast.error(msg);
    }
}

async function updateTaskStatus(taskId, status) {
    try {
        const res = await $.ajax({
            url: `/api/tasks/${taskId}/status?status=${status}`,
            type: 'PUT'
        });
        if (res && res.code === 200) {
            Toast.success('タスクを更新しました');
            loadTasks();
        } else {
            Toast.error(res.message || 'タスクの更新に失敗しました');
        }
    } catch (e) {
        let msg = 'タスクの更新に失敗しました';
        try {
            if (e.responseJSON && e.responseJSON.message) msg = e.responseJSON.message;
        } catch(err) {}
        Toast.error(msg);
    }
}

async function convertNotificationToTask(event, notificationId) {
    event.stopPropagation();
    try {
        const res = await $.ajax({
            url: `/api/tasks/from-notification/${notificationId}`,
            type: 'POST'
        });
        if (res && res.code === 200) {
            Toast.success('通知からタスクを作成しました');
            loadTasks();
        } else {
            Toast.error(res.message || 'タスクの作成に失敗しました');
        }
    } catch (e) {
        let msg = 'タスクの作成に失敗しました';
        try {
            if (e.responseJSON && e.responseJSON.message) msg = e.responseJSON.message;
        } catch(err) {}
        Toast.error(msg);
    }
}

// ToDo（通知）一覧を読み込む
async function loadTodos(page) {
    const typeSelect = document.getElementById('searchType');
    const type = typeSelect ? typeSelect.value : '';
    const unreadCheck = document.getElementById('searchUnreadOnly') || document.getElementById('unreadOnly');
    const unreadOnly = unreadCheck ? unreadCheck.checked : false;

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
    if (!tbody) return;
    if (!records || records.length === 0) {
        tbody.innerHTML = `<tr><td colspan="4" class="text-center text-muted py-4">通知はありません</td></tr>`;
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
            ? `<span class="badge bg-secondary">既読</span>`
            : `<span class="badge bg-accent-red">未読</span>`;

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
                    <button class="btn btn-sm btn-outline-primary me-1" onclick="convertNotificationToTask(event, ${item.id})" title="タスクに変換"><i class="bi bi-plus-square me-1"></i>タスク化</button>
                    ${!isRead ? `<button class="btn btn-sm btn-outline-secondary" onclick="markAsRead(event, ${item.id})">既読</button>` : ''}
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

    if (!info || !controls) return;
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
    if (e.target.tagName === 'BUTTON' || e.target.closest('button')) return;
    
    try {
        await SES.api.put(`/api/notifications/${id}/read`, {});
        if (url && url !== '#') {
            window.location.href = url;
        } else {
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
