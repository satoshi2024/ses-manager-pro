// 雇用勤怠管理画面（attendance-leave-overtime-compliance / T070）
(() => {
    const month = document.getElementById('managementMonth');
    if (!month) return;
    const now = new Date();
    month.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    document.getElementById('managementReload').addEventListener('click', load);
    load();

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
})();
