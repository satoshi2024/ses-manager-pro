/**
 * 要員セルフサービス - マイライフサイクル JavaScript モジュール
 */
SES.myLifecycle = {
    currentCaseId: null,

    init: function() {
        const container = document.querySelector('[data-case-id]');
        if (container) {
            this.currentCaseId = container.getAttribute('data-case-id');
            this.loadDetail(this.currentCaseId);
        } else if (document.getElementById('myCasesTableBody')) {
            this.loadCases();
            this.loadPendingTasks();
        }
    },

    // ==========================================
    // 案件一覧 & 未完了タスク一覧
    // ==========================================
    loadCases: async function() {
        try {
            const cases = (await SES.api.get('/api/my/lifecycle/cases')) || [];
            this.renderCases(cases);
        } catch (e) {
            console.error(e);
            SES.toast.error(e.message || '案件一覧の取得に失敗しました');
        }
    },

    renderCases: function(cases) {
        const tbody = document.getElementById('myCasesTableBody');
        if (!tbody) return;
        if (cases.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="text-center py-4 text-muted">手続き案件はありません</td></tr>';
            return;
        }

        const typeMap = {
            'JOIN': { label: '入社手続き', class: 'bg-primary' },
            'ASSIGNMENT': { label: '配属手続き', class: 'bg-info text-dark' },
            'TRANSFER': { label: '異動手続き', class: 'bg-secondary' },
            'LEAVE': { label: '休職手続き', class: 'bg-warning text-dark' },
            'REINSTATEMENT': { label: '復職手続き', class: 'bg-success' },
            'RESIGNATION': { label: '退社手続き', class: 'bg-danger' }
        };

        const statusMap = {
            'ACTIVE': { label: '進行中', class: 'bg-primary' },
            'ON_HOLD': { label: '保留中', class: 'bg-warning text-dark' },
            'COMPLETED': { label: '完了', class: 'bg-success' },
            'CANCELLED': { label: '中止', class: 'bg-danger' }
        };

        let html = '';
        cases.forEach(c => {
            const t = typeMap[c.lifecycleType] || { label: c.lifecycleType, class: 'bg-secondary' };
            const s = statusMap[c.status] || { label: c.status, class: 'bg-secondary' };
            const total = c.tasks ? c.tasks.length : 0;
            const completed = c.tasks ? c.tasks.filter(tk => tk.status === 'COMPLETED' || tk.status === 'WAIVED').length : 0;
            const pct = total > 0 ? Math.round((completed / total) * 100) : 0;

            html += `
                <tr>
                    <td class="ps-4"><span class="badge ${t.class} fs-6">${t.label}</span></td>
                    <td class="text-muted small">${SES.escapeHtml(c.anchorDate || '-')}</td>
                    <td style="min-width: 140px;">
                        <div class="d-flex align-items-center gap-2">
                            <div class="progress flex-grow-1 bg-dark" style="height: 6px;">
                                <div class="progress-bar bg-success" style="width: ${pct}%"></div>
                            </div>
                            <span class="small text-muted">${completed}/${total}</span>
                        </div>
                    </td>
                    <td><span class="badge ${s.class}">${s.label}</span></td>
                    <td class="text-end pe-4">
                        <a href="/my/lifecycle/${c.id}" class="btn btn-sm btn-outline-light">
                            <i class="bi bi-arrow-right-circle me-1"></i>詳細・提出
                        </a>
                    </td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
    },

    loadPendingTasks: async function() {
        try {
            const tasks = (await SES.api.get('/api/my/lifecycle/tasks/pending')) || [];
            const badge = document.getElementById('myPendingTasksBadge');
            if (badge) {
                if (tasks.length > 0) {
                    badge.textContent = tasks.length;
                    badge.style.display = 'inline-block';
                } else {
                    badge.style.display = 'none';
                }
            }
            this.renderPendingTasks(tasks);
        } catch (e) {
            console.error(e);
            SES.toast.error(e.message || '提出タスクの取得に失敗しました');
        }
    },

    renderPendingTasks: function(tasks) {
        const list = document.getElementById('myPendingTasksList');
        if (!list) return;
        if (tasks.length === 0) {
            list.innerHTML = '<li class="list-group-item bg-transparent text-center py-4 text-muted">現在提出が必要なタスクはありません</li>';
            return;
        }

        let html = '';
        tasks.forEach(tk => {
            html += `
                <li class="list-group-item bg-transparent border-dark d-flex justify-content-between align-items-center py-3">
                    <div>
                        <div class="fw-bold text-light">${SES.escapeHtml(tk.taskName)}</div>
                        <div class="text-muted small">期日: ${SES.escapeHtml(tk.dueDate || '-')}</div>
                    </div>
                    <div>
                        <button type="button" class="btn btn-sm btn-success" onclick="SES.myLifecycle.openCompleteModal(this)" data-task-id="${tk.id}" data-task-name="${SES.escapeHtml(tk.taskName || '')}" data-task-description="${SES.escapeHtml(tk.description || '')}" data-evidence-type="${SES.escapeHtml(tk.evidenceType || 'NONE')}" title="タスクを提出" aria-label="タスクを提出">
                            <i class="bi bi-check2 me-1"></i>提出・完了
                        </button>
                    </div>
                </li>
            `;
        });
        list.innerHTML = html;
    },

    openCompleteModal: function(buttonOrTaskId, taskName, desc, evidenceType) {
        const taskId = buttonOrTaskId && buttonOrTaskId.dataset
            ? buttonOrTaskId.dataset.taskId
            : buttonOrTaskId;
        if (buttonOrTaskId && buttonOrTaskId.dataset) {
            taskName = buttonOrTaskId.dataset.taskName;
            desc = buttonOrTaskId.dataset.taskDescription;
            evidenceType = buttonOrTaskId.dataset.evidenceType;
        }
        document.getElementById('myTaskId').value = taskId;
        document.getElementById('myTaskTitleLabel').textContent = taskName;
        document.getElementById('myTaskDescLabel').textContent = desc || '';
        document.getElementById('myCompletionComment').value = '';
        const docSection = document.getElementById('myDocIdSection');
        if (evidenceType === 'DOCUMENT_LINK') {
            docSection.style.display = 'block';
        } else {
            docSection.style.display = 'none';
        }
        new bootstrap.Modal(document.getElementById('myCompleteModal')).show();
    },

    submitComplete: async function() {
        const taskId = document.getElementById('myTaskId').value;
        const comment = document.getElementById('myCompletionComment').value;
        const docId = document.getElementById('myDocumentId').value;

        const payload = {
            completionComment: comment || undefined,
            documentId: docId ? parseInt(docId, 10) : undefined
        };

        try {
            await SES.api.post(`/api/my/lifecycle/tasks/${encodeURIComponent(taskId)}/complete`, payload);
            SES.toast.success('タスクを完了報告しました');
            bootstrap.Modal.getInstance(document.getElementById('myCompleteModal')).hide();
            await this.loadCases();
            await this.loadPendingTasks();
        } catch (e) {
            console.error(e);
            SES.toast.error(e.message || '報告に失敗しました');
        }
    },

    // ==========================================
    // 詳細画面
    // ==========================================
    loadDetail: async function(caseId) {
        try {
            const caseDto = await SES.api.get(`/api/my/lifecycle/cases/${encodeURIComponent(caseId)}`);
            this.renderDetail(caseDto);
        } catch (e) {
            console.error(e);
            SES.toast.error(e.message || '詳細取得に失敗しました');
        }
    },

    renderDetail: function(caseDto) {
        document.getElementById('myBreadcrumbCaseNo').textContent = caseDto.title || caseDto.caseNo;
        document.getElementById('myCaseTitleHeader').textContent = caseDto.title || caseDto.caseNo;
        document.getElementById('myAnchorDateHeader').textContent = caseDto.anchorDate || '-';
        document.getElementById('myCreatedAtHeader').textContent = caseDto.createdAt ? caseDto.createdAt.replace('T', ' ').substring(0, 10) : '-';

        const typeMap = {
            'JOIN': '入社手続き', 'ASSIGNMENT': '現場配属手続き', 'TRANSFER': '所属異動手続き',
            'LEAVE': '休職手続き', 'REINSTATEMENT': '復職手続き', 'RESIGNATION': '退社手続き'
        };
        const statusMap = {
            'ACTIVE': { label: '進行中', class: 'bg-primary' },
            'ON_HOLD': { label: '保留中', class: 'bg-warning text-dark' },
            'COMPLETED': { label: '完了', class: 'bg-success' },
            'CANCELLED': { label: '中止', class: 'bg-danger' }
        };

        document.getElementById('myCaseTypeBadge').textContent = typeMap[caseDto.lifecycleType] || caseDto.lifecycleType;
        const s = statusMap[caseDto.status] || { label: caseDto.status, class: 'bg-secondary' };
        const statusBadge = document.getElementById('myCaseStatusBadge');
        statusBadge.textContent = s.label;
        statusBadge.className = `badge ${s.class}`;

        const tasks = caseDto.tasks || [];
        const total = tasks.length;
        const done = tasks.filter(t => t.status === 'COMPLETED' || t.status === 'WAIVED').length;
        const pct = total > 0 ? Math.round((done / total) * 100) : 0;
        document.getElementById('myProgressSummary').textContent = `${done} / ${total} 完了`;
        document.getElementById('myProgressPercentage').textContent = `${pct}%`;
        document.getElementById('myProgressBar').style.width = `${pct}%`;

        this.renderDetailTasks(tasks, caseDto.status);
    },

    renderDetailTasks: function(tasks, caseStatus) {
        const tbody = document.getElementById('myTasksTableBody');
        if (!tbody) return;
        if (tasks.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" class="text-center py-4 text-muted">公開タスクはありません</td></tr>';
            return;
        }

        let html = '';
        tasks.forEach(t => {
            const statusClassMap = {
                'PENDING': 'bg-secondary',
                'IN_PROGRESS': 'bg-primary',
                'COMPLETED': 'bg-success',
                'WAIVED': 'bg-warning text-dark'
            };
            const statusLabelMap = {
                'PENDING': '未着手',
                'IN_PROGRESS': '対応可能',
                'COMPLETED': '完了済み',
                'WAIVED': '免除'
            };

            const sClass = statusClassMap[t.status] || 'bg-secondary';
            const sLabel = statusLabelMap[t.status] || t.status;

            let actionBtn = '-';
            if (caseStatus === 'ACTIVE' && (t.status === 'IN_PROGRESS' || t.status === 'PENDING')) {
                actionBtn = `<button type="button" class="btn btn-sm btn-success" onclick="SES.myLifecycle.openDetailCompleteModal(this)" data-task-id="${t.id}" data-evidence-type="${SES.escapeHtml(t.evidenceType || 'NONE')}" title="タスクを完了" aria-label="タスクを完了"><i class="bi bi-check-lg me-1"></i>完了・提出</button>`;
            }

            html += `
                <tr>
                    <td class="ps-4 text-muted">${t.stepOrder || '-'}</td>
                    <td>
                        <div class="fw-bold text-light">${SES.escapeHtml(t.taskName)}</div>
                        <div class="text-muted small">${SES.escapeHtml(t.description || '')}</div>
                    </td>
                    <td class="text-muted small">${SES.escapeHtml(t.dueDate || '-')}</td>
                    <td><span class="badge ${sClass}">${sLabel}</span></td>
                    <td class="text-light small">${SES.escapeHtml(t.completionComment || '-')}</td>
                    <td class="text-end pe-4">${actionBtn}</td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
    },

    openDetailCompleteModal: function(buttonOrTaskId, evidenceType) {
        const taskId = buttonOrTaskId && buttonOrTaskId.dataset
            ? buttonOrTaskId.dataset.taskId
            : buttonOrTaskId;
        if (buttonOrTaskId && buttonOrTaskId.dataset) {
            evidenceType = buttonOrTaskId.dataset.evidenceType;
        }
        document.getElementById('myDetailTaskId').value = taskId;
        document.getElementById('myDetailCompletionComment').value = '';
        const docSection = document.getElementById('myDetailDocIdSection');
        if (evidenceType === 'DOCUMENT_LINK') {
            docSection.style.display = 'block';
        } else {
            docSection.style.display = 'none';
        }
        new bootstrap.Modal(document.getElementById('myDetailCompleteModal')).show();
    },

    submitDetailComplete: async function() {
        const taskId = document.getElementById('myDetailTaskId').value;
        const comment = document.getElementById('myDetailCompletionComment').value;
        const docId = document.getElementById('myDetailDocumentId').value;

        const payload = {
            completionComment: comment || undefined,
            documentId: docId ? parseInt(docId, 10) : undefined
        };

        try {
            await SES.api.post(`/api/my/lifecycle/tasks/${encodeURIComponent(taskId)}/complete`, payload);
            SES.toast.success('タスクを完了報告しました');
            bootstrap.Modal.getInstance(document.getElementById('myDetailCompleteModal')).hide();
            await this.loadDetail(this.currentCaseId);
        } catch (e) {
            console.error(e);
            SES.toast.error(e.message || '報告に失敗しました');
        }
    }
};

$(document).ready(function() {
    SES.myLifecycle.init();
});
