$(document).ready(function() {
    loadCustomers();
});

let followUpCounts = {};

function loadCustomers(page = 1) {
    // まず要フォロー一覧を取得
    $.ajax({
        url: '/api/customers/follow-ups',
        method: 'GET',
        success: function(res) {
            followUpCounts = {};
            if (res.code === 200 && res.data) {
                let total = 0;
                res.data.forEach(f => {
                    followUpCounts[f.customerId] = (followUpCounts[f.customerId] || 0) + 1;
                    total++;
                });
                if (total > 0) {
                    $('#total-followup-badge').text(`${total}件の要フォロー`).removeClass('d-none');
                } else {
                    $('#total-followup-badge').addClass('d-none');
                }
            }
            fetchCustomerList(page);
        },
        error: function(err) {
            console.error('Follow-ups fetch error:', err);
            fetchCustomerList(page);
        }
    });
}

function fetchCustomerList(page) {
    const data = {
        current: page,
        size: 10,
        companyName: $('#searchCompanyName').val(),
        commercialFlow: $('#searchFlow').val(),
        trustLevel: $('#searchRank').val()
    };

    $.ajax({
        url: '/api/customers',
        method: 'GET',
        data: data,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderCustomers(res.data.records || res.data);
                if (res.data.total !== undefined) {
                    renderPagination(res.data, 'loadCustomers');
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
            html += `<li class="page-item active" aria-current="page"><a class="page-link bg-info border-info text-dark fw-bold" href="javascript:void(0)">${i}</a></li>`;
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

function renderCustomers(records) {
    const tbody = $('#customer-table-body');
    tbody.empty();
    
    if (!records || records.length === 0) {
        tbody.append('<tr><td colspan="6" class="text-center text-muted py-4">データがありません</td></tr>');
        return;
    }
    
    records.forEach(cust => {
        // Commercial Flow Badge
        let flowBadge = `<span class="status-badge status-secondary">${cust.commercialFlow || '-'}</span>`;
        if (cust.commercialFlow === 'エンド直') flowBadge = '<span class="status-badge status-primary">エンド直</span>';
        else if (cust.commercialFlow === '元請け') flowBadge = '<span class="status-badge status-success">元請け</span>';
        else if (cust.commercialFlow === '二次請け') flowBadge = '<span class="status-badge status-warning">二次請け</span>';

        // Trust Level
        let trustIcon = '';
        if (cust.trustLevel === 'S') trustIcon = '<div class="d-flex text-warning align-items-center"><i class="bi bi-star-fill me-1"></i>S</div>';
        else if (cust.trustLevel === 'A') trustIcon = '<div class="d-flex text-info align-items-center"><i class="bi bi-star-half me-1"></i>A</div>';
        else if (cust.trustLevel === 'B') trustIcon = '<div class="d-flex text-secondary align-items-center"><i class="bi bi-star me-1"></i>B</div>';
        else trustIcon = '<div class="d-flex text-danger align-items-center"><i class="bi bi-exclamation-triangle-fill me-1"></i>C</div>';

        let followUpBadge = '';
        const count = followUpCounts[cust.id];
        if (count && count > 0) {
            followUpBadge = `<span class="badge bg-danger rounded-pill ms-2" title="要フォロー">${count}件の要フォロー</span>`;
        }

        const tr = `
            <tr style="cursor: pointer;" onclick="location.href='/customer/${cust.id}'">
                <td class="ps-4 py-3">
                    <div class="fw-bold text-light">${cust.companyName} ${followUpBadge}</div>
                    <div class="text-muted small">${cust.address || ''}</div>
                </td>
                <td>${flowBadge}</td>
                <td>${trustIcon}</td>
                <td>${cust.contactPerson || '-'}</td>
                <td class="font-monospace">${cust.contactPhone || '-'}</td>
                <td class="text-end pe-4" onclick="event.stopPropagation();">
                    <div class="btn-group btn-group-sm" role="group">
                        <button type="button" class="btn btn-outline-info text-info border-info" onclick="editCustomer(${cust.id})"><i class="bi bi-pencil"></i></button>
                        <button type="button" class="btn btn-outline-danger text-danger border-danger" onclick="deleteCustomer(${cust.id})"><i class="bi bi-trash"></i></button>
                    </div>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function editCustomer(id) {
    $.ajax({
        url: '/api/customers/' + id,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const cust = res.data;
                $('#cust-id').val(cust.id);
                $('#cust-companyName').val(cust.companyName);
                $('#cust-commercialFlow').val(cust.commercialFlow);
                $('#cust-trustLevel').val(cust.trustLevel);
                $('#cust-contactPerson').val(cust.contactPerson);
                $('#cust-contactPhone').val(cust.contactPhone);
                
                bootstrap.Modal.getOrCreateInstance(document.getElementById('customerModal')).show();
            } else {
                Toast.error('データの取得に失敗しました');
            }
        }
    });
}

function saveCustomer() {
    const companyName = $('#cust-companyName').val();
    if (!companyName) {
        Toast.error('会社名は必須です');
        return;
    }
    
    const id = $('#cust-id').val();
    const data = {
        companyName: companyName,
        commercialFlow: $('#cust-commercialFlow').val(),
        trustLevel: $('#cust-trustLevel').val(),
        contactPerson: $('#cust-contactPerson').val(),
        contactPhone: $('#cust-contactPhone').val()
    };

    if (id) {
        data.id = parseInt(id);
    }

    $.ajax({
        url: '/api/customers',
        method: id ? 'PUT' : 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(id ? '顧客を更新しました' : '顧客を登録しました');
                bootstrap.Modal.getOrCreateInstance(document.getElementById('customerModal')).hide();
                $('#customer-form')[0].reset();
                $('#cust-id').val('');
                loadCustomers(1);
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

function deleteCustomer(id) {
    Swal.fire({
        title: '削除確認',
        text: 'この顧客データを削除しますか？この操作は元に戻せません。',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: '削除する',
        cancelButtonText: 'キャンセル'
    }).then((result) => {
        if (result.isConfirmed) {
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
                },
                error: function(err) {
                    console.error(err);
                    Toast.error('通信エラーが発生しました');
                }
            });
        }
    });
}

