$(document).ready(function() {
    loadCustomers();
});

function loadCustomers() {
    $.ajax({
        url: '/api/customers',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderCustomers(res.data.records || res.data);
            } else {
                Toast.error('データの取得に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}

function renderCustomers(records) {
    const tbody = $('#customer-table-body');
    tbody.empty();
    
    if (!records || records.length === 0) {
        tbody.append('<tr><td colspan="6" class="text-center text-muted py-4">データがありません</td></tr>');
        return;
    }
    
    records.forEach(cust => {
        // Commercial Flow Badge
        let flowBadge = `<span class="badge bg-secondary bg-opacity-20 text-secondary border border-secondary border-opacity-50 px-2 py-1">${cust.commercialFlow || '-'}</span>`;
        if (cust.commercialFlow === 'エンド直') flowBadge = '<span class="badge bg-primary bg-opacity-20 text-primary border border-primary border-opacity-50 px-2 py-1">エンド直</span>';
        else if (cust.commercialFlow === '元請け') flowBadge = '<span class="badge bg-success bg-opacity-20 text-success border border-success border-opacity-50 px-2 py-1">元請け</span>';
        else if (cust.commercialFlow === '二次請け') flowBadge = '<span class="badge bg-warning bg-opacity-20 text-warning border border-warning border-opacity-50 px-2 py-1">二次請け</span>';

        // Trust Level
        let trustIcon = '';
        if (cust.trustLevel === 'S') trustIcon = '<div class="d-flex text-warning align-items-center"><i class="bi bi-star-fill me-1"></i>S</div>';
        else if (cust.trustLevel === 'A') trustIcon = '<div class="d-flex text-info align-items-center"><i class="bi bi-star-half me-1"></i>A</div>';
        else if (cust.trustLevel === 'B') trustIcon = '<div class="d-flex text-secondary align-items-center"><i class="bi bi-star me-1"></i>B</div>';
        else trustIcon = '<div class="d-flex text-danger align-items-center"><i class="bi bi-exclamation-triangle-fill me-1"></i>C</div>';

        const tr = `
            <tr>
                <td class="ps-4 py-3">
                    <div class="fw-bold text-light">${cust.companyName}</div>
                    <div class="text-muted small">${cust.address || ''}</div>
                </td>
                <td>${flowBadge}</td>
                <td>${trustIcon}</td>
                <td>${cust.contactPerson || '-'}</td>
                <td class="font-monospace">${cust.contactPhone || '-'}</td>
                <td class="text-end pe-4">
                    <div class="btn-group btn-group-sm" role="group">
                        <button type="button" class="btn btn-outline-danger text-danger border-danger" onclick="deleteCustomer(${cust.id})"><i class="bi bi-trash"></i></button>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function saveCustomer() {
    const companyName = $('#cust-companyName').val();
    if (!companyName) {
        Toast.error('会社名は必須です');
        return;
    }
    
    const data = {
        companyName: companyName,
        commercialFlow: $('#cust-commercialFlow').val(),
        trustLevel: $('#cust-trustLevel').val(),
        contactPerson: $('#cust-contactPerson').val(),
        contactPhone: $('#cust-contactPhone').val()
    };

    $.ajax({
        url: '/api/customers',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success('顧客を登録しました');
                bootstrap.Modal.getInstance(document.getElementById('customerModal')).hide();
                $('#customer-form')[0].reset();
                loadCustomers();
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

function deleteCustomer(id) {
    if (!confirm('本当に削除しますか？')) return;
    
    $.ajax({
        url: '/api/customers/' + id,
        method: 'DELETE',
        success: function(res) {
            if (res.code === 200) {
                Toast.success('削除しました');
                loadCustomers();
            } else {
                Toast.error(res.message || '削除に失敗しました');
            }
        }
    });
}
