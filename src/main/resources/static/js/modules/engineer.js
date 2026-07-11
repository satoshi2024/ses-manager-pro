$(document).ready(function() {
    // Load engineers on page load
    loadEngineers();
    
    // Load station names for autocomplete
    loadAllStations();
});

function loadAllStations() {
    $.ajax({
        url: '/data/station_names.json',
        method: 'GET',
        dataType: 'json',
        success: function(res) {
            if (res && res.length > 0) {
                const datalist = $('#station-list');
                datalist.empty();
                // To prevent browser lag with 10k elements, modern browsers handle datalists very well,
                // but we can just append them as strings.
                let html = '';
                res.forEach(item => {
                    // Setting text content of <option> shows as lighter text on the right side in Chrome
                    html += `<option value="${item.name}">${item.pref}</option>`;
                });
                datalist.html(html);
            }
        },
        error: function(err) {
            console.error("Failed to load station names", err);
        }
    });
}

function loadEngineers(page = 1) {
    const data = {
        current: page,
        size: 10,
        fullName: $('#searchName').val(),
        status: $('#searchStatus').val(),
        employmentType: $('#searchEmpType').val()
    };

    $.ajax({
        url: '/api/engineers',
        method: 'GET',
        data: data,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderEngineers(res.data.records || res.data); // Handle pagination object if present
                if (res.data.total !== undefined) {
                    renderPagination(res.data, 'loadEngineers');
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
    
    // Prev
    if (pageData.current > 1) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current - 1})"><i class="bi bi-chevron-left"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" href="javascript:void(0)" tabindex="-1" aria-disabled="true"><i class="bi bi-chevron-left"></i></a></li>`;
    }
    
    // Pages (Simplified)
    for (let i = 1; i <= pageData.pages; i++) {
        if (i === pageData.current) {
            html += `<li class="page-item active" aria-current="page"><a class="page-link bg-primary border-primary" href="javascript:void(0)">${i}</a></li>`;
        } else if (i <= 3 || i >= pageData.pages - 2 || Math.abs(i - pageData.current) <= 1) {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${i})">${i}</a></li>`;
        } else if (i === 4 && pageData.current > 5) {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light disabled border-0"><span class="bg-transparent border-0 text-muted">...</span></a></li>`;
        } else if (i === pageData.pages - 3 && pageData.current < pageData.pages - 4) {
             html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light disabled border-0"><span class="bg-transparent border-0 text-muted">...</span></a></li>`;
        }
    }
    
    // Next
    if (pageData.current < pageData.pages) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="${loadFuncName}(${pageData.current + 1})"><i class="bi bi-chevron-right"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)"><i class="bi bi-chevron-right"></i></a></li>`;
    }
    
    html += `</ul></nav>`;
    paginationContainer.html(html);
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
        if (eng.status === '稼動中') statusBadge = '<span class="status-badge status-success">稼動中</span>';
        else if (eng.status === '提案中') statusBadge = '<span class="status-badge status-warning">提案中</span>';
        else if (eng.status === '退場予定') statusBadge = '<span class="status-badge status-danger">退場予定</span>';
        else statusBadge = '<span class="status-badge status-secondary">Bench</span>';

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
                        <button type="button" class="btn btn-outline-info text-info border-info" onclick="editEngineer(${eng.id})"><i class="bi bi-pencil"></i></button>
                        <button type="button" class="btn btn-outline-danger text-danger border-danger" onclick="deleteEngineer(${eng.id})"><i class="bi bi-trash"></i></button>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function editEngineer(id) {
    $.ajax({
        url: '/api/engineers/' + id,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const eng = res.data;
                $('#eng-id').val(eng.id);
                $('#eng-fullName').val(eng.fullName);
                $('#eng-fullNameKana').val(eng.fullNameKana);
                $('#eng-employmentType').val(eng.employmentType);
                $('#eng-status').val(eng.status);
                $('#eng-experienceYears').val(eng.experienceYears);
                $('#eng-expectedUnitPrice').val(eng.expectedUnitPrice);
                
                // Parse nearestStation
                $('#eng-nearestStation').val(eng.nearestStation || '');
                
                // モーダル表示（既存インスタンスを再利用し、二重生成・バックドロップ残りを防ぐ）
                bootstrap.Modal.getOrCreateInstance(document.getElementById('engineerModal')).show();
            } else {
                Toast.error('データの取得に失敗しました');
            }
        }
    });
}

function saveEngineer() {
    const fullName = $('#eng-fullName').val();
    if (!fullName) {
        Toast.error('氏名は必須です');
        return;
    }
    
    const id = $('#eng-id').val();
    const nearestStation = $('#eng-nearestStation').val() || '';

    const data = {
        fullName: fullName,
        fullNameKana: $('#eng-fullNameKana').val(),
        employmentType: $('#eng-employmentType').val(),
        status: $('#eng-status').val(),
        experienceYears: $('#eng-experienceYears').val() ? parseInt($('#eng-experienceYears').val()) : null,
        expectedUnitPrice: $('#eng-expectedUnitPrice').val() ? parseInt($('#eng-expectedUnitPrice').val()) : null,
        nearestStation: nearestStation
    };

    if (id) {
        data.id = parseInt(id);
    }

    $.ajax({
        url: '/api/engineers',
        method: id ? 'PUT' : 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(id ? '要員を更新しました' : '要員を登録しました');
                // getInstance は未生成時に null を返し .hide() で例外→モーダルが閉じない不具合になるため getOrCreateInstance を使う
                bootstrap.Modal.getOrCreateInstance(document.getElementById('engineerModal')).hide();
                $('#engineer-form')[0].reset();
                $('#eng-id').val('');
                loadEngineers(1);
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

function deleteEngineer(id) {
    Swal.fire({
        title: '削除確認',
        text: 'この要員データを削除しますか？この操作は元に戻せません。',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: '削除する',
        cancelButtonText: 'キャンセル'
    }).then((result) => {
        if (result.isConfirmed) {
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
                },
                error: function(err) {
                    console.error(err);
                    Toast.error('通信エラーが発生しました');
                }
            });
        }
    });
}

