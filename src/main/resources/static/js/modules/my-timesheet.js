// 要員ポータル（マイ勤怠 / engineer-self-service-timesheet P1）
document.addEventListener('DOMContentLoaded', () => {
    const now = new Date();
    document.getElementById('myMonth').value =
        now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
    document.getElementById('myMonth').addEventListener('change', loadMyTimesheet);
    loadMyTimesheet();
});

let myMonthValue = null;
// 行データはインラインhandlerへ埋め込まず、contractIdをキーにJS Mapで保持する（保存型XSS対策 / R3R-16）。
const myRowMap = new Map();

function loadMyTimesheet() {
    myMonthValue = document.getElementById('myMonth').value;
    fetch('/api/my/timesheet?month=' + encodeURIComponent(myMonthValue))
        .then(res => res.json()).then(data => {
            if (data.code !== 200) { document.getElementById('myContracts').textContent = data.message || ''; return; }
            renderMy(data.data.rows || [], data.data.engineerName);
        });
}

function renderMy(rows, engineerName) {
    const container = document.getElementById('myContracts');
    container.innerHTML = '';
    myRowMap.clear();
    if (engineerName) {
        container.innerHTML += `<h5 class="mb-3 text-light"><i class="bi bi-person me-2"></i>${SES.escapeHtml(engineerName)}</h5>`;
    }
    if (rows.length === 0) {
        container.innerHTML += '<div class="alert alert-secondary">対象の契約がありません</div>';
        return;
    }
    rows.forEach(row => {
        myRowMap.set(String(row.contractId), row);
        const editable = !row.status || ['入力中', '差戻し'].includes(row.status);
        const card = document.createElement('div');
        card.className = 'card mb-3';
        let dailyRows = (row.dailies || []).map(d => `
            <tr>
                <td>${SES.escapeHtml(d.workDate)}</td>
                <td>${SES.escapeHtml(d.startTime || '')}</td>
                <td>${SES.escapeHtml(d.endTime || '')}</td>
                <td>${SES.escapeHtml(String(d.breakMinutes || 0))}</td>
                <td>${SES.escapeHtml(String(d.workedHours || ''))}</td>
                <td>${d.remarks ? SES.escapeHtml(d.remarks) : ''}</td>
                <td>${editable ? `<button class="btn btn-sm btn-outline-danger" data-action="delete-daily" data-contract-id="${SES.escapeHtml(String(row.contractId))}" data-work-date="${SES.escapeHtml(String(d.workDate))}">×</button>` : ''}</td>
            </tr>`).join('');
        // 差戻し理由は専用フィールド(rejectComment)を表示する。業務備考(remarks)は表示しない（R3R-12）。
        const rejectBanner = (row.status === '差戻し' && row.rejectComment)
            ? `<div class="alert alert-warning py-1">${SES.escapeHtml(SES.i18n.t('my.timesheet.rejectComment', '差戻しコメント'))}: ${SES.escapeHtml(row.rejectComment)}</div>` : '';
        card.innerHTML = `
            <div class="card-header d-flex justify-content-between">
                <span>${SES.escapeHtml(row.projectName || '')} <small class="text-muted">${SES.escapeHtml(row.contractNo || '')}</small></span>
                <span class="badge bg-info">${SES.escapeHtml(SES.i18n.t('workRecord.status.' + (row.status || '入力中'), row.status || '入力中'))}</span>
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
                <div class="mt-2">${SES.i18n.t('my.timesheet.total','合計')}: <strong>${SES.escapeHtml(String(row.actualHours || 0))} h</strong></div>
                ${editable ? `<button class="btn btn-primary mt-2" data-action="submit-month" data-contract-id="${SES.escapeHtml(String(row.contractId))}">${SES.i18n.t('my.timesheet.submit','提出')}</button>` : ''}
                ${(row.workRecordId && ['提出済', '確定'].includes(row.status)) ? `<a class="btn btn-outline-info mt-2" href="/api/my/timesheet/${encodeURIComponent(row.workRecordId)}/report.pdf" target="_blank">PDF</a>` : ''}
            </div>`;
        container.appendChild(card);
    });
    wireMyHandlers(container);
}

