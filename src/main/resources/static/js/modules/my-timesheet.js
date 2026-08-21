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
    SES.api.get('/api/my/timesheet', { month: myMonthValue })
        .then(data => {
            const rows = (data && data.rows) || [];
            renderMySummary(rows);
            renderMy(rows, data && data.engineerName);
        })
        .catch(err => {
            // 未紐付け(403)はトーストに加え、初日の行き止まりを避ける案内を画面にも出す。
            if (err && err.message === 'Forbidden') {
                renderMyError({ code: 403 });
            }
        });
}

// 対象月の最終日を求める（提出期限の目安。バックエンドに期限設定は無いため月末を採用）。
// isoは今日の日付文字列との比較用、displayは表示用（どちらもDateの往復無しで組み立てる。
// 日付専用文字列をnew Date()でUTCとして解釈しローカルgetterで読むと、UTCより遅いTZで1日ずれるため）。
function myMonthEnd(monthValue) {
    const [y, m] = monthValue.split('-').map(Number);
    const lastDay = new Date(y, m, 0).getDate();
    const mm = String(m).padStart(2, '0');
    const dd = String(lastDay).padStart(2, '0');
    return { iso: `${y}-${mm}-${dd}`, display: `${y}/${mm}/${dd}` };
}

// 提出期限・当月合計・承認状況の要約バー。状態集計は既存の workRecord.status.* をそのまま使う。
function renderMySummary(rows) {
    const el = document.getElementById('myTimesheetSummary');
    if (!el) return;
    if (!myMonthValue || rows.length === 0) { el.innerHTML = ''; return; }

    const deadline = myMonthEnd(myMonthValue);
    const today = SES.util.getLocalDateString();
    const hasUnfinished = rows.some(row => !['提出済', '確定'].includes(row.status));
    const overdue = hasUnfinished && today > deadline.iso;

    const monthTotal = rows.reduce((sum, row) => sum + (Number(row.actualHours) || 0), 0);

    const statusOrder = ['入力中', '差戻し', '提出済', '確定'];
    const statusBadgeClass = { '入力中': 'bg-secondary', '差戻し': 'bg-danger', '提出済': 'bg-info', '確定': 'bg-success' };
    const statusCounts = {};
    rows.forEach(row => {
        const status = row.status || '入力中';
        statusCounts[status] = (statusCounts[status] || 0) + 1;
    });
    const statusBadges = statusOrder
        .filter(status => statusCounts[status])
        .map(status => `<span class="badge ${statusBadgeClass[status]} me-1">${SES.escapeHtml(SES.i18n.t('workRecord.status.' + status, status))} (${statusCounts[status]})</span>`)
        .join('');

    el.innerHTML = `
        <div class="card">
            <div class="card-body d-flex flex-wrap gap-4">
                <div>
                    <div class="text-muted small">${SES.i18n.t('my.timesheet.summary.deadline', '提出期限')}</div>
                    <div class="fw-bold ${overdue ? 'text-danger' : ''}" data-field="deadline">
                        ${SES.escapeHtml(deadline.display)}
                        ${overdue ? `<span class="badge bg-danger ms-1">${SES.escapeHtml(SES.i18n.t('my.timesheet.summary.overdue', '期限超過'))}</span>` : ''}
                    </div>
                </div>
                <div>
                    <div class="text-muted small">${SES.i18n.t('my.timesheet.summary.monthTotal', '当月合計')}</div>
                    <div class="fw-bold" data-field="monthTotal">${SES.escapeHtml(monthTotal.toFixed(1))} h</div>
                </div>
                <div>
                    <div class="text-muted small">${SES.i18n.t('my.timesheet.summary.approvalStatus', '承認状況')}</div>
                    <div data-field="approvalStatus">${statusBadges}</div>
                </div>
            </div>
        </div>`;
}

/**
 * エラー時の表示。特に未紐付け(403)は新規要員が初日に必ず見る画面なので、
 * エラー文だけを置いて行き止まりにせず、次に何をすればよいかを示す。
 */
function renderMyError(data) {
    const summary = document.getElementById('myTimesheetSummary');
    if (summary) summary.innerHTML = '';
    const container = document.getElementById('myContracts');
    container.innerHTML = '';
    const box = document.createElement('div');
    if (data.code === 403) {
        box.className = 'alert alert-warning';
        const title = document.createElement('div');
        title.className = 'fw-bold mb-1';
        title.textContent = data.message || SES.i18n.t('error.my.notLinked');
        const guide = document.createElement('div');
        guide.className = 'small';
        guide.textContent = SES.i18n.t('my.timesheet.notLinked.guide');
        box.appendChild(title);
        box.appendChild(guide);
    } else {
        box.className = 'alert alert-danger';
        box.textContent = data.message || SES.i18n.t('error.general', 'エラーが発生しました。しばらくしてから再度お試しください');
    }
    container.appendChild(box);
}

