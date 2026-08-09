// 休暇管理画面（attendance-leave-overtime-compliance / T071）
(() => {
    const month = document.getElementById('leaveMonth');
    if (!month) return;
    const now = new Date();
    month.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    month.addEventListener('change', load);
    document.getElementById('leaveBalanceLoad').addEventListener('click', loadBalance);
    load();

    function load() {
        fetch(`/api/leave?month=${encodeURIComponent(month.value)}`).then(read).then(data => {
            if (data.code !== 200) return showError(data.message);
            render(data.data || []);
        }).catch(showError);
    }

    function loadBalance() {
        const engineerId = document.getElementById('leaveBalanceEngineer').value;
        if (!engineerId) return;
        fetch(`/api/leave/ledger/balance?engineerId=${encodeURIComponent(engineerId)}`).then(read).then(data => {
            if (data.code !== 200) return showError(data.message);
            const rows = (data.data || []).map(b => `<span class="badge bg-secondary me-2">${esc(b.leaveType)}: ${b.mode === 'internal' ? (b.balanceMinutes ?? 0) : t('leave.balanceExternal','外部参照')}</span>`).join('');
            document.getElementById('leaveBalanceResult').innerHTML = rows || '';
            clearError();
        }).catch(showError);
    }

    function render(rows) {
        document.getElementById('leaveManagementBody').innerHTML = rows.map(row =>
            `<tr><td>${esc(row.engineerName || row.engineerId)}</td><td>${esc(row.leaveType)}</td><td>${esc(row.startDate)}</td><td>${esc(row.endDate || '')}</td><td>${esc(row.requestedMinutes)}</td><td>${esc(row.status)}</td><td>${esc(row.approvalStatus || '')}</td></tr>`
        ).join('');
    }

    function read(response) { return response.json(); }
    function t(key, fallback) { return SES.i18n.t(key, fallback); }
    function esc(value) { return SES.escapeHtml(String(value ?? '')); }
    function clearError() { document.getElementById('leaveError').classList.add('d-none'); }
    function showError(error) { const el = document.getElementById('leaveError'); el.textContent = error || t('leave.error', '休暇の処理に失敗しました'); el.classList.remove('d-none'); }
})();
