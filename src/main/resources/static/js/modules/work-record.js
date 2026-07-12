$(document).ready(function() {
    const now = new Date();
    const currentMonth = now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0');
    $('#workMonth').val(currentMonth);
    loadWorkRecords();
});

function loadWorkRecords() {
    const month = $('#workMonth').val();
    if (!month) return;

    $('#work-record-table-body').html('<tr><td colspan="8" class="text-center text-muted py-4"><div class="spinner-border spinner-border-sm me-2"></div>読み込み中...</td></tr>');
    
    $.ajax({
        url: '/api/work-records/grid',
        method: 'GET',
        data: { month: month },
        success: function(res) {
            renderWorkRecords(res);
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
            $('#work-record-table-body').html('<tr><td colspan="8" class="text-center text-muted py-4">通信エラーが発生しました</td></tr>');
        }
    });
}

function renderWorkRecords(list) {
    const tbody = $('#work-record-table-body');
    tbody.empty();
    
    if (!list || list.length === 0) {
        tbody.append('<tr><td colspan="8" class="text-center text-muted py-4">データがありません</td></tr>');
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
                                value="${item.remarks || ''}" 
                                ${isConfirmed ? 'readonly' : ''} 
                                onblur="saveHours(this)">`;

        const tr = `
            <tr>
                <td class="px-4 py-3">
                    <div class="fw-bold text-white">${item.engineerName || '-'}</div>
                    <div class="small text-muted">${item.projectName || '-'}</div>
                </td>
                <td class="py-3"><span class="font-monospace text-muted">${item.contractNo || '-'}</span></td>
                <td class="py-3 text-muted small">
                    ${item.settlementHoursMin ? item.settlementHoursMin + 'h' : '固定'} ~ ${item.settlementHoursMax ? item.settlementHoursMax + 'h' : '固定'}
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
                </td>
                <td class="px-4 py-3">
                    ${remarksInput}
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function getStatusBadge(status) {
    if (!status) return `<span class="badge bg-secondary text-white">未入力</span>`;
    let bg = 'bg-secondary';
    if(status === '入力中') bg = 'bg-primary';
    if(status === '確定') bg = 'bg-success';
    return `<span class="badge ${bg} text-white">${status}</span>`;
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
            // res is WorkRecord object
            if (res && res.id) {
                row.find('.billing-amount-' + contractId).text(res.billingAmount ? '¥' + res.billingAmount.toLocaleString() : '-');
                row.find('.payment-amount-' + contractId).text(res.paymentAmount ? '¥' + res.paymentAmount.toLocaleString() : '-');
                row.find('.status-cell-' + contractId).html(getStatusBadge(res.status));
                Toast.success('保存しました');
            }
        },
        error: function(err) {
            console.error(err);
            if (err.responseJSON && err.responseJSON.message) {
                Toast.error(err.responseJSON.message);
            } else {
                Toast.error('保存に失敗しました');
            }
        }
    });
}

function confirmMonth() {
    const month = $('#workMonth').val();
    if (!month) return;

    Swal.fire({
        title: '月次確定',
        text: `${month}月の実績を確定しますか？確定後は編集できなくなります。`,
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#198754',
        cancelButtonColor: '#6c757d',
        confirmButtonText: '確定する',
        cancelButtonText: 'キャンセル'
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/api/work-records/confirm?month=' + month,
                method: 'POST',
                success: function(res) {
                    Toast.success('月次を確定しました');
                    loadWorkRecords(); // reload to reflect readonly state
                },
                error: function(err) {
                    console.error(err);
                    Toast.error('確定処理に失敗しました');
                }
            });
        }
    });
}
