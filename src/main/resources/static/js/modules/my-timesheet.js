// 要員ポータル（マイ勤怠 / engineer-self-service-timesheet P1）
document.addEventListener('DOMContentLoaded', () => {
    const now = new Date();
    document.getElementById('myMonth').value =
        now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
    document.getElementById('myMonth').addEventListener('change', loadMyTimesheet);
    loadMyTimesheet();
});

let myMonthValue = null;

function loadMyTimesheet() {
    myMonthValue = document.getElementById('myMonth').value;
    fetch('/api/my/timesheet?month=' + myMonthValue)
        .then(res => res.json()).then(data => {
            if (data.code !== 200) { document.getElementById('myContracts').innerHTML = SES.escapeHtml(data.message || ''); return; }
            renderMy(data.data.rows || []);
        });
}

function renderMy(rows) {
    const container = document.getElementById('myContracts');
    container.innerHTML = '';
    if (rows.length === 0) {
        container.innerHTML = '<div class="alert alert-secondary">対象の契約がありません</div>';
        return;
    }
    rows.forEach(row => {
        const editable = !row.status || ['入力中', '差戻し'].includes(row.status);
        const card = document.createElement('div');
        card.className = 'card mb-3';
        let dailyRows = (row.dailies || []).map(d => `
            <tr>
                <td>${SES.escapeHtml(d.workDate)}</td>
                <td>${d.startTime || ''}</td>
                <td>${d.endTime || ''}</td>
                <td>${d.breakMinutes || 0}</td>
                <td>${d.workedHours || ''}</td>
                <td>${d.remarks ? SES.escapeHtml(d.remarks) : ''}</td>
                <td>${editable ? `<button class="btn btn-sm btn-outline-danger" onclick="deleteMyDaily(${row.contractId}, '${d.workDate}')">×</button>` : ''}</td>
            </tr>`).join('');
        const rejectBanner = row.status === '差戻し'
            ? `<div class="alert alert-warning py-1">${SES.i18n.t('my.timesheet.rejectComment', '差戻しコメント')}: ${SES.escapeHtml(row.remarks || '')}</div>` : '';
        card.innerHTML = `
            <div class="card-header d-flex justify-content-between">
                <span>${SES.escapeHtml(row.projectName || '')} <small class="text-muted">${SES.escapeHtml(row.contractNo || '')}</small></span>
                <span class="badge bg-info">${SES.i18n.t('workRecord.status.' + (row.status || '入力中'), row.status || '入力中')}</span>
            </div>
            <div class="card-body">
                ${rejectBanner}
                <table class="table table-sm table-bordered">
                    <thead><tr>
                        <th>${SES.i18n.t('my.timesheet.date','日付')}</th>
                        <th>${SES.i18n.t('my.timesheet.start','開始')}</th>
                        <th>${SES.i18n.t('my.timesheet.end','終了')}</th>
                        <th>${SES.i18n.t('my.timesheet.break','休憩(分)')}</th>
                        <th>${SES.i18n.t('my.timesheet.hours','稼働(h)')}</th>
                        <th>${SES.i18n.t('my.timesheet.remarks','備考')}</th>
                        <th></th>
                    </tr></thead>
                    <tbody>${dailyRows}</tbody>
                </table>
                ${editable ? dailyForm(row.contractId) : ''}
                <div class="mt-2">${SES.i18n.t('my.timesheet.total','合計')}: <strong>${row.actualHours || 0} h</strong></div>
                ${(editable && row.workRecordId) ? `<button class="btn btn-primary mt-2" onclick="submitMy(${row.workRecordId})">${SES.i18n.t('my.timesheet.submit','提出')}</button>` : ''}
                ${row.workRecordId ? `<a class="btn btn-outline-info mt-2" href="/api/my/timesheet/${row.workRecordId}/report.pdf" target="_blank">PDF</a>` : ''}
            </div>`;
        container.appendChild(card);
    });
}

function dailyForm(contractId) {
    return `
        <div class="row g-1 align-items-end" id="dailyForm-${contractId}">
            <div class="col"><input type="date" class="form-control form-control-sm" name="workDate"></div>
            <div class="col"><input type="time" class="form-control form-control-sm" name="startTime"></div>
            <div class="col"><input type="time" class="form-control form-control-sm" name="endTime"></div>
            <div class="col"><input type="number" class="form-control form-control-sm" name="breakMinutes" value="60"></div>
            <div class="col"><input type="text" class="form-control form-control-sm" name="remarks" placeholder="備考"></div>
            <div class="col"><button class="btn btn-sm btn-success" onclick="saveMyDaily(${contractId})">追加</button></div>
        </div>`;
}

function saveMyDaily(contractId) {
    const form = document.getElementById('dailyForm-' + contractId);
    const body = {
        contractId: contractId,
        workMonth: myMonthValue,
        workDate: form.querySelector('[name=workDate]').value,
        startTime: form.querySelector('[name=startTime]').value || null,
        endTime: form.querySelector('[name=endTime]').value || null,
        breakMinutes: parseInt(form.querySelector('[name=breakMinutes]').value, 10) || 0,
        remarks: form.querySelector('[name=remarks]').value || null
    };
    if (!body.workDate) { alert('日付を入力してください'); return; }
    fetch('/api/my/timesheet/daily', {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify(body)
    }).then(res => res.json()).then(data => {
        if (data.code === 200) loadMyTimesheet();
        else alert(data.message);
    });
}

function deleteMyDaily(contractId, workDate) {
    fetch(`/api/my/timesheet/daily?contractId=${contractId}&workMonth=${myMonthValue}&workDate=${workDate}`, {
        method: 'DELETE', headers: SES.csrf.header()
    }).then(res => res.json()).then(data => {
        if (data.code === 200) loadMyTimesheet();
        else alert(data.message);
    });
}

function submitMy(workRecordId) {
    if (!confirm(SES.i18n.t('my.timesheet.submit', '提出') + '?')) return;
    fetch(`/api/my/timesheet/${workRecordId}/submit`, { method: 'POST', headers: SES.csrf.header() })
        .then(res => res.json()).then(data => {
            if (data.code === 200) loadMyTimesheet();
            else alert(data.message);
        });
}
