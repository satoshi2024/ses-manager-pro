// 雇用勤怠管理画面（attendance-leave-overtime-compliance / T070 / T072同期カード）
(() => {
    const month = document.getElementById('managementMonth');
    if (!month) return;
    const now = new Date();
    month.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    document.getElementById('managementReload').addEventListener('click', load);
    load();
    initSyncCard();
    initDiscrepancyCard();

    function load() {
        fetch(`/api/work-records/attendance?month=${encodeURIComponent(month.value)}`).then(r => r.json()).then(data => {
            if (data.code !== 200) return showError(data.message);
            clearError();
            const flags = document.getElementById('attendanceRoleFlags').dataset;
            const canApprove = flags.canApprove === 'true';
            const canClose = flags.canClose === 'true';
            const canReopen = flags.canReopen === 'true';
            document.getElementById('attendanceManagementBody').innerHTML = (data.data.months || []).map(row => {
                const id = encodeURIComponent(row.engineerId);
                const m = encodeURIComponent(month.value);
                let actions = '';
                if (canApprove && row.status === '提出済') actions += action(`/api/work-records/attendance/${id}/reject?month=${m}`, 'attendance.reject', '差戻し') + action(`/api/work-records/attendance/${id}/approve?month=${m}`, 'attendance.approve', '承認');
                if (canClose && row.status === '承認済') actions += action(`/api/work-records/attendance/${id}/close?month=${m}`, 'attendance.close', '締め');
                if (canReopen && row.status === '締め済') actions += action(`/api/work-records/attendance/${id}/reopen?month=${m}`, 'attendance.reopen', '再open', true);
                return `<tr><td>${esc(row.engineerName || row.engineerId)}</td><td>${esc(row.status || '')}</td><td>${esc(row.workedMinutes || 0)}</td><td>${actions}</td></tr>`;
            }).join('');
            document.querySelectorAll('[data-attendance-action]').forEach(button => button.addEventListener('click', () => run(button)));
        }).catch(showError);
    }
    function action(url, key, fallback, reopen) {
        const marker = reopen ? ' data-attendance-reopen="true"' : '';
        return `<button type="button" class="btn btn-sm btn-outline-primary me-1" data-attendance-action="${esc(url)}"${marker}>${esc(SES.i18n.t(key, fallback))}</button>`;
    }
    function run(button) {
        const url = button.dataset.attendanceAction;
        const headers = Object.assign({'Content-Type': 'application/json'}, SES.csrf.header());
        const options = { method: 'POST', headers };
        if (button.dataset.attendanceReopen === 'true') {
            const reason = window.prompt(SES.i18n.t('attendance.reopenReasonPrompt', '再open理由を入力してください'));
            if (!reason || !reason.trim()) return;
            options.body = JSON.stringify({ month: new URL(url, window.location.origin).searchParams.get('month'), reason: reason.trim() });
        }
        fetch(url, options).then(r => r.json()).then(data => { if (data.code !== 200) return showError(data.message); load(); }).catch(showError);
    }
    function esc(value) { return SES.escapeHtml(String(value ?? '')); }
    function showError(error) { const el = document.getElementById('managementError'); el.textContent = error || SES.i18n.t('attendance.error', '勤怠の処理に失敗しました'); el.classList.remove('d-none'); }
    function clearError() { document.getElementById('managementError').classList.add('d-none'); }

    // ===== T072: 外部勤怠同期カード =====
    function initSyncCard() {
        const syncMonth = document.getElementById('syncMonth');
        const syncRunPush = document.getElementById('syncRunPush');
        const syncRunPull = document.getElementById('syncRunPull');
        const syncExportCsv = document.getElementById('syncExportCsv');
        if (!syncMonth) return;
        syncMonth.value = month.value;
        month.addEventListener('change', () => { syncMonth.value = month.value; });
        loadSyncStatus();

        if (syncRunPush) syncRunPush.addEventListener('click', () => runSync('push'));
        if (syncRunPull) syncRunPull.addEventListener('click', () => runSync('pull'));
        syncExportCsv.addEventListener('click', (e) => {
            e.preventDefault();
            const target = document.getElementById('attendanceSyncError');
            const monthValue = encodeURIComponent(syncMonth.value);
            if (!monthValue) { target.textContent = SES.i18n.t('attendance.sync.monthRequired', '対象月を指定してください'); target.classList.remove('d-none'); return; }
            window.location.href = `/api/work-records/attendance/sync/export-csv?month=${monthValue}`;
        });

        function runSync(direction) {
            const target = document.getElementById('attendanceSyncError');
            target.classList.add('d-none');
            const monthValue = encodeURIComponent(syncMonth.value);
            if (!monthValue) { target.textContent = SES.i18n.t('attendance.sync.monthRequired', '対象月を指定してください'); target.classList.remove('d-none'); return; }
            const headers = Object.assign({'Content-Type': 'application/json'}, SES.csrf.header());
            fetch(`/api/work-records/attendance/sync/run?month=${monthValue}&direction=${direction}`, { method: 'POST', headers })
                .then(r => r.json())
                .then(data => {
                    if (data.code !== 200) { target.textContent = data.message || SES.i18n.t('attendance.sync.failed', '同期に失敗しました'); target.classList.remove('d-none'); return; }
                    renderResult(data.data);
                    loadSyncStatus();
                })
                .catch(() => { target.textContent = SES.i18n.t('attendance.sync.failed', '同期に失敗しました'); target.classList.remove('d-none'); });
        }

        function loadSyncStatus() {
            const provider = document.getElementById('attendanceSyncProvider');
            fetch('/api/work-records/attendance/sync/status').then(r => r.json()).then(data => {
                if (data.code !== 200 || !data.data) return;
                const status = data.data;
                provider.textContent = SES.i18n.t('attendance.sync.provider', 'provider') + ': ' + esc(status.provider)
                    + (status.providerAvailable ? '' : ' (' + SES.i18n.t('attendance.sync.unavailable', '未接続・CSV出力をご利用ください') + ')');
                renderResult(status.lastResult);
            }).catch(() => {});
        }

        function renderResult(result) {
            const el = document.getElementById('attendanceSyncResult');
            if (!result) { el.innerHTML = ''; return; }
            const t = SES.i18n.t;
            let html = `<span class="badge ${result.success ? 'text-bg-success' : 'text-bg-danger'}">${result.success ? t('attendance.sync.success', '成功') : t('attendance.sync.partial', '部分失敗')}</span> `
                + `${t('attendance.sync.pushed', '送信')}: ${esc(result.pushedCount || 0)} / ${t('attendance.sync.duplicate', '重複skip')}: ${esc(result.duplicateSkippedCount || 0)}`
                + ` / ${t('attendance.sync.pulled', '取得')}: ${esc(result.pulledCount || 0)} / ${t('attendance.sync.rejected', '拒否')}: ${esc(result.rejectedCount || 0)}`;
            if (result.direction !== 'push') {
                html += `<div class="mt-1">${t('attendance.sync.matched', '一致')}: ${esc(result.matchedCount || 0)}`
                    + ` / ${t('attendance.sync.diff', '差異')}: ${esc(result.diffCount || 0)}`
                    + ` / ${t('attendance.sync.unmatched', '該当なし')}: ${esc(result.unmatchedCount || 0)}</div>`;
                if (result.differences && result.differences.length) {
                    html += `<ul class="mt-1 mb-0 ps-3 text-danger">${result.differences.map(d =>
                        `<li>${esc(d.workDate || '')} ${esc(d.externalValue || '')} ≠ ${esc(d.internalValue || '')}</li>`).join('')}</ul>`;
                }
            }
            if (result.errors && result.errors.length) {
                html += `<div class="mt-1 text-danger">${result.errors.map(esc).join('<br>')}</div>`;
            }
            el.innerHTML = html;
        }
    }

    // ===== T073: 客先工数差異（read-only比較・理由確認） =====
    function initDiscrepancyCard() {
        const body = document.getElementById('attendanceDiscrepancyBody');
        if (!body) return;
        loadDiscrepancy();
        month.addEventListener('change', loadDiscrepancy);

        function loadDiscrepancy() {
            const monthValue = encodeURIComponent(month.value);
            fetch(`/api/work-records/attendance/discrepancy?month=${monthValue}`).then(r => r.json()).then(data => {
                if (data.code !== 200) return showDiscrepancyError(data.message);
                const target = document.getElementById('attendanceDiscrepancyError');
                target.classList.add('d-none');
                const items = (data.data && data.data.items) || [];
                body.innerHTML = items.length === 0
                    ? `<tr><td colspan="7" class="text-muted">${esc(SES.i18n.t('attendance.discrepancy.empty', '差異データがありません'))}</td></tr>`
                    : items.map(row => {
                        const confirmed = row.confirmed;
                        const over = row.overThreshold;
                        const statusBadge = confirmed
                            ? `<span class="badge text-bg-success">${esc(SES.i18n.t('attendance.discrepancy.confirmed', '確認済み'))}</span>`
                            : (over
                                ? `<span class="badge text-bg-danger">${esc(SES.i18n.t('attendance.discrepancy.overThreshold', '要確認'))}</span>`
                                : `<span class="badge text-bg-secondary">${esc(SES.i18n.t('attendance.discrepancy.within', '範囲内'))}</span>`);
                        const confirmBtn = (!confirmed && over)
                            ? `<button type="button" class="btn btn-sm btn-outline-primary" data-discrepancy-confirm="${esc(row.engineerId)}">${esc(SES.i18n.t('attendance.discrepancy.confirm', '確認'))}</button>`
                            : '';
                        return `<tr>
                            <td>${esc(row.engineerName || row.engineerId)}</td>
                            <td class="text-end">${esc(row.attendanceMinutes || 0)}</td>
                            <td class="text-end">${esc(row.contractMinutes || 0)}</td>
                            <td class="text-end">${esc(row.diffMinutes || 0)}</td>
                            <td>${statusBadge}</td>
                            <td>${esc(row.reason || '')}${row.confirmedAt ? `<div class="small text-muted">${esc(row.confirmedBy || '')} ${esc(row.confirmedAt || '')}</div>` : ''}</td>
                            <td>${confirmBtn}</td>
                        </tr>`;
                    }).join('');
                document.querySelectorAll('[data-discrepancy-confirm]').forEach(button =>
                    button.addEventListener('click', () => confirmDiscrepancy(button.dataset.discrepancyConfirm)));
            }).catch(showDiscrepancyError);
        }

        function confirmDiscrepancy(engineerId) {
            const reason = window.prompt(SES.i18n.t('attendance.discrepancy.reasonPrompt', '確認理由を入力してください'));
            if (!reason || !reason.trim()) return;
            const headers = Object.assign({'Content-Type': 'application/json'}, SES.csrf.header());
            fetch('/api/work-records/attendance/discrepancy/confirm', {
                method: 'POST',
                headers,
                body: JSON.stringify({ engineerId: String(engineerId), month: month.value, reason: reason.trim() })
            }).then(r => r.json()).then(data => {
                if (data.code !== 200) return showDiscrepancyError(data.message);
                loadDiscrepancy();
            }).catch(showDiscrepancyError);
        }

        function showDiscrepancyError(error) {
            const el = document.getElementById('attendanceDiscrepancyError');
            el.textContent = error || SES.i18n.t('attendance.discrepancy.error', '差異の取得に失敗しました');
            el.classList.remove('d-none');
        }
    }
})();
