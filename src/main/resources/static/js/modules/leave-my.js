// 休暇申請本人画面（attendance-leave-overtime-compliance / T071）
(() => {
    const form = document.getElementById('leaveApplyForm');
    if (!form) return;
    form.addEventListener('submit', applyLeave);
    document.getElementById('leaveBody').addEventListener('click', event => {
        const button = event.target.closest('[data-action="cancel"]');
        if (!button) return;
        const reason = window.prompt(t('leave.cancelPrompt', '取消理由を入力してください'));
        if (reason === null) return;
        fetch(`/api/my/leave/${encodeURIComponent(button.dataset.id)}/cancel`, {
            method: 'POST', headers: Object.assign({'Content-Type': 'application/json'}, SES.csrf.header()),
            body: JSON.stringify({ reason })
        }).then(read).then(handle).catch(showError);
    });
    load();

    function load() {
        fetch('/api/my/leave').then(read).then(data => {
            if (data.code !== 200) return showError(data.message);
            render(data.data || []);
        }).catch(showError);
    }

    function render(rows) {
        const state = document.getElementById('leaveState');
        state.textContent = `${t('leave.my.title', '休暇申請')}: ${rows.length} ${t('leave.countSuffix', '件')}`;
        document.getElementById('leaveBody').innerHTML = rows.map(row => {
            const status = approvalLabel(row);
            const cancelable = row.status === '承認済' && row.approvalStatus !== 'rejected' && row.approvalStatus !== 'withdrawn';
            const actions = cancelable ? `<button type="button" class="btn btn-sm btn-outline-danger" data-action="cancel" data-id="${esc(row.id)}">${esc(t('leave.cancel','取消申請'))}</button>` : '';
            return `<tr><td>${esc(row.leaveType)}</td><td>${esc(row.startDate)}</td><td>${esc(row.endDate || '')}</td><td>${esc(row.startTime || '')}${row.startTime ? '〜' + esc(row.endTime || '') : ''}</td><td>${esc(row.requestedMinutes)}</td><td>${esc(status)}</td><td>${actions}</td></tr>`;
        }).join('');
    }

    function approvalLabel(row) {
        if (row.status === '承認済') return t('leave.status.approved', '承認済');
        if (row.status === '取消済') return t('leave.status.cancelled', '取消済');
        if (row.approvalStatus === 'rejected') return t('leave.status.rejected', '却下');
        if (row.approvalStatus === 'withdrawn') return t('leave.status.withdrawn', '取下げ');
        if (row.status === '差戻し') return t('leave.status.returned', '差戻し');
        return t('leave.status.applied', '申請中');
    }

    function applyLeave(event) {
        event.preventDefault();
        const body = {
            leaveType: value('leaveType'),
            startDate: value('leaveStartDate'),
            endDate: value('leaveEndDate') || null,
            startTime: value('leaveStartTime') || null,
            endTime: value('leaveEndTime') || null,
            reason: value('leaveReason') || null
        };
        fetch('/api/my/leave', { method: 'POST', headers: Object.assign({'Content-Type': 'application/json'}, SES.csrf.header()), body: JSON.stringify(body) })
            .then(read).then(handle).catch(showError);
    }

    function handle(data) { if (data.code !== 200) return showError(data.message); clearError(); form.reset(); load(); }
    function read(response) { return response.json(); }
    function value(id) { return document.getElementById(id).value; }
    function t(key, fallback) { return SES.i18n.t(key, fallback); }
    function esc(value) { return SES.escapeHtml(String(value ?? '')); }
    function clearError() { document.getElementById('leaveError').classList.add('d-none'); }
    function showError(error) { const el = document.getElementById('leaveError'); el.textContent = error || t('leave.error', '休暇の処理に失敗しました'); el.classList.remove('d-none'); }
})();
