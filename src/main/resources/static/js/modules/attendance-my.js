// 雇用勤怠本人画面（attendance-leave-overtime-compliance / T070、方式A休憩区間 / R2-P1-02）
(() => {
    const month = document.getElementById('attendanceMonth');
    const form = document.getElementById('attendanceDayForm');
    if (!month || !form) return;
    const now = new Date();
    month.value = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`;
    month.addEventListener('change', load);
    document.getElementById('attendanceSubmit').addEventListener('click', submit);
    form.addEventListener('submit', save);
    document.getElementById('attendanceBreakAdd').addEventListener('click', () => addBreakRow());
    addBreakRow();
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
        document.getElementById('attendanceDayBody').innerHTML = (row?.days || []).map(day => `<tr><td>${esc(day.workDate)}</td><td>${esc(day.clockIn || '')}</td><td>${esc(day.clockOut || '')}</td><td>${esc(day.breakMinutes || 0)}</td><td>${esc(breakText(day))}</td><td>${esc(day.workType || '')}</td><td>${locked ? '' : `<button type="button" class="btn btn-sm btn-outline-danger" data-action="delete" data-date="${esc(day.workDate)}">${esc(t('attendance.delete','削除'))}</button>`}</td></tr>`).join('');
    }

    function breakText(day) {
        const intervals = (day.breaks || []).filter(b => b.startTime && b.endTime);
        if (intervals.length === 0) return '';
        return intervals.map(b => `${b.startTime}-${b.endTime}`).join(', ');
    }

    function addBreakRow(breaks) {
        const container = document.getElementById('attendanceBreaks');
        const row = document.createElement('div');
        row.className = 'row g-1 align-items-center mb-1 break-row';
        row.innerHTML = `<div class="col-5"><input type="time" class="form-control form-control-sm break-start"></div>` +
            `<div class="col-5"><input type="time" class="form-control form-control-sm break-end"></div>` +
            `<div class="col-2"><button type="button" class="btn btn-sm btn-outline-danger break-remove" title="${esc(t('attendance.breakRemove','削除'))}">&times;</button></div>`;
        row.querySelector('.break-remove').addEventListener('click', () => row.remove());
        container.appendChild(row);
        if (breaks && breaks[0]) {
            row.querySelector('.break-start').value = breaks[0].startTime || '';
            row.querySelector('.break-end').value = breaks[0].endTime || '';
        }
        return row;
    }

    function save(event) {
        event.preventDefault();
        const breaks = [];
        document.querySelectorAll('#attendanceBreaks .break-row').forEach(row => {
            const start = row.querySelector('.break-start').value;
            const end = row.querySelector('.break-end').value;
            if (start || end) breaks.push({ startTime: start || null, endTime: end || null });
        });
        const body = { workDate: value('attendanceDate'), clockIn: value('attendanceClockIn') || null, clockOut: value('attendanceClockOut') || null, breaks, workType: value('attendanceWorkType') };
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
