$(document).ready(function() {
    const now = new Date();
    const currentMonth = now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
    $('#workMonth').val(currentMonth);
    loadWorkRecords();
});

function loadWorkRecords() {
    const month = $('#workMonth').val();
    if (!month) return;

    $('#work-record-table-body').html('<tr><td colspan="8" class="text-center text-muted py-4"><div class="spinner-border spinner-border-sm me-2"></div>' + SES.i18n.t('common.msg.loading') + '</td></tr>');
    
    $.ajax({
        url: '/api/work-records/grid',
        method: 'GET',
        data: { month: month },
        success: function(res) {
            if (res.code === 200) {
                renderWorkRecords(res.data);
            } else {
                Toast.error(res.message || SES.i18n.t('common.msg.fetchFail'));
                $('#work-record-table-body').html('<tr><td colspan="8" class="text-center text-muted py-4">' + SES.i18n.t('common.msg.fetchFail') + '</td></tr>');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('common.msg.networkError'));
            $('#work-record-table-body').html('<tr><td colspan="8" class="text-center text-muted py-4">' + SES.i18n.t('common.msg.networkError') + '</td></tr>');
        }
    });
}

function renderWorkRecords(list) {
    const tbody = $('#work-record-table-body');
    tbody.empty();
    
    if (!list || list.length === 0) {
        tbody.append('<tr><td colspan="8" class="text-center text-muted py-4">' + SES.i18n.t('common.msg.noData') + '</td></tr>');
        return;
    }
    
    list.forEach(item => {
        const isConfirmed = item.status === '確定';
        const hoursInput = `<input type="number" step="0.1" class="form-control form-control-sm form-control-dark bg-secondary text-white border-dark actual-hours-input" 
                                data-contract-id="${item.contractId}" 
                                value="${item.actualHours || ''}" 
                                ${isConfirmed ? 'readonly' : ''} 
                                onblur="saveHours(this)">`;
                                
        const remarksInput = `<input type="text" class="form-control form-control-sm form-control-dark bg-secondary text-white border-dark remarks-input"
                                data-contract-id="${item.contractId}"
                                value="${SES.escapeHtml(item.remarks || '')}"
                                ${isConfirmed ? 'readonly' : ''}
                                onblur="saveHours(this)">`;

        const tr = `
            <tr>
                <td class="px-4 py-3">
                    <div class="fw-bold text-white">${SES.escapeHtml(item.engineerName || '-')}</div>
                    <div class="small text-muted">${SES.escapeHtml(item.projectName || '-')}</div>
                </td>
                <td class="py-3"><span class="font-monospace text-muted">${SES.escapeHtml(item.contractNo || '-')}</span></td>
                <td class="py-3 text-muted small">
                    ${item.settlementHoursMin ? item.settlementHoursMin + 'h' : SES.i18n.t('workRecord.table.fixed')} ~ ${item.settlementHoursMax ? item.settlementHoursMax + 'h' : SES.i18n.t('workRecord.table.fixed')}
                </td>
                <td class="py-3">
                    ${hoursInput}
                </td>
                <td class="py-3 text-accent-green fw-bold billing-amount-${item.contractId}">
                    ${item.billingAmount ? '¥' + item.billingAmount.toLocaleString() : '-'}
                </td>
                <td class="py-3 text-white payment-amount-${item.contractId}">
                    ${item.paymentAmount ? '¥' + item.paymentAmount.toLocaleString() : '-'}
                </td>
                <td class="py-3 status-cell-${item.contractId}">
                    ${getStatusBadge(item.status)}
                    ${item.status === '提出済' && item.workRecordId ? `
                        <div class="mt-1">
                            <button class="btn btn-sm btn-success py-0 px-1" onclick="approveWorkRecord(${item.workRecordId})">${SES.i18n.t('workRecord.approve','承認')}</button>
                            <button class="btn btn-sm btn-warning py-0 px-1" onclick="rejectWorkRecord(${item.workRecordId})">${SES.i18n.t('workRecord.reject','差戻し')}</button>
                        </div>` : ''}
                    ${item.workRecordId ? `<div class="mt-1"><button class="btn btn-sm btn-outline-secondary py-0 px-1 me-1" onclick="showDaily(${item.workRecordId})">日次明細</button><a class="btn btn-sm btn-outline-info py-0 px-1" href="/api/work-records/${item.workRecordId}/report.pdf" target="_blank">PDF</a></div>` : ''}
                </td>
                <td class="px-4 py-3">
                    ${remarksInput}
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function approveWorkRecord(id) {
    fetch(`/api/work-records/${id}/approve`, { method: 'POST', headers: SES.csrf.header() })
        .then(res => res.json()).then(data => {
            if (data.code === 200) loadWorkRecords();
            else alert(data.message);
        });
}

function rejectWorkRecord(id) {
    const comment = prompt(SES.i18n.t('my.timesheet.rejectComment', '差戻しコメント'));
    if (comment === null) return;
    fetch(`/api/work-records/${id}/reject`, {
        method: 'POST',
        headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
        body: JSON.stringify({ comment })
    }).then(res => res.json()).then(data => {
        if (data.code === 200) loadWorkRecords();
        else alert(data.message);
    });
}

function getStatusBadge(status) {
    if (!status) return `<span class="badge bg-secondary text-white">${SES.i18n.t('workRecord.status.未入力')}</span>`;
    let bg = 'bg-secondary';
    if(status === '入力中') bg = 'bg-primary';
    if(status === '提出済') bg = 'bg-info';
    if(status === '差戻し') bg = 'bg-warning';
    if(status === '確定') bg = 'bg-success';
    return `<span class="badge ${bg} text-white">${SES.i18n.t('workRecord.status.' + status, status)}</span>`;
}

function saveHours(element) {
    const row = $(element).closest('tr');
    const contractId = $(element).data('contract-id');
    const actualHoursStr = row.find('.actual-hours-input').val();
    const remarks = row.find('.remarks-input').val();
    const month = $('#workMonth').val();

    if (!actualHoursStr) return; // ignore empty

    const data = {
        contractId: contractId,
        workMonth: month,
        actualHours: parseFloat(actualHoursStr),
        remarks: remarks
    };

    $.ajax({
        url: '/api/work-records',
        method: 'PUT',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200 && res.data) {
                const rec = res.data;
                row.find('.billing-amount-' + contractId).text(rec.billingAmount ? '¥' + rec.billingAmount.toLocaleString() : '-');
                row.find('.payment-amount-' + contractId).text(rec.paymentAmount ? '¥' + rec.paymentAmount.toLocaleString() : '-');
                row.find('.status-cell-' + contractId).html(getStatusBadge(rec.status));
                Toast.success(SES.i18n.t('common.msg.saveSuccess'));
            } else {
                // 確定済み月の編集など業務エラーをユーザーに表示する
                Toast.error(res.message || SES.i18n.t('common.msg.saveFail'));
            }
        },
        error: function(err) {
            console.error(err);
            if (err.responseJSON && err.responseJSON.message) {
                Toast.error(err.responseJSON.message);
            } else {
                Toast.error(SES.i18n.t('common.msg.saveFail'));
            }
        }
    });
}

function confirmMonth() {
    const month = $('#workMonth').val();
    if (!month) return;

    Swal.fire({
        title: SES.i18n.t('workRecord.confirm.title'),
        text: SES.i18n.t('workRecord.confirm.message', { month }),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#198754',
        cancelButtonColor: '#6c757d',
        confirmButtonText: SES.i18n.t('common.btn.confirm'),
        cancelButtonText: SES.i18n.t('common.btn.cancel')
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/api/work-records/confirm?month=' + month,
                method: 'POST',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success(SES.i18n.t('workRecord.msg.confirmSuccess'));
                        loadWorkRecords(); // reload to reflect readonly state
                    } else {
                        Toast.error(res.message || SES.i18n.t('workRecord.msg.confirmFail'));
                    }
                },
                error: function(err) {
                    console.error(err);
                    Toast.error(SES.i18n.t('workRecord.msg.confirmFail'));
                }
            });
        }
    });
}

const dailyCache = {};
function showDaily(id) {
    if (dailyCache[id]) {
        renderDailyModal(dailyCache[id]);
        return;
    }
    fetch(`/api/work-records/${id}/daily`)
        .then(res => res.json()).then(data => {
            if (data.code === 200) {
                dailyCache[id] = data.data;
                renderDailyModal(data.data);
            } else {
                Toast.error(data.message);
            }
        }).catch(err => Toast.error('Failed to load'));
}

function renderDailyModal(list) {
    let rows = list.map(d => `<tr><td>${SES.escapeHtml(d.workDate||'')}</td><td>${d.startTime||''}</td><td>${d.endTime||''}</td><td>${d.breakMinutes||0}</td><td>${d.workedHours||0}</td><td>${SES.escapeHtml(d.remarks||'')}</td></tr>`).join('');
    if (!rows) rows = '<tr><td colspan="6" class="text-center text-muted">明細なし</td></tr>';
    const html = `<table class="table table-sm table-bordered text-start">
        <thead class="table-dark"><tr><th>日付</th><th>開始</th><th>終了</th><th>休憩(分)</th><th>稼働(h)</th><th>備考</th></tr></thead>
        <tbody>${rows}</tbody>
    </table>`;
    Swal.fire({
        title: '日次明細',
        html: html,
        width: '600px',
        showConfirmButton: true,
        confirmButtonText: '閉じる',
        confirmButtonColor: '#6c757d'
    });
}
