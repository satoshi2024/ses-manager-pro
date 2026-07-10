$(document).ready(function() {
    loadContracts();
    loadSelectOptions();
});

function loadContracts() {
    $('#contract-table-body').html('<tr><td colspan="7" class="text-center text-muted py-4"><div class="spinner-border spinner-border-sm me-2"></div>読み込み中...</td></tr>');
    
    $.ajax({
        url: '/api/contracts',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderContracts(res.data.records || res.data);
            } else {
                Toast.error('データの取得に失敗しました');
                $('#contract-table-body').html('<tr><td colspan="7" class="text-center text-muted py-4">データの取得に失敗しました</td></tr>');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
            $('#contract-table-body').html('<tr><td colspan="7" class="text-center text-muted py-4">通信エラーが発生しました</td></tr>');
        }
    });
}

function loadSelectOptions() {
    // Load Engineers
    $.get('/api/engineers', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#cont-engineerId');
            select.empty().append('<option value="">要員を選択...</option>');
            (res.data.records || res.data).forEach(e => select.append(`<option value="${e.id}">${e.fullName}</option>`));
        }
    });
    // Load Projects
    $.get('/api/projects', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#cont-projectId');
            select.empty().append('<option value="">案件を選択...</option>');
            (res.data.records || res.data).forEach(p => select.append(`<option value="${p.id}">${p.projectName}</option>`));
        }
    });
    // Load Customers
    $.get('/api/customers', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#cont-customerId');
            select.empty().append('<option value="">顧客を選択...</option>');
            (res.data.records || res.data).forEach(c => select.append(`<option value="${c.id}">${c.companyName}</option>`));
        }
    });
}

function renderContracts(list) {
    const tbody = $('#contract-table-body');
    tbody.empty();
    
    if (!list || list.length === 0) {
        tbody.append('<tr><td colspan="7" class="text-center text-muted py-4">データがありません</td></tr>');
        return;
    }
    
    list.forEach(c => {
        const tr = `
            <tr>
                <td class="px-4 py-3"><span class="font-monospace text-muted">${c.contractNo || 'C-' + c.id}</span></td>
                <td class="py-3">
                    <div class="fw-bold text-white">${c.engineerId || '-'} (ID)</div>
                    <div class="small text-muted">${c.contractType || '準委任'}</div>
                </td>
                <td class="py-3">
                    <div class="text-white">${c.customerId || '-'} (ID)</div>
                    <div class="small text-muted text-truncate" style="max-width:200px;">${c.projectId || '-'} (ID)</div>
                </td>
                <td class="py-3 text-white small">
                    <div><i class="bi bi-play-circle text-success me-1"></i>${c.startDate || '-'}</div>
                    <div><i class="bi bi-stop-circle text-danger me-1"></i>${c.endDate || '-'}</div>
                </td>
                <td class="py-3">
                    <div class="text-accent-green fw-bold">¥${c.sellingPrice ? c.sellingPrice.toLocaleString() : '---'}円</div>
                    <div class="small text-muted">¥${c.costPrice ? c.costPrice.toLocaleString() : '---'}円</div>
                </td>
                <td class="py-3">
                    ${getStatusBadge(c.status)}
                </td>
                <td class="px-4 py-3 text-end">
                    <button type="button" class="btn btn-outline-danger btn-sm text-danger border-danger" onclick="deleteContract(${c.id})"><i class="bi bi-trash"></i></button>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function getStatusBadge(status) {
    let bg = 'status-secondary';
    if(status === '稼動中') bg = 'status-success';
    if(status === '準備中') bg = 'status-primary';
    if(status === '終了') bg = 'status-secondary';
    if(status === '解約') bg = 'status-danger';
    return `<span class="status-badge ${bg}">${status || '準備中'}</span>`;
}

function saveContract() {
    const engineerId = $('#cont-engineerId').val();
    const projectId = $('#cont-projectId').val();
    
    if (!engineerId || !projectId) {
        Toast.error('要員と案件は必須です');
        return;
    }
    
    const data = {
        engineerId: parseInt(engineerId),
        projectId: parseInt(projectId),
        customerId: $('#cont-customerId').val() ? parseInt($('#cont-customerId').val()) : null,
        startDate: $('#cont-startDate').val() || null,
        endDate: $('#cont-endDate').val() || null,
        sellingPrice: $('#cont-sellingPrice').val() ? parseInt($('#cont-sellingPrice').val()) : null,
        costPrice: $('#cont-costPrice').val() ? parseInt($('#cont-costPrice').val()) : null,
        status: '準備中' // Default status
    };

    $.ajax({
        url: '/api/contracts',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success('契約を登録しました');
                bootstrap.Modal.getInstance(document.getElementById('contractModal')).hide();
                $('#contract-form')[0].reset();
                loadContracts();
            } else {
                Toast.error(res.message || '登録に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}

function deleteContract(id) {
    Swal.fire({
        title: '削除確認',
        text: 'この契約データを削除しますか？この操作は元に戻せません。',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: '削除する',
        cancelButtonText: 'キャンセル'
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/api/contracts/' + id,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success('削除しました');
                        loadContracts();
                    } else {
                        Toast.error(res.message || '削除に失敗しました');
                    }
                },
                error: function(err) {
                    console.error(err);
                    Toast.error('通信エラーが発生しました');
                }
            });
        }
    });
}
