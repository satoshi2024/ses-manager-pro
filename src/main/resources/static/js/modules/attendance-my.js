// 雇用勤怠本人画面（attendance-leave-overtime-compliance / T070）
(() => {
    const month = document.getElementById('attendanceMonth');
    const form = document.getElementById('attendanceDayForm');
    if (!month || !form) return;
    const now = new Date();
    month.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    month.addEventListener('change', load);
    document.getElementById('attendanceSubmit').addEventListener('click', submit);
    form.addEventListener('submit', save);
    document.getElementById('attendanceDayBody').addEventListener('click', event => {
        const button = event.target.closest('[data-action="delete"]');
        if (!button) return;
        fetch(`/api/my/attendance/daily?month=${encodeURIComponent(month.value)}&workDate=${encodeURIComponent(button.dataset.date)}`, {
            method: 'DELETE', headers: SES.csrf.header()
        }).then(read).then(handle).catch(showError);
    });
    load();

    function load() {
        fetch(`/api/my/attendance?month=${encodeURIComponent(month.value)}`).then(read).then(data => {
            if (data.code !== 200) return showError(data.message);
            const row = (data.data.months || [])[0];
            render(row);
        }).catch(showError);
    }

    function render(row) {
        const state = document.getElementById('attendanceState');
        state.textContent = row ? `${t('attendance.status', '状態')}: ${row.status} / ${t('attendance.totalMinutes', '実働(分)')}: ${row.workedMinutes || 0}` : t('attendance.noRecord', 'まだ入力がありません');
        const locked = row && !['入力中', '差戻し'].includes(row.status);
        document.getElementById('attendanceSubmit').disabled = !row || locked;
        form.querySelectorAll('input,select,button').forEach(el => { el.disabled = !!locked; });
        document.getElementById('attendanceDayBody').innerHTML = (row?.days || []).map(day => `<tr><td>${esc(day.workDate)}</td><td>${esc(day.clockIn || '')}</td><td>${esc(day.clockOut || '')}</td><td>${esc(day.breakMinutes || 0)}</td><td>${esc(day.workType || '')}</td><td>${locked ? '' : `<button type="button" class="btn btn-sm btn-outline-danger" data-action="delete" data-date="${esc(day.workDate)}">${esc(t('attendance.delete','削除'))}</button>`}</td></tr>`).join('');
    }

    function save(event) {
        event.preventDefault();
        const body = { workDate: value('attendanceDate'), clockIn: value('attendanceClockIn') || null, clockOut: value('attendanceClockOut') || null, breakMinutes: Number(value('attendanceBreak') || 0), workType: value('attendanceWorkType') };
        fetch('/api/my/attendance/daily', { method: 'POST', headers: Object.assign({'Content-Type': 'application/json'}, SES.csrf.header()), body: JSON.stringify(body) })
            .then(read).then(handle).catch(showError);
    }

    function submit() {
        fetch(`/api/my/attendance/submit?month=${encodeURIComponent(month.value)}`, { method: 'POST', headers: SES.csrf.header() })
            .then(read).then(handle).catch(showError);
    }

    function handle(data) { if (data.code !== 200) return showError(data.message); clearError(); load(); }
    function read(response) { return response.json(); }
    function value(id) { return document.getElementById(id).value; }
    function t(key, fallback) { return SES.i18n.t(key, fallback); }
    function esc(value) { return SES.escapeHtml(String(value ?? '')); }
    function clearError() { document.getElementById('attendanceError').classList.add('d-none'); }
    function showError(error) { const el = document.getElementById('attendanceError'); el.textContent = error || t('attendance.error', '勤怠の処理に失敗しました'); el.classList.remove('d-none'); }
})();