function renderMy(rows, engineerName) {
    const container = document.getElementById('myContracts');
    container.innerHTML = '';
    myRowMap.clear();
    if (engineerName) {
        container.innerHTML += `<h5 class="mb-3 text-light"><i class="bi bi-person me-2"></i>${SES.escapeHtml(engineerName)}</h5>`;
    }
    if (rows.length === 0) {
        container.innerHTML += '<div class="alert alert-secondary">'
            + SES.escapeHtml(SES.i18n.t('my.timesheet.noContracts', '対象の契約がありません'))
            + '</div>';
        return;
    }
    rows.forEach(row => {
        myRowMap.set(String(row.contractId), row);
        const editable = !row.status || ['入力中', '差戻し', '未入力'].includes(row.status);
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
                <div class="table-responsive">
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
                </div>
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
    // 日次入力は6列圧縮レイアウトを維持（col-12/col-6 + col-md）。ロールバックしない。
    return `
        <div class="row g-2 align-items-end" id="dailyForm-${contractId}">
            <div class="col-12 col-md"><input type="date" class="form-control form-control-sm" name="workDate"></div>
            <div class="col-6 col-md"><input type="time" class="form-control form-control-sm" name="startTime"></div>
            <div class="col-6 col-md"><input type="time" class="form-control form-control-sm" name="endTime"></div>
            <div class="col-6 col-md"><input type="number" class="form-control form-control-sm" name="breakMinutes" value="60"></div>
            <div class="col-6 col-md"><input type="text" class="form-control form-control-sm" name="remarks" placeholder="${SES.escapeHtml(SES.i18n.t('my.timesheet.remarks', '備考'))}"></div>
            <div class="col-12 col-md-auto"><button class="btn btn-sm btn-success w-100" data-action="save-daily" data-contract-id="${SES.escapeHtml(String(contractId))}">${SES.escapeHtml(SES.i18n.t('my.timesheet.add', '追加'))}</button></div>
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
    if (!body.workDate) {
        SES.toast.error(SES.i18n.t('validation.required', SES.i18n.t('my.timesheet.date', '日付')));
        return;
    }
    SES.api.post('/api/my/timesheet/daily', body)
        .then(() => {
            SES.toast.success(SES.i18n.t('common.saved', '保存しました'));
            loadMyTimesheet();
        })
        .catch(() => { /* SES.apiが共通トーストを表示 */ });
}

function deleteMyDaily(contractId, workDate) {
    Swal.fire({
        title: SES.i18n.t('common.deleteConfirmTitle', '削除確認'),
        text: SES.i18n.t('confirm.delete', '削除してもよろしいですか？この操作は取り消せません'),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#d33',
        confirmButtonText: SES.i18n.t('common.delete', '削除'),
        cancelButtonText: SES.i18n.t('common.cancel', 'キャンセル')
    }).then(result => {
        if (!result.isConfirmed) return;
        const url = '/api/my/timesheet/daily?contractId=' + encodeURIComponent(contractId)
            + '&workMonth=' + encodeURIComponent(myMonthValue)
            + '&workDate=' + encodeURIComponent(workDate);
        SES.api.delete(url)
            .then(() => {
                SES.toast.success(SES.i18n.t('common.deleted', '削除しました'));
                loadMyTimesheet();
            })
            .catch(() => { /* SES.apiが共通トーストを表示 */ });
    });
}

function submitMyByMonth(contractId) {
    const row = myRowMap.get(String(contractId)) || {};
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

    let html = SES.escapeHtml(SES.i18n.t('my.timesheet.submit.confirm', '提出しますか？'));
    if (missingDays > 0) {
        html = SES.escapeHtml(SES.i18n.t(
            'my.timesheet.submit.missingWeekdays',
            [missingDays, missingDates.join(', ')],
            '未入力の平日が {0} 日あります:\n{1}\n\nこのまま提出しますか？'
        )).replace(/\n/g, '<br>');
    }

    Swal.fire({
        title: SES.i18n.t('my.timesheet.submit', '提出'),
        html: html,
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: SES.i18n.t('common.ok', 'OK'),
        cancelButtonText: SES.i18n.t('common.cancel', 'キャンセル')
    }).then(result => {
        if (!result.isConfirmed) return;
        const url = '/api/my/timesheet/submit-by-month?contractId=' + encodeURIComponent(contractId)
            + '&workMonth=' + encodeURIComponent(myMonthValue);
        SES.api.post(url, {})
            .then(() => {
                SES.toast.success(SES.i18n.t('my.timesheet.submitted', '提出しました'));
                loadMyTimesheet();
            })
            .catch(() => { /* SES.apiが共通トーストを表示 */ });
    });
}

function submitMy(workRecordId) {
    Swal.fire({
        title: SES.i18n.t('my.timesheet.submit', '提出'),
        text: SES.i18n.t('my.timesheet.submit.confirm', '提出しますか？'),
        icon: 'question',
        showCancelButton: true,
        confirmButtonText: SES.i18n.t('common.ok', 'OK'),
        cancelButtonText: SES.i18n.t('common.cancel', 'キャンセル')
    }).then(result => {
        if (!result.isConfirmed) return;
        SES.api.post('/api/my/timesheet/' + encodeURIComponent(workRecordId) + '/submit', {})
            .then(() => {
                SES.toast.success(SES.i18n.t('my.timesheet.submitted', '提出しました'));
                loadMyTimesheet();
            })
            .catch(() => { /* SES.apiが共通トーストを表示 */ });
    });
}
