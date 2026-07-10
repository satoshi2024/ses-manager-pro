$(document).ready(function() {
    // Load engineers on page load
    loadEngineers();
});

function loadEngineers() {
    $.ajax({
        url: '/api/engineers',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderEngineers(res.data.records || res.data); // Handle pagination object if present
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

function renderEngineers(records) {
    const tbody = $('#engineer-table-body');
    tbody.empty();
    
    if (!records || records.length === 0) {
        tbody.append('<tr><td colspan="6" class="text-center text-muted py-4">データがありません</td></tr>');
        return;
    }
    
    records.forEach(eng => {
        // Build avatar
        const initial = eng.fullName ? eng.fullName.charAt(0) : '?';
        const kana = eng.fullNameKana || '';
        
        // Status Badge
        let statusBadge = '';
        if (eng.status === '稼動中') statusBadge = '<span class="badge bg-success bg-opacity-20 text-success border border-success border-opacity-50 px-2 py-1">稼動中</span>';
        else if (eng.status === '提案中') statusBadge = '<span class="badge bg-warning bg-opacity-20 text-warning border border-warning border-opacity-50 px-2 py-1">提案中</span>';
        else if (eng.status === '退場予定') statusBadge = '<span class="badge bg-danger bg-opacity-20 text-danger border border-danger border-opacity-50 px-2 py-1">退場予定</span>';
        else statusBadge = '<span class="badge bg-secondary bg-opacity-20 text-secondary border border-secondary border-opacity-50 px-2 py-1">Bench</span>';

        const priceStr = eng.expectedUnitPrice ? eng.expectedUnitPrice.toLocaleString() + '円' : '-';
        const expStr = eng.experienceYears ? eng.experienceYears + '年' : '-';

        const tr = `
            <tr>
                <td class="ps-4">
                    <div class="d-flex align-items-center py-1">
                        <div class="avatar bg-primary text-white rounded-circle me-3 d-flex align-items-center justify-content-center" style="width: 36px; height: 36px;">${initial}</div>
                        <div>
                            <div class="fw-bold">${eng.fullName || '-'}</div>
                            <div class="text-muted small">${kana}</div>
                        </div>
                    </div>
                </td>
                <td>${statusBadge}</td>
                <td>${eng.employmentType || '-'}</td>
                <td>${expStr}</td>
                <td>${priceStr}</td>
                <td class="text-end pe-4">
                    <div class="btn-group btn-group-sm" role="group">
                        <a href="/engineer/detail?id=${eng.id}" class="btn btn-outline-secondary text-light border-secondary"><i class="bi bi-eye"></i></a>
                        <button type="button" class="btn btn-outline-danger text-danger border-danger" onclick="deleteEngineer(${eng.id})"><i class="bi bi-trash"></i></button>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function saveEngineer() {
    const fullName = $('#eng-fullName').val();
    if (!fullName) {
        Toast.error('氏名は必須です');
        return;
    }
    
    const data = {
        fullName: fullName,
        fullNameKana: $('#eng-fullNameKana').val(),
        status: $('#eng-status').val(),
        employmentType: $('#eng-employmentType').val(),
        experienceYears: $('#eng-experienceYears').val() ? parseInt($('#eng-experienceYears').val()) : null,
        expectedUnitPrice: $('#eng-expectedUnitPrice').val() ? parseInt($('#eng-expectedUnitPrice').val()) : null
    };

    $.ajax({
        url: '/api/engineers',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success('要員を登録しました');
                bootstrap.Modal.getInstance(document.getElementById('engineerModal')).hide();
                $('#engineer-form')[0].reset();
                loadEngineers(); // Reload table
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

function deleteEngineer(id) {
    if (!confirm('本当に削除しますか？')) return;
    
    $.ajax({
        url: '/api/engineers/' + id,
        method: 'DELETE',
        success: function(res) {
            if (res.code === 200) {
                Toast.success('削除しました');
                loadEngineers();
            } else {
                Toast.error(res.message || '削除に失敗しました');
            }
        }
    });
}
