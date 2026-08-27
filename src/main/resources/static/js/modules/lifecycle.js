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
            const res = await SES.api.get('/api/lifecycle/cases', params);
            if (res.code === 200) {
                this.renderCases(res.data || []);
            } else {
                SES.toast.error(res.message || '案件一覧の取得に失敗しました');
            }
        } catch (e) {
            console.error(e);
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
                    <td class="ps-4 fw-bold font-monospace text-light">${SES.escape(c.caseNo)}</td>
                    <td><span class="badge ${t.class}">${t.label}</span></td>
                    <td class="text-light">${SES.escape(c.engineerName || '-')}</td>
                    <td class="text-muted small">${SES.escape(c.anchorDate || '-')}</td>
                    <td><span class="badge ${s.class}">${s.label}</span></td>
                    <td style="min-width: 160px;">
                        <div class="d-flex align-items-center gap-2">
                            <div class="progress flex-grow-1 bg-dark" style="height: 6px;">
                                <div class="progress-bar bg-success" style="width: ${pct}%"></div>
                            </div>
                            <span class="small text-muted">${completed}/${total}</span>
                        </div>
                    </td>
                    <td class="text-end pe-4">
                        <a href="/lifecycle/${c.id}" class="btn btn-sm btn-outline-light">
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
            const res = await SES.api.get('/api/lifecycle/tasks/my-pending');
            if (res.code === 200) {
                const tasks = res.data || [];
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
            }
        } catch (e) {
            console.error(e);
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
                    <td class="ps-4 font-monospace text-light">${SES.escape(tk.taskCode)}</td>
                    <td class="text-light fw-bold">${SES.escape(tk.taskName)}</td>
                    <td class="text-muted small">${SES.escape(tk.dueDate || '-')}</td>
                    <td>${isBlocking}</td>
                    <td>${statusBadge}</td>
                    <td class="text-end pe-4">
                        <a href="/lifecycle/${tk.caseId}" class="btn btn-sm btn-primary">
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
                const res = await SES.api.get('/api/engineers', { size: 500 });
                if (res.code === 200 && res.data && res.data.records) {
                    let opts = '<option value="">要員を選択してください</option>';
                    res.data.records.forEach(e => {
                        opts += `<option value="${e.id}">${SES.escape(e.fullName)} (${SES.escape(e.employmentType || '-')})</option>`;
                    });
                    engSelect.innerHTML = opts;
                }
            } catch (e) {
                console.error(e);
            }
        }
        const today = new Date().toISOString().split('T')[0];
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
            const res = await SES.api.post('/api/lifecycle/cases', payload);
            if (res.code === 200) {
                SES.toast.success(res.message || '案件を起票しました');
                bootstrap.Modal.getInstance(document.getElementById('createCaseModal')).hide();
                window.location.href = '/lifecycle/' + res.data.id;
            } else {
                SES.toast.error(res.message || '案件起票に失敗しました');
            }
        } catch (e) {
            console.error(e);
        }
    },

    // ==========================================
    // 案件詳細
    // ==========================================
    loadDetail: async function(caseId) {
        try {
            const res = await SES.api.get(`/api/lifecycle/cases/${caseId}`);
            if (res.code === 200) {
                this.renderDetail(res.data);
            } else {
                SES.toast.error(res.message || '詳細の読み込みに失敗しました');
            }
        } catch (e) {
            console.error(e);
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
                    actionBtns += `<button class="btn btn-sm btn-outline-primary me-1" onclick="SES.lifecycle.startTask(${t.id})">着手</button>`;
                }
                if (t.status === 'IN_PROGRESS') {
                    actionBtns += `<button class="btn btn-sm btn-success me-1" onclick="SES.lifecycle.openCompleteModal(${t.id}, '${t.evidenceType || 'NONE'}')"><i class="bi bi-check-lg me-1"></i>完了</button>`;
                }
                if (t.status === 'PENDING' || t.status === 'IN_PROGRESS') {
                    actionBtns += `<button class="btn btn-sm btn-outline-warning me-1" onclick="SES.lifecycle.openWaiveModal(${t.id})">免除</button>`;
                    actionBtns += `<button class="btn btn-sm btn-outline-info" onclick="SES.lifecycle.openReassignModal(${t.id})">担当変更</button>`;
                }
            }

            const assignee = t.assigneeNameSnapshot
                ? `${SES.escape(t.assigneeNameSnapshot)} <span class="text-muted small">(${SES.escape(t.assigneeRole || '-')})</span>`
                : (t.assigneeRole ? `<span class="text-info">${SES.escape(t.assigneeRole)}担当</span>` : '-');

            const deps = t.predecessorTaskCodes && t.predecessorTaskCodes.length > 0
                ? t.predecessorTaskCodes.map(c => `<span class="badge bg-dark border border-secondary">${SES.escape(c)}</span>`).join(' ')
                : '<span class="text-muted">-</span>';

            html += `
                <tr>
                    <td class="ps-4 text-muted">${t.stepOrder || '-'}</td>
                    <td>
                        <div class="fw-bold text-light">${SES.escape(t.taskName)}</div>
                        <div class="font-monospace text-muted small">${SES.escape(t.taskCode)}</div>
                    </td>
                    <td>${assignee}</td>
                    <td class="text-muted small">${SES.escape(t.dueDate || '-')}</td>
                    <td>${deps}</td>
                    <td><span class="badge bg-dark border border-secondary text-light">${SES.escape(t.evidenceType || 'NONE')}</span></td>
                    <td>
                        <span class="badge ${sClass}">${sLabel}</span>
                        ${isBlocking}
                    </td>
                    <td class="text-end pe-4">${actionBtns}</td>
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
            const actor = `${e.actorUserId ? 'User#' + e.actorUserId : 'SYSTEM'} (${SES.escape(e.actorRoleSnapshot || '-')})`;
            const transition = (e.beforeState || e.afterState) ? `${e.beforeState || '-'} → <strong class="text-light">${e.afterState || '-'}</strong>` : '-';

            html += `
                <tr>
                    <td class="ps-4 text-muted font-monospace small">${time}</td>
                    <td><span class="badge bg-dark border border-secondary">${SES.escape(e.eventType)}</span></td>
                    <td class="text-light small">${actor}</td>
                    <td class="small">${transition}</td>
                    <td class="pe-4 text-muted font-monospace small">${SES.escape(e.detailsJson || '-')}</td>
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
            const res = await SES.api.get(`/api/lifecycle/cases/${this.currentCaseId}/gate`);
            if (res.code === 200) {
                const data = res.data;
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
                                <div class="p-2 border ${border} rounded bg-dark d-flex align-items-center gap-3">
                                    <div>${icon}</div>
                                    <div>
                                        <div class="text-light small fw-bold">${SES.escape(c.criterionName)}</div>
                                        <div class="text-muted small">${SES.escape(c.detail || '')}</div>
                                    </div>
                                </div>
                            </div>
                        `;
                    });
                    list.innerHTML = html;
                }
            }
        } catch (e) {
            console.error(e);
        }
    },

    // ==========================================
    // タスク操作
    // ==========================================
    startTask: async function(taskId) {
        try {
            const res = await SES.api.post(`/api/lifecycle/tasks/${taskId}/start`, {});
            if (res.code === 200) {
                SES.toast.success('タスクを開始しました');
                this.loadDetail(this.currentCaseId);
            } else {
                SES.toast.error(res.message || 'タスク開始に失敗しました');
            }
        } catch (e) {
            console.error(e);
        }
    },

    openCompleteModal: function(taskId, evidenceType) {
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
            const res = await SES.api.post(`/api/lifecycle/tasks/${taskId}/complete`, payload);
            if (res.code === 200) {
                SES.toast.success('タスクを完了しました');
                bootstrap.Modal.getInstance(document.getElementById('completeTaskModal')).hide();
                this.loadDetail(this.currentCaseId);
            } else {
                SES.toast.error(res.message || 'タスク完了に失敗しました');
            }
        } catch (e) {
            console.error(e);
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
            const res = await SES.api.post(`/api/lifecycle/tasks/${taskId}/waive`, payload);
            if (res.code === 200) {
                SES.toast.success('タスクを免除しました');
                bootstrap.Modal.getInstance(document.getElementById('waiveTaskModal')).hide();
                this.loadDetail(this.currentCaseId);
            } else {
                SES.toast.error(res.message || 'タスク免除に失敗しました');
            }
        } catch (e) {
            console.error(e);
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
            const res = await SES.api.post(`/api/lifecycle/tasks/${taskId}/reassign`, payload);
            if (res.code === 200) {
                SES.toast.success('タスク担当者を変更しました');
                bootstrap.Modal.getInstance(document.getElementById('reassignTaskModal')).hide();
                this.loadDetail(this.currentCaseId);
            } else {
                SES.toast.error(res.message || '担当者変更に失敗しました');
            }
        } catch (e) {
            console.error(e);
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
            customClass: { popup: 'bg-dark text-light' }
        }).then(async (result) => {
            if (result.isConfirmed) {
                try {
                    const res = await SES.api.post(`/api/lifecycle/cases/${this.currentCaseId}/hold`, { reason: result.value || '' });
                    if (res.code === 200) {
                        SES.toast.success('案件を保留にしました');
                        this.loadDetail(this.currentCaseId);
                    } else {
                        SES.toast.error(res.message);
                    }
                } catch (e) {
                    console.error(e);
                }
            }
        });
    },

    resumeCase: async function() {
        try {
            const res = await SES.api.post(`/api/lifecycle/cases/${this.currentCaseId}/resume`, {});
            if (res.code === 200) {
                SES.toast.success('案件を再開しました');
                this.loadDetail(this.currentCaseId);
            } else {
                SES.toast.error(res.message);
            }
        } catch (e) {
            console.error(e);
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
            customClass: { popup: 'bg-dark text-light' }
        }).then(async (result) => {
            if (result.isConfirmed) {
                try {
                    const res = await SES.api.post(`/api/lifecycle/cases/${this.currentCaseId}/complete`, {});
                    if (res.code === 200) {
                        SES.toast.success('案件を正常に完了しました');
                        this.loadDetail(this.currentCaseId);
                    } else {
                        SES.toast.error(res.message);
                    }
                } catch (e) {
                    console.error(e);
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
            customClass: { popup: 'bg-dark text-light' }
        }).then(async (result) => {
            if (result.isConfirmed) {
                try {
                    const res = await SES.api.post(`/api/lifecycle/cases/${this.currentCaseId}/cancel`, { reason: result.value || '' });
                    if (res.code === 200) {
                        SES.toast.success('案件を中止しました');
                        this.loadDetail(this.currentCaseId);
                    } else {
                        SES.toast.error(res.message);
                    }
                } catch (e) {
                    console.error(e);
                }
            }
        });
    },

    // ==========================================
    // テンプレート管理
    // ==========================================
    loadTemplates: async function() {
        try {
            const res = await SES.api.get('/api/lifecycle/templates');
            if (res.code === 200) {
                this.renderTemplates(res.data || []);
            }
        } catch (e) {
            console.error(e);
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
                    <td class="ps-4 fw-bold text-light">${SES.escape(tpl.name)}</td>
                    <td><span class="badge bg-info text-dark">${SES.escape(tpl.templateType)}</span></td>
                    <td class="font-monospace text-light">v${tpl.versionNo}</td>
                    <td class="text-muted small">${SES.escape(tpl.validFrom || '-')}</td>
                    <td class="text-light">${tpl.tasks ? tpl.tasks.length : 0}件</td>
                    <td>${statusBadge}</td>
                    <td class="text-end pe-4">
                        <button class="btn btn-sm btn-outline-light" onclick="SES.lifecycle.toggleTemplateStatus(${tpl.id}, '${tpl.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE'}')">
                            ${tpl.status === 'ACTIVE' ? '無効化' : '有効化'}
                        </button>
                    </td>
                </tr>
            `;
        });
        tbody.innerHTML = html;
    },

    toggleTemplateStatus: async function(templateId, newStatus) {
        try {
            const res = await SES.api.post(`/api/lifecycle/templates/${templateId}/toggle-status`, { status: newStatus });
            if (res.code === 200) {
                SES.toast.success('ステータスを更新しました');
                this.loadTemplates();
            } else {
                SES.toast.error(res.message);
            }
        } catch (e) {
            console.error(e);
        }
    }
};

$(document).ready(function() {
    SES.lifecycle.init();
});
