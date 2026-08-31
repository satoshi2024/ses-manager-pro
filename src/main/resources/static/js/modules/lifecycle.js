/**
 * ライフサイクル管理モジュール (JavaScript)
 */
SES.lifecycle = {
    currentCaseId: null,

    init: function() {
        const container = document.querySelector('[data-case-id]');
        if (container) {
            this.currentCaseId = container.getAttribute('data-case-id');
            this.loadDetail(this.currentCaseId);
        } else if (document.getElementById('casesTableBody')) {
            this.loadCases();
            this.loadMyTasks();
        } else if (document.getElementById('templatesTableBody')) {
            this.loadTemplates();
        }
    },

    // ==========================================
    // 案件一覧
    // ==========================================
    loadCases: async function() {
        const type = document.getElementById('filterType') ? document.getElementById('filterType').value : '';
        const status = document.getElementById('filterStatus') ? document.getElementById('filterStatus').value : '';
        const fromDate = document.getElementById('filterFromDate') ? document.getElementById('filterFromDate').value : '';
        const toDate = document.getElementById('filterToDate') ? document.getElementById('filterToDate').value : '';

        const params = {};
        if (type) params.lifecycleType = type;
        if (status) params.status = status;
        if (fromDate) params.fromDate = fromDate;
        if (toDate) params.toDate = toDate;

        try {
            const cases = await SES.api.get('/api/lifecycle/cases', params);
            this.renderCases(cases || []);
        } catch (e) {
            console.error(e);
            console.error(e.message || '案件一覧の取得に失敗しました');
        }
    },

    renderCases: function(cases) {
        const tbody = document.getElementById('casesTableBody');
        if (!tbody) return;
        if (cases.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="text-center py-4 text-muted">該当する案件はありません</td></tr>';
            return;
        }

        const typeMap = {
            'JOIN': { label: '入社', class: 'bg-primary' },
            'ASSIGNMENT': { label: '現場配属', class: 'bg-info text-dark' },
            'TRANSFER': { label: '所属異動', class: 'bg-secondary' },
            'LEAVE': { label: '休職', class: 'bg-warning text-dark' },
            'REINSTATEMENT': { label: '復職', class: 'bg-success' },
            'RESIGNATION': { label: '退社', class: 'bg-danger' }
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
                    <td class="ps-4 fw-bold font-monospace text-theme">${SES.escapeHtml(c.caseNo)}</td>
                    <td><span class="badge ${t.class}">${t.label}</span></td>
                    <td class="text-theme">${SES.escapeHtml(c.engineerName || '-')}</td>
                    <td class="text-muted small">${SES.escapeHtml(c.anchorDate || '-')}</td>
                    <td><span class="badge ${s.class}">${s.label}</span></td>
                    <td style="min-width: 160px;">
                        <div class="d-flex align-items-center gap-2">
                            <div class="progress flex-grow-1 bg-tertiary" style="height: 6px;">
                                <div class="progress-bar bg-success" style="width: ${pct}%"></div>
                            </div>
                            <span class="small text-muted">${completed}/${total}</span>
                        </div>
                    </td>
                    <td class="text-end pe-4">
                        <a href="/lifecycle/${encodeURIComponent(c.id)}" class="btn btn-sm btn-outline-secondary">
                            <i class="bi bi-eye me-1"></i>詳細
                        </a>
                    </td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
    },

    // ==========================================
    // 自担当タスク一覧
    // ==========================================
    loadMyTasks: async function() {
        try {
            const tasks = (await SES.api.get('/api/lifecycle/tasks/my-pending')) || [];
            const countBadge = document.getElementById('myTasksCount');
            if (countBadge) {
                if (tasks.length > 0) {
                    countBadge.textContent = tasks.length;
                    countBadge.style.display = 'inline-block';
                } else {
                    countBadge.style.display = 'none';
                }
            }
            this.renderMyTasks(tasks);
        } catch (e) {
            console.error(e);
            console.error(e.message || '自担当タスクの取得に失敗しました');
        }
    },

    renderMyTasks: function(tasks) {
        const tbody = document.getElementById('myTasksTableBody');
        if (!tbody) return;
        if (tasks.length === 0) {
            tbody.innerHTML = '<tr><td colspan="6" class="text-center py-4 text-muted">現在自担当の未完了タスクはありません</td></tr>';
            return;
        }

        let html = '';
        tasks.forEach(tk => {
            const isBlocking = tk.isBlocking === 1 ? '<span class="badge bg-danger">阻害</span>' : '<span class="badge bg-secondary">任意</span>';
            const statusBadge = tk.status === 'IN_PROGRESS' ? '<span class="badge bg-primary">着手可能</span>' : '<span class="badge bg-secondary">待機中</span>';

            html += `
                <tr>
                    <td class="ps-4 font-monospace text-theme">${SES.escapeHtml(tk.taskCode)}</td>
                    <td class="text-theme fw-bold">${SES.escapeHtml(tk.taskName)}</td>
                    <td class="text-muted small">${SES.escapeHtml(tk.dueDate || '-')}</td>
                    <td>${isBlocking}</td>
                    <td>${statusBadge}</td>
                    <td class="text-end pe-4">
                        <a href="/lifecycle/${encodeURIComponent(tk.caseId)}" class="btn btn-sm btn-primary">
                            <i class="bi bi-box-arrow-up-right me-1"></i>案件へ
                        </a>
                    </td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
    },

    // ==========================================
    // 案件起票
    // ==========================================
    openCreateModal: async function() {
        const engSelect = document.getElementById('createEngineerId');
        if (engSelect && engSelect.children.length <= 1) {
            try {
                const page = await SES.api.get('/api/engineers', { size: 500 });
                if (page && page.records) {
                    let opts = '<option value="">要員を選択してください</option>';
                    page.records.forEach(e => {
                        opts += `<option value="${e.id}">${SES.escapeHtml(e.fullName)} (${SES.escapeHtml(e.employmentType || '-')})</option>`;
                    });
                    engSelect.innerHTML = opts;
                }
            } catch (e) {
                console.error(e);
                console.error(e.message || '要員一覧の取得に失敗しました');
            }
        }
        const today = SES.util.getLocalDateString();
        if (document.getElementById('createAnchorDate')) {
            document.getElementById('createAnchorDate').value = today;
        }
        const modal = new bootstrap.Modal(document.getElementById('createCaseModal'));
        modal.show();
    },

    submitCreateCase: async function() {
        const engineerId = document.getElementById('createEngineerId').value;
        const lifecycleType = document.getElementById('createLifecycleType').value;
        const anchorDate = document.getElementById('createAnchorDate').value;
        const title = document.getElementById('createTitle').value;
        const remarks = document.getElementById('createRemarks').value;

        if (!engineerId || !lifecycleType || !anchorDate) {
            SES.toast.warning('必須項目を入力してください');
            return;
        }

        const payload = {
            engineerId: parseInt(engineerId, 10),
            lifecycleType: lifecycleType,
            anchorDate: anchorDate,
            title: title || undefined,
            remarks: remarks || undefined
        };

        try {
            const created = await SES.api.post('/api/lifecycle/cases', payload);
            SES.toast.success('案件を起票しました');
            bootstrap.Modal.getInstance(document.getElementById('createCaseModal')).hide();
            window.location.href = '/lifecycle/' + encodeURIComponent(created.id);
        } catch (e) {
            console.error(e);
            console.error(e.message || '案件起票に失敗しました');
        }
    },

    // ==========================================
    // 案件詳細
    // ==========================================
    loadDetail: async function(caseId) {
        try {
            const caseDto = await SES.api.get(`/api/lifecycle/cases/${encodeURIComponent(caseId)}`);
            this.renderDetail(caseDto);
        } catch (e) {
            console.error(e);
            console.error(e.message || '詳細の読み込みに失敗しました');
        }
    },

    renderDetail: function(caseDto) {
        document.getElementById('breadcrumbCaseNo').textContent = caseDto.caseNo;
        document.getElementById('caseTitleHeader').textContent = caseDto.title || caseDto.caseNo;
        document.getElementById('engineerNameHeader').textContent = caseDto.engineerName || '-';
        document.getElementById('anchorDateHeader').textContent = caseDto.anchorDate || '-';
        document.getElementById('templateVersionHeader').textContent = caseDto.templateVersion ? `v${caseDto.templateVersion}` : '-';
        document.getElementById('createdAtHeader').textContent = caseDto.createdAt ? caseDto.createdAt.replace('T', ' ').substring(0, 16) : '-';

        const typeMap = {
            'JOIN': '入社 (JOIN)', 'ASSIGNMENT': '現場配属 (ASSIGNMENT)', 'TRANSFER': '所属異動 (TRANSFER)',
            'LEAVE': '休職 (LEAVE)', 'REINSTATEMENT': '復職 (REINSTATEMENT)', 'RESIGNATION': '退社 (RESIGNATION)'
        };
        const statusMap = {
            'ACTIVE': { label: '進行中', class: 'bg-primary' },
            'ON_HOLD': { label: '保留中', class: 'bg-warning text-dark' },
            'COMPLETED': { label: '完了', class: 'bg-success' },
            'CANCELLED': { label: '中止', class: 'bg-danger' }
        };

        document.getElementById('caseTypeBadge').textContent = typeMap[caseDto.lifecycleType] || caseDto.lifecycleType;
        const s = statusMap[caseDto.status] || { label: caseDto.status, class: 'bg-secondary' };
        const statusBadge = document.getElementById('caseStatusBadge');
        statusBadge.textContent = s.label;
        statusBadge.className = `badge ${s.class}`;

        // アクションボタン制御
        const btnHold = document.getElementById('btnHoldCase');
        const btnResume = document.getElementById('btnResumeCase');
        const btnCancel = document.getElementById('btnCancelCase');
        const btnComplete = document.getElementById('btnCompleteCase');

        if (caseDto.status === 'ACTIVE') {
            btnHold.style.display = 'inline-block';
            btnResume.style.display = 'none';
            btnCancel.style.display = 'inline-block';
            btnComplete.style.display = 'inline-block';
        } else if (caseDto.status === 'ON_HOLD') {
            btnHold.style.display = 'none';
            btnResume.style.display = 'inline-block';
            btnCancel.style.display = 'inline-block';
            btnComplete.style.display = 'none';
        } else {
            btnHold.style.display = 'none';
            btnResume.style.display = 'none';
            btnCancel.style.display = 'none';
            btnComplete.style.display = 'none';
        }

        // 進捗バー
        const tasks = caseDto.tasks || [];
        const total = tasks.length;
        const done = tasks.filter(t => t.status === 'COMPLETED' || t.status === 'WAIVED').length;
        const pct = total > 0 ? Math.round((done / total) * 100) : 0;
        document.getElementById('progressSummary').textContent = `${done} / ${total} 完了`;
        document.getElementById('progressPercentage').textContent = `${pct}%`;
        document.getElementById('progressBar').style.width = `${pct}%`;

        // 退社ゲート
        if (caseDto.lifecycleType === 'RESIGNATION') {
            document.getElementById('resignationGateCard').style.display = 'block';
            this.checkGate();
        } else {
            document.getElementById('resignationGateCard').style.display = 'none';
        }

        // タスク一覧描画
        this.renderTasks(tasks, caseDto.status);

        // イベントログ描画
        this.renderEvents(caseDto.events || []);
    },

    renderTasks: function(tasks, caseStatus) {
        const tbody = document.getElementById('tasksTableBody');
        if (!tbody) return;
        if (tasks.length === 0) {
            tbody.innerHTML = '<tr><td colspan="8" class="text-center py-4 text-muted">タスクはありません</td></tr>';
            return;
        }

        let html = '';
        tasks.forEach(t => {
            const isBlocking = t.isBlocking === 1
                ? '<span class="badge bg-danger">阻害</span>'
                : '<span class="badge bg-secondary">任意</span>';

            const statusClassMap = {
                'PENDING': 'bg-secondary',
                'IN_PROGRESS': 'bg-primary',
                'COMPLETED': 'bg-success',
                'WAIVED': 'bg-warning text-dark'
            };
            const statusLabelMap = {
                'PENDING': '未着手',
                'IN_PROGRESS': '進行中',
                'COMPLETED': '完了',
                'WAIVED': '免除'
            };

            const sClass = statusClassMap[t.status] || 'bg-secondary';
            const sLabel = statusLabelMap[t.status] || t.status;

            let actionBtns = '';
            if (caseStatus === 'ACTIVE') {
                if (t.status === 'PENDING') {
                    actionBtns += `<button type="button" class="btn btn-sm btn-outline-primary" onclick="SES.lifecycle.startTask(${t.id})">着手</button>`;
                }
                if (t.status === 'IN_PROGRESS') {
                    actionBtns += `<button type="button" class="btn btn-sm btn-success" onclick="SES.lifecycle.openCompleteModal(this)" data-task-id="${t.id}" data-evidence-type="${SES.escapeHtml(t.evidenceType || 'NONE')}" title="タスクを完了" aria-label="タスクを完了"><i class="bi bi-check-lg me-1"></i>完了</button>`;
                }
                if (t.status === 'PENDING' || t.status === 'IN_PROGRESS') {
                    actionBtns += `<button type="button" class="btn btn-sm btn-outline-warning" onclick="SES.lifecycle.openWaiveModal(${t.id})">免除</button>`;
                    actionBtns += `<button type="button" class="btn btn-sm btn-outline-info" onclick="SES.lifecycle.openReassignModal(${t.id})">担当変更</button>`;
                }
            }

            const assignee = t.assigneeNameSnapshot
                ? `${SES.escapeHtml(t.assigneeNameSnapshot)} <span class="text-muted small">(${SES.escapeHtml(t.assigneeRole || '-')})</span>`
                : (t.assigneeRole ? `<span class="text-info">${SES.escapeHtml(t.assigneeRole)}担当</span>` : '-');

            const deps = t.predecessorTaskCodes && t.predecessorTaskCodes.length > 0
                ? t.predecessorTaskCodes.map(c => `<span class="badge bg-tertiary border border-theme text-theme">${SES.escapeHtml(c)}</span>`).join(' ')
                : '<span class="text-muted">-</span>';

            html += `
                <tr>
                    <td class="ps-4 text-muted">${t.stepOrder || '-'}</td>
                    <td>
                        <div class="fw-bold text-theme">${SES.escapeHtml(t.taskName)}</div>
                        <div class="font-monospace text-muted small">${SES.escapeHtml(t.taskCode)}</div>
                    </td>
                    <td>${assignee}</td>
                    <td class="text-muted small">${SES.escapeHtml(t.dueDate || '-')}</td>
                    <td>${deps}</td>
                    <td><span class="badge bg-tertiary border border-theme text-theme">${SES.escapeHtml(t.evidenceType || 'NONE')}</span></td>
                    <td>
                        <span class="badge ${sClass}">${sLabel}</span>
                        ${isBlocking}
                    </td>
                    <td class="text-end pe-4"><div class="d-flex flex-wrap justify-content-end align-items-center gap-1">${actionBtns}</div></td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
    },

    renderEvents: function(events) {
        const tbody = document.getElementById('eventsTableBody');
        if (!tbody) return;
        if (events.length === 0) {
            tbody.innerHTML = '<tr><td colspan="5" class="text-center py-3 text-muted">履歴はありません</td></tr>';
            return;
        }

        let html = '';
        events.forEach(e => {
            const time = e.occurredAt ? e.occurredAt.replace('T', ' ').substring(0, 19) : '-';
            const actorType = e.actorType || 'LEGACY_UNRESOLVED';
            const confirmationSource = e.confirmationSource || 'LEGACY_UNRESOLVED';
            const actor = `${SES.escapeHtml(actorType)} / ${SES.escapeHtml(confirmationSource)}${e.actorUserId ? ' (User#' + e.actorUserId + ')' : ''}`;
            const transition = (e.beforeState || e.afterState) ? `${e.beforeState || '-'} → <strong class="text-theme">${e.afterState || '-'}</strong>` : '-';

            html += `
                <tr>
                    <td class="ps-4 text-muted font-monospace small">${time}</td>
                    <td><span class="badge bg-tertiary border border-theme text-theme">${SES.escapeHtml(e.eventType)}</span></td>
                    <td class="text-theme small">${actor}</td>
                    <td class="small">${transition}</td>
                    <td class="pe-4 text-muted font-monospace small">${SES.escapeHtml(e.detailsJson || '-')}</td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
    },

    // ==========================================
    // 退社ゲート検証
    // ==========================================
    checkGate: async function() {
        if (!this.currentCaseId) return;
        try {
            const data = await SES.api.get(`/api/lifecycle/cases/${encodeURIComponent(this.currentCaseId)}/gate`);
            const badge = document.getElementById('gateStatusBadge');
            if (data.passed) {
                badge.innerHTML = '<span class="badge bg-success px-3 py-2 fs-6"><i class="bi bi-shield-check me-1"></i>ゲート通過 (PASS)</span>';
            } else {
                badge.innerHTML = '<span class="badge bg-danger px-3 py-2 fs-6"><i class="bi bi-shield-x me-1"></i>ゲート未通過 (BLOCKED)</span>';
            }
            document.getElementById('gateSummaryText').textContent = data.summary || '';

            const list = document.getElementById('gateCriteriaList');
            if (list && data.criteria) {
                let html = '';
                data.criteria.forEach(c => {
                    const icon = c.passed ? '<i class="bi bi-check-circle-fill text-success fs-5"></i>' : '<i class="bi bi-x-circle-fill text-danger fs-5"></i>';
                    const border = c.passed ? 'border-success' : 'border-danger';
                    html += `
                        <div class="col-md-6">
                            <div class="p-2 border ${border} rounded bg-card d-flex align-items-center gap-3">
                                <div>${icon}</div>
                                <div>
                                    <div class="text-theme small fw-bold">${SES.escapeHtml(c.criterionName)}</div>
                                    <div class="text-muted small">${SES.escapeHtml(c.detail || '')}</div>
                                </div>
                            </div>
                        </div>
                    `;
                });
                list.innerHTML = html;
            }
        } catch (e) {
            console.error(e);
            console.error(e.message || 'ゲート情報の取得に失敗しました');
        }
    },

    // ==========================================
    // タスク操作
    // ==========================================
    startTask: async function(taskId) {
        try {
            await SES.api.post(`/api/lifecycle/tasks/${encodeURIComponent(taskId)}/start`, {});
            SES.toast.success('タスクを開始しました');
            await this.loadDetail(this.currentCaseId);
        } catch (e) {
            console.error(e);
            console.error(e.message || 'タスク開始に失敗しました');
        }
    },

    openCompleteModal: function(buttonOrTaskId, evidenceType) {
        const taskId = buttonOrTaskId && buttonOrTaskId.dataset
            ? buttonOrTaskId.dataset.taskId
            : buttonOrTaskId;
        if (buttonOrTaskId && buttonOrTaskId.dataset) {
            evidenceType = buttonOrTaskId.dataset.evidenceType;
        }
        document.getElementById('completeTaskId').value = taskId;
        document.getElementById('completeComment').value = '';
        const evSection = document.getElementById('evidenceSection');
        if (evidenceType === 'DOCUMENT_LINK') {
            evSection.style.display = 'block';
        } else {
            evSection.style.display = 'none';
        }
        new bootstrap.Modal(document.getElementById('completeTaskModal')).show();
    },

    submitCompleteTask: async function() {
        const taskId = document.getElementById('completeTaskId').value;
        const comment = document.getElementById('completeComment').value;
        const docId = document.getElementById('completeDocumentId').value;

        const payload = {
            completionComment: comment || undefined,
            documentId: docId ? parseInt(docId, 10) : undefined
        };

        try {
            await SES.api.post(`/api/lifecycle/tasks/${encodeURIComponent(taskId)}/complete`, payload);
            SES.toast.success('タスクを完了しました');
            bootstrap.Modal.getInstance(document.getElementById('completeTaskModal')).hide();
            await this.loadDetail(this.currentCaseId);
        } catch (e) {
            console.error(e);
            console.error(e.message || 'タスク完了に失敗しました');
        }
    },

    openWaiveModal: function(taskId) {
        document.getElementById('waiveTaskId').value = taskId;
        document.getElementById('waiveReason').value = '';
        document.getElementById('waiveApprovalId').value = '';
        new bootstrap.Modal(document.getElementById('waiveTaskModal')).show();
    },

    submitWaiveTask: async function() {
        const taskId = document.getElementById('waiveTaskId').value;
        const reason = document.getElementById('waiveReason').value;
        const approvalId = document.getElementById('waiveApprovalId').value;

        if (!reason) {
            SES.toast.warning('免除理由を入力してください');
            return;
        }

        const payload = {
            reason: reason,
            approvalRequestId: approvalId ? parseInt(approvalId, 10) : undefined
        };

        try {
            await SES.api.post(`/api/lifecycle/tasks/${encodeURIComponent(taskId)}/waive`, payload);
            SES.toast.success('タスクを免除しました');
            bootstrap.Modal.getInstance(document.getElementById('waiveTaskModal')).hide();
            await this.loadDetail(this.currentCaseId);
        } catch (e) {
            console.error(e);
            console.error(e.message || 'タスク免除に失敗しました');
        }
    },

    openReassignModal: function(taskId) {
        document.getElementById('reassignTaskId').value = taskId;
        document.getElementById('reassignUserId').value = '';
        document.getElementById('reassignReason').value = '';
        new bootstrap.Modal(document.getElementById('reassignTaskModal')).show();
    },

    submitReassignTask: async function() {
        const taskId = document.getElementById('reassignTaskId').value;
        const userId = document.getElementById('reassignUserId').value;
        const reason = document.getElementById('reassignReason').value;

        if (!userId) {
            SES.toast.warning('担当ユーザーIDを入力してください');
            return;
        }

        const payload = {
            newAssigneeUserId: parseInt(userId, 10),
            reason: reason || undefined
        };

        try {
            await SES.api.post(`/api/lifecycle/tasks/${encodeURIComponent(taskId)}/reassign`, payload);
            SES.toast.success('タスク担当者を変更しました');
            bootstrap.Modal.getInstance(document.getElementById('reassignTaskModal')).hide();
            await this.loadDetail(this.currentCaseId);
        } catch (e) {
            console.error(e);
            console.error(e.message || '担当者変更に失敗しました');
        }
    },

    // ==========================================
    // 案件状態遷移 (保留 / 再開 / 完了 / 中止)
    // ==========================================
    promptHoldCase: function() {
        Swal.fire({
            title: '案件を保留にしますか？',
            input: 'text',
            inputPlaceholder: '保留理由を入力してください',
            showCancelButton: true,
            confirmButtonText: '保留にする',
            cancelButtonText: 'キャンセル',
            customClass: { popup: 'bg-card text-theme' }
        }).then(async (result) => {
            if (result.isConfirmed) {
                try {
                    await SES.api.post(`/api/lifecycle/cases/${encodeURIComponent(this.currentCaseId)}/hold`, { reason: result.value || '' });
                    SES.toast.success('案件を保留にしました');
                    await this.loadDetail(this.currentCaseId);
                } catch (e) {
                    console.error(e);
                    console.error(e.message || '案件の保留に失敗しました');
                }
            }
        });
    },

    resumeCase: async function() {
        try {
            await SES.api.post(`/api/lifecycle/cases/${encodeURIComponent(this.currentCaseId)}/resume`, {});
            SES.toast.success('案件を再開しました');
            await this.loadDetail(this.currentCaseId);
        } catch (e) {
            console.error(e);
            console.error(e.message || '案件の再開に失敗しました');
        }
    },

    completeCase: function() {
        Swal.fire({
            title: '案件を完了確定しますか？',
            text: 'すべての阻害タスクおよび退社ゲート条件が満たされている必要があります。',
            icon: 'question',
            showCancelButton: true,
            confirmButtonText: '完了確定',
            cancelButtonText: 'キャンセル',
            customClass: { popup: 'bg-card text-theme' }
        }).then(async (result) => {
            if (result.isConfirmed) {
                try {
                    await SES.api.post(`/api/lifecycle/cases/${encodeURIComponent(this.currentCaseId)}/complete`, {});
                    SES.toast.success('案件を正常に完了しました');
                    await this.loadDetail(this.currentCaseId);
                } catch (e) {
                    console.error(e);
                    console.error(e.message || '案件の完了に失敗しました');
                }
            }
        });
    },

    promptCancelCase: function() {
        Swal.fire({
            title: '案件を中止しますか？',
            text: '未完了のタスクはすべて自動免除されます。この操作は取り消せません。',
            input: 'text',
            inputPlaceholder: '中止理由を入力してください',
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: '中止する',
            cancelButtonText: 'キャンセル',
            confirmButtonColor: '#dc3545',
            customClass: { popup: 'bg-card text-theme' }
        }).then(async (result) => {
            if (result.isConfirmed) {
                try {
                    await SES.api.post(`/api/lifecycle/cases/${encodeURIComponent(this.currentCaseId)}/cancel`, { reason: result.value || '' });
                    SES.toast.success('案件を中止しました');
                    await this.loadDetail(this.currentCaseId);
                } catch (e) {
                    console.error(e);
                    console.error(e.message || '案件の中止に失敗しました');
                }
            }
        });
    },

    // ==========================================
    // テンプレート管理
    // ==========================================
    loadTemplates: async function() {
        try {
            const templates = (await SES.api.get('/api/lifecycle/templates')) || [];
            this.renderTemplates(templates);
        } catch (e) {
            console.error(e);
            console.error(e.message || 'テンプレート一覧の取得に失敗しました');
        }
    },

    renderTemplates: function(templates) {
        const tbody = document.getElementById('templatesTableBody');
        if (!tbody) return;
        if (templates.length === 0) {
            tbody.innerHTML = '<tr><td colspan="7" class="text-center py-4 text-muted">テンプレートはありません</td></tr>';
            return;
        }

        let html = '';
        templates.forEach(tpl => {
            const statusBadge = tpl.status === 'ACTIVE'
                ? '<span class="badge bg-success">有効</span>'
                : '<span class="badge bg-secondary">無効</span>';

            html += `
                <tr>
                    <td class="ps-4 fw-bold text-theme">${SES.escapeHtml(tpl.name || '')}</td>
                    <td><span class="badge bg-info text-dark">${SES.escapeHtml(tpl.templateType || '')}</span></td>
                    <td class="font-monospace text-theme">v${tpl.versionNo}</td>
                    <td class="text-muted small">${SES.escapeHtml(tpl.validFrom || '-')}</td>
                    <td class="text-theme">${tpl.taskCount !== undefined ? tpl.taskCount : (tpl.tasks ? tpl.tasks.length : 0)}件</td>
                    <td>${statusBadge}</td>
                    <td class="text-end pe-4">
                        <div class="d-flex flex-wrap justify-content-end align-items-center gap-1">
                            <button type="button" class="btn btn-sm btn-outline-primary" onclick="SES.lifecycle.openTemplateModal(${tpl.id})" title="テンプレートを改定" aria-label="テンプレートを改定">
                                <i class="bi bi-pencil me-1"></i>改定
                            </button>
                            <button type="button" class="btn btn-sm btn-outline-secondary" onclick="SES.lifecycle.toggleTemplateStatus(${tpl.id}, '${tpl.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'}')">
                                ${tpl.status === 'ACTIVE' ? '無効化' : '有効化'}
                            </button>
                        </div>
                    </td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
    },

    toggleTemplateStatus: async function(templateId, newStatus) {
        try {
            await SES.api.post(`/api/lifecycle/templates/${encodeURIComponent(templateId)}/toggle-status`, { status: newStatus });
            SES.toast.success('ステータスを更新しました');
            await this.loadTemplates();
        } catch (e) {
            console.error(e);
            console.error(e.message || 'ステータスの更新に失敗しました');
        }
    },

    openTemplateModal: async function(id) {
        const form = document.getElementById('templateForm');
        if (!form) return;

        form.reset();
        document.getElementById('templateId').value = id || '';
        document.getElementById('templateModalTitle').textContent = id ? 'テンプレート改定' : 'テンプレート新規作成';
        document.getElementById('templateSaveButton').textContent = id ? '改定して保存' : '作成';
        document.getElementById('templateType').disabled = Boolean(id);
        document.getElementById('templateTasks').replaceChildren();

        if (id) {
            try {
                const template = await SES.api.get(`/api/lifecycle/templates/${encodeURIComponent(id)}`);
                this.fillTemplateForm(template);
            } catch (e) {
                console.error(e);
                console.error(e.message || 'テンプレート詳細の取得に失敗しました');
                return;
            }
        } else {
            document.getElementById('templateValidFrom').value = SES.util.getLocalDateString();
            this.addTemplateTask();
        }

        bootstrap.Modal.getOrCreateInstance(document.getElementById('templateModal')).show();
    },

    fillTemplateForm: function(template) {
        document.getElementById('templateType').value = template.templateType || '';
        document.getElementById('templateName').value = template.name || '';
        document.getElementById('templateDescription').value = template.description || '';
        document.getElementById('templateValidFrom').value = template.validFrom || '';
        document.getElementById('templateValidTo').value = template.validTo || '';
        (template.tasks || []).forEach(task => this.addTemplateTask(task));
        if (!template.tasks || template.tasks.length === 0) this.addTemplateTask();
    },

    addTemplateTask: function(task = {}) {
        const row = document.createElement('div');
        row.className = 'template-task-row border-theme rounded p-3 mb-2';
        row.innerHTML = `
            <div class="d-flex justify-content-between align-items-center mb-2">
                <span class="fw-bold">タスク</span>
                <button type="button" class="btn btn-sm btn-outline-danger remove-template-task" title="タスクを削除" aria-label="タスクを削除"><i class="bi bi-trash"></i></button>
            </div>
            <div class="row g-2">
                <div class="col-md-3"><label class="form-label small">コード</label><input class="form-control form-control-sm border-theme task-code" required></div>
                <div class="col-md-5"><label class="form-label small">タスク名</label><input class="form-control form-control-sm border-theme task-name" required></div>
                <div class="col-md-2"><label class="form-label small">期限（日）</label><input type="number" class="form-control form-control-sm border-theme task-due-days" min="0"></div>
                <div class="col-md-2"><label class="form-label small">並び順</label><input type="number" class="form-control form-control-sm border-theme task-sort-order" min="0"></div>
                <div class="col-md-6"><label class="form-label small">説明</label><input class="form-control form-control-sm border-theme task-description"></div>
                <div class="col-md-3"><label class="form-label small">担当ルール</label><input class="form-control form-control-sm border-theme task-assignee-rule" value="APPLICANT"></div>
                <div class="col-md-3"><label class="form-label small">担当値</label><input class="form-control form-control-sm border-theme task-assignee-value"></div>
                <div class="col-md-4"><label class="form-label small">証跡種別</label><select class="form-select form-select-sm border-theme task-evidence-type"><option value="NONE">なし</option><option value="COMMENT">コメント</option><option value="DOCUMENT_LINK">書類リンク</option></select></div>
                <div class="col-md-8"><label class="form-label small">前提タスクコード（カンマ区切り）</label><input class="form-control form-control-sm border-theme task-predecessors"></div>
                <div class="col-12 d-flex flex-wrap gap-3">
                    <label class="form-check"><input type="checkbox" class="form-check-input task-mandatory" checked> 必須</label>
                    <label class="form-check"><input type="checkbox" class="form-check-input task-blocking" checked> 阻害</label>
                    <label class="form-check"><input type="checkbox" class="form-check-input task-engineer-visible" checked> 要員に公開</label>
                </div>
            </div>`;
        const set = (selector, value) => { const element = row.querySelector(selector); if (element && value != null) element.value = value; };
        set('.task-code', task.taskCode || '');
        set('.task-name', task.taskName || '');
        set('.task-due-days', task.relativeDueDays == null ? 0 : task.relativeDueDays);
        set('.task-sort-order', task.sortOrder == null ? '' : task.sortOrder);
        set('.task-description', task.description || '');
        set('.task-assignee-rule', task.assigneeRule || 'APPLICANT');
        set('.task-assignee-value', task.assigneeRuleValue || '');
        set('.task-evidence-type', task.evidenceType || 'NONE');
        set('.task-predecessors', (task.predecessorTaskCodes || []).join(', '));
        row.querySelector('.task-mandatory').checked = task.isMandatory == null || task.isMandatory === 1;
        row.querySelector('.task-blocking').checked = task.isBlocking == null || task.isBlocking === 1;
        row.querySelector('.task-engineer-visible').checked = task.isEngineerVisible == null || task.isEngineerVisible === 1;
        row.querySelector('.remove-template-task').addEventListener('click', () => row.remove());
        document.getElementById('templateTasks').appendChild(row);
    },

    saveTemplate: async function() {
        const form = document.getElementById('templateForm');
        if (!form.checkValidity()) {
            form.reportValidity();
            return;
        }
        const id = document.getElementById('templateId').value;
        const tasks = Array.from(document.querySelectorAll('.template-task-row')).map((row, index) => ({
            taskCode: row.querySelector('.task-code').value.trim(),
            taskName: row.querySelector('.task-name').value.trim(),
            description: row.querySelector('.task-description').value.trim() || null,
            relativeDueDays: Number(row.querySelector('.task-due-days').value || 0),
            assigneeRule: row.querySelector('.task-assignee-rule').value.trim() || 'APPLICANT',
            assigneeRuleValue: row.querySelector('.task-assignee-value').value.trim() || null,
            isMandatory: row.querySelector('.task-mandatory').checked ? 1 : 0,
            isBlocking: row.querySelector('.task-blocking').checked ? 1 : 0,
            evidenceType: row.querySelector('.task-evidence-type').value,
            isEngineerVisible: row.querySelector('.task-engineer-visible').checked ? 1 : 0,
            sortOrder: Number(row.querySelector('.task-sort-order').value || ((index + 1) * 10)),
            predecessorTaskCodes: row.querySelector('.task-predecessors').value.split(',').map(value => value.trim()).filter(Boolean)
        }));
        const payload = {
            templateType: document.getElementById('templateType').value,
            name: document.getElementById('templateName').value.trim(),
            description: document.getElementById('templateDescription').value.trim() || null,
            validFrom: document.getElementById('templateValidFrom').value,
            validTo: document.getElementById('templateValidTo').value || null,
            tasks: tasks
        };
        try {
            if (id) {
                await SES.api.put(`/api/lifecycle/templates/${encodeURIComponent(id)}`, payload);
            } else {
                await SES.api.post('/api/lifecycle/templates', payload);
            }
            SES.toast.success(id ? 'テンプレートを改定しました' : 'テンプレートを作成しました');
            bootstrap.Modal.getInstance(document.getElementById('templateModal')).hide();
            await this.loadTemplates();
        } catch (e) {
            console.error(e);
            console.error(e.message || 'テンプレートの保存に失敗しました');
        }
    }
};

$(document).ready(function() {
    SES.lifecycle.init();
});