// data-action属性を持つ要素へイベントリスナーを紐付ける（インラインhandler廃止）。
function wireMyHandlers(container) {
    container.querySelectorAll('[data-action="delete-daily"]').forEach(btn => {
        btn.addEventListener('click', () => deleteMyDaily(btn.dataset.contractId, btn.dataset.workDate));
    });
    container.querySelectorAll('[data-action="submit-month"]').forEach(btn => {
        btn.addEventListener('click', () => submitMyByMonth(btn.dataset.contractId));
    });
    container.querySelectorAll('[data-action="save-daily"]').forEach(btn => {
        btn.addEventListener('click', () => saveMyDaily(btn.dataset.contractId));
    });
}

function dailyForm(contractId) {
    return `
        <div class="row g-2 align-items-end" id="dailyForm-${contractId}">
            <div class="col-12 col-md"><input type="date" class="form-control form-control-sm" name="workDate"></div>
            <div class="col-6 col-md"><input type="time" class="form-control form-control-sm" name="startTime"></div>
            <div class="col-6 col-md"><input type="time" class="form-control form-control-sm" name="endTime"></div>
            <div class="col-6 col-md"><input type="number" class="form-control form-control-sm" name="breakMinutes" value="60"></div>
            <div class="col-6 col-md"><input type="text" class="form-control form-control-sm" name="remarks" placeholder="備考"></div>
            <div class="col-12 col-md-auto"><button class="btn btn-sm btn-success w-100" data-action="save-daily" data-contract-id="${SES.escapeHtml(String(contractId))}">追加</button></div>
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
    fetch(`/api/my/timesheet/daily?contractId=${encodeURIComponent(contractId)}&workMonth=${encodeURIComponent(myMonthValue)}&workDate=${encodeURIComponent(workDate)}`, {
        method: 'DELETE', headers: SES.csrf.header()
    }).then(res => res.json()).then(data => {
        if (data.code === 200) loadMyTimesheet();
        else alert(data.message);
    });
}

function submitMyByMonth(contractId) {
    const row = myRowMap.get(String(contractId)) || {};
    // missing days calculation
    let missingDays = 0;
    let missingDates = [];
    if (myMonthValue) {
        let [y, m] = myMonthValue.split('-');
        let daysInMonth = new Date(y, m, 0).getDate();
        let enteredDates = new Set((row.dailies || []).map(d => d.workDate));
        
        let cStart = row.contractStartDate ? new Date(row.contractStartDate) : null;
        let cEnd = row.contractEndDate ? new Date(row.contractEndDate) : null;
        
        for (let i = 1; i <= daysInMonth; i++) {
            let d = new Date(y, m - 1, i);
            let dStr = y + '-' + m + '-' + String(i).padStart(2, '0');
            
            if (d.getDay() !== 0 && d.getDay() !== 6) { // Weekdays only
                let inContract = true;
                if (cStart && dStr < row.contractStartDate) inContract = false;
                if (cEnd && dStr > row.contractEndDate) inContract = false;
                
                if (inContract && !enteredDates.has(dStr)) {
                    missingDays++;
                    missingDates.push(dStr);
                }
            }
        }
    }
    
    let msg = SES.i18n.t('my.timesheet.submit', '提出') + '?';
    if (missingDays > 0) {
        msg += "\n\n未入力の平日が " + missingDays + " 日あります:\n" + missingDates.join(', ') + "\n\nこのまま提出しますか？";
    }
    
    if (!confirm(msg)) return;
    
    fetch(`/api/my/timesheet/submit-by-month?contractId=${encodeURIComponent(contractId)}&workMonth=${encodeURIComponent(myMonthValue)}`, { method: 'POST', headers: SES.csrf.header() })
        .then(res => res.json()).then(data => {
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
