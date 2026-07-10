$(document).ready(function() {
    loadProjects();
    loadCustomersForSelect();
});

function loadProjects() {
    $.ajax({
        url: '/api/projects',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderProjects(res.data.records || res.data);
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

function loadCustomersForSelect() {
    $.ajax({
        url: '/api/customers',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const customers = res.data.records || res.data;
                const select = $('#proj-customerId');
                select.empty();
                select.append('<option value="">顧客を選択してください...</option>');
                customers.forEach(c => {
                    select.append(`<option value="${c.id}">${c.companyName}</option>`);
                });
            }
        }
    });
}

function renderProjects(records) {
    const tbody = $('#project-table-body');
    tbody.empty();
    
    if (!records || records.length === 0) {
        tbody.append('<tr><td colspan="7" class="text-center text-muted py-4">データがありません</td></tr>');
        return;
    }
    
    records.forEach(proj => {
        // Remote Badge
        let remoteIcon = '';
        if (proj.remoteType === 'フルリモート') remoteIcon = '<i class="bi bi-check-circle-fill text-success" title="フルリモート"></i> フル';
        else if (proj.remoteType === 'ハイブリッド' || proj.remoteType === '一部リモート') remoteIcon = '<i class="bi bi-check-circle text-success" title="ハイブリッド"></i> 一部';
        else remoteIcon = '<i class="bi bi-x-circle text-danger" title="不可"></i> 不可';

        // Status Badge
        let statusBadge = `<span class="badge bg-secondary bg-opacity-20 text-secondary border border-secondary border-opacity-50 px-2 py-1">${proj.status || '-'}</span>`;
        if (proj.status === '募集中') statusBadge = '<span class="badge bg-success bg-opacity-20 text-success border border-success border-opacity-50 px-2 py-1">募集中</span>';
        else if (proj.status === '選考中' || proj.status === '面談調整中') statusBadge = '<span class="badge bg-warning bg-opacity-20 text-warning border border-warning border-opacity-50 px-2 py-1">選考中</span>';
        else if (proj.status === '充足' || proj.status === '参画決定') statusBadge = '<span class="badge bg-primary bg-opacity-20 text-primary border border-primary border-opacity-50 px-2 py-1">参画決定</span>';

        const min = proj.unitPriceMin ? (proj.unitPriceMin / 10000) + '万' : '-';
        const max = proj.unitPriceMax ? (proj.unitPriceMax / 10000) + '万' : '-';
        const priceStr = `${min} 〜 ${max}`;

        const tr = `
            <tr>
                <td class="ps-4 py-3">
                    <div class="fw-bold text-light">${proj.projectName}</div>
                    <div class="text-muted small"><i class="bi bi-code-slash me-1"></i>${proj.description || ''}</div>
                </td>
                <td>${proj.customerId || '未設定'} (ID)</td> <!-- Ideally we join with Customer table on backend to get name -->
                <td class="font-monospace">${priceStr}</td>
                <td class="text-center">${proj.requiredCount || 1}名</td>
                <td class="text-center">${remoteIcon}</td>
                <td>${statusBadge}</td>
                <td class="text-end pe-4">
                    <div class="btn-group btn-group-sm" role="group">
                        <button type="button" class="btn btn-outline-danger text-danger border-danger" onclick="deleteProject(${proj.id})"><i class="bi bi-trash"></i></button>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function saveProject() {
    const projectName = $('#proj-projectName').val();
    if (!projectName) {
        Toast.error('案件名は必須です');
        return;
    }
    
    const data = {
        projectName: projectName,
        customerId: $('#proj-customerId').val() ? parseInt($('#proj-customerId').val()) : null,
        unitPriceMin: $('#proj-unitPriceMin').val() ? parseInt($('#proj-unitPriceMin').val()) : null,
        unitPriceMax: $('#proj-unitPriceMax').val() ? parseInt($('#proj-unitPriceMax').val()) : null,
        remoteType: $('#proj-remoteType').val(),
        status: $('#proj-status').val()
    };

    $.ajax({
        url: '/api/projects',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success('案件を登録しました');
                bootstrap.Modal.getInstance(document.getElementById('projectModal')).hide();
                $('#project-form')[0].reset();
                loadProjects();
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

function deleteProject(id) {
    if (!confirm('本当に削除しますか？')) return;
    
    $.ajax({
        url: '/api/projects/' + id,
        method: 'DELETE',
        success: function(res) {
            if (res.code === 200) {
                Toast.success('削除しました');
                loadProjects();
            } else {
                Toast.error(res.message || '削除に失敗しました');
            }
        }
    });
}
