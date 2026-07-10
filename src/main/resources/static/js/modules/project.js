$(document).ready(function() {
    loadProjects();
    loadCustomersForSelect();
});

function loadProjects(page = 1) {
    const data = {
        current: page,
        size: 10,
        projectName: $('#searchProjectName').val(),
        status: $('#searchStatus').val()
    };

    $.ajax({
        url: '/api/projects',
        method: 'GET',
        data: data,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderProjects(res.data.records || res.data);
                if (res.data.total !== undefined) {
                    renderPagination(res.data, 'loadProjects');
                }
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

function renderPagination(pageData, loadFuncName) {
    const paginationContainer = $('.card-footer');
    if (pageData.total === 0) {
        paginationContainer.html('<div class="text-muted small ps-2">全 0 件</div>');
        return;
    }
    
    const start = (pageData.current - 1) * pageData.size + 1;
    const end = Math.min(pageData.current * pageData.size, pageData.total);
    
    let html = `
        <div class="text-muted small ps-2">
            全 ${pageData.total} 件中 ${start}-${end} 件を表示
        </div>
        <nav aria-label="Page navigation">
            <ul class="pagination pagination-sm mb-0 pe-2">
    `;
    
    if (pageData.current > 1) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current - 1})"><i class="bi bi-chevron-left"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" href="javascript:void(0)" tabindex="-1" aria-disabled="true"><i class="bi bi-chevron-left"></i></a></li>`;
    }
    
    for (let i = 1; i <= pageData.pages; i++) {
        if (i === pageData.current) {
            html += `<li class="page-item active" aria-current="page"><a class="page-link bg-success border-success text-white" href="javascript:void(0)">${i}</a></li>`;
        } else if (i <= 3 || i >= pageData.pages - 2 || Math.abs(i - pageData.current) <= 1) {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${i})">${i}</a></li>`;
        } else if (i === 4 && pageData.current > 5) {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light disabled border-0"><span class="bg-transparent border-0 text-muted">...</span></a></li>`;
        } else if (i === pageData.pages - 3 && pageData.current < pageData.pages - 4) {
             html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light disabled border-0"><span class="bg-transparent border-0 text-muted">...</span></a></li>`;
        }
    }
    
    if (pageData.current < pageData.pages) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current + 1})"><i class="bi bi-chevron-right"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)"><i class="bi bi-chevron-right"></i></a></li>`;
    }
    
    html += `</ul></nav>`;
    paginationContainer.html(html);
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
        let statusBadge = `<span class="status-badge status-secondary">${proj.status || '-'}</span>`;
        if (proj.status === '募集中') statusBadge = '<span class="status-badge status-success">募集中</span>';
        else if (proj.status === '選考中' || proj.status === '面談調整中') statusBadge = '<span class="status-badge status-warning">選考中</span>';
        else if (proj.status === '充足' || proj.status === '参画決定') statusBadge = '<span class="status-badge status-primary">参画決定</span>';

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
                        <button type="button" class="btn btn-outline-info text-info border-info" onclick="editProject(${proj.id})"><i class="bi bi-pencil"></i></button>
                        <button type="button" class="btn btn-outline-danger text-danger border-danger" onclick="deleteProject(${proj.id})"><i class="bi bi-trash"></i></button>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function editProject(id) {
    $.ajax({
        url: '/api/projects/' + id,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const proj = res.data;
                $('#proj-id').val(proj.id);
                $('#proj-projectName').val(proj.projectName);
                $('#proj-customerId').val(proj.customerId || '');
                $('#proj-unitPriceMin').val(proj.unitPriceMin);
                $('#proj-unitPriceMax').val(proj.unitPriceMax);
                $('#proj-remoteType').val(proj.remoteType);
                $('#proj-status').val(proj.status);
                
                new bootstrap.Modal(document.getElementById('projectModal')).show();
            } else {
                Toast.error('データの取得に失敗しました');
            }
        }
    });
}

function saveProject() {
    const projectName = $('#proj-projectName').val();
    if (!projectName) {
        Toast.error('案件名は必須です');
        return;
    }
    
    const id = $('#proj-id').val();
    const data = {
        projectName: projectName,
        customerId: $('#proj-customerId').val() ? parseInt($('#proj-customerId').val()) : null,
        unitPriceMin: $('#proj-unitPriceMin').val() ? parseInt($('#proj-unitPriceMin').val()) : null,
        unitPriceMax: $('#proj-unitPriceMax').val() ? parseInt($('#proj-unitPriceMax').val()) : null,
        remoteType: $('#proj-remoteType').val(),
        status: $('#proj-status').val()
    };

    if (id) {
        data.id = parseInt(id);
    }

    $.ajax({
        url: '/api/projects',
        method: id ? 'PUT' : 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(id ? '案件を更新しました' : '案件を登録しました');
                bootstrap.Modal.getInstance(document.getElementById('projectModal')).hide();
                $('#project-form')[0].reset();
                $('#proj-id').val('');
                loadProjects(1);
            } else {
                Toast.error(res.message || '保存に失敗しました');
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error('通信エラーが発生しました');
        }
    });
}

function deleteProject(id) {
    Swal.fire({
        title: '削除確認',
        text: 'この案件データを削除しますか？この操作は元に戻せません。',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: '削除する',
        cancelButtonText: 'キャンセル'
    }).then((result) => {
        if (result.isConfirmed) {
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
                },
                error: function(err) {
                    console.error(err);
                    Toast.error('通信エラーが発生しました');
                }
            });
        }
    });
}
