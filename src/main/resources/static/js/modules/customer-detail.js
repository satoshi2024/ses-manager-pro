let customerId = null;
let currentActivities = [];

$(document).ready(function() {
    const pathParts = window.location.pathname.split('/');
    customerId = pathParts[pathParts.length - 1];
    
    if (customerId && !isNaN(customerId)) {
        loadCustomerInfo();
        loadCustomerSummary();
        loadActivities(1);
    }
});

function loadCustomerInfo() {
    $.ajax({
        url: '/api/customers/' + customerId,
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const c = res.data;
                $('#header-company-name').text(c.companyName);
                $('#info-companyName').text(c.companyName);
                $('#info-address').text(c.address || '-');
                $('#info-commercialFlow').text(c.commercialFlow || '-');
                
                let trustIcon = '-';
                if (c.trustLevel === 'S') trustIcon = '<span class="text-warning"><i class="bi bi-star-fill me-1"></i>S</span>';
                else if (c.trustLevel === 'A') trustIcon = '<span class="text-info"><i class="bi bi-star-half me-1"></i>A</span>';
                else if (c.trustLevel === 'B') trustIcon = '<span class="text-secondary"><i class="bi bi-star me-1"></i>B</span>';
                else if (c.trustLevel === 'C') trustIcon = '<span class="text-danger"><i class="bi bi-exclamation-triangle-fill me-1"></i>C</span>';
                $('#info-trustLevel').html(trustIcon);
                
                $('#info-contactPerson').text(c.contactPerson || '-');
                $('#info-contactPhone').text(c.contactPhone || '-');
            }
        }
    });
}

function loadCustomerSummary() {
    $.ajax({
        url: '/api/customers/' + customerId + '/summary',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                const s = res.data;
                $('#kpi-projectCount').text(s.projectCount || 0);
                $('#kpi-proposalCount').text(s.proposalCount || 0);
                $('#kpi-winRate').text(s.winRate != null ? (s.winRate * 100).toFixed(1) + '%' : '—');
                $('#kpi-activeContractCount').text(s.activeContractCount || 0);
            }
        }
    });
}

function loadActivities(page = 1) {
    $.ajax({
        url: '/api/customers/' + customerId + '/activities',
        method: 'GET',
        data: { current: page, size: 5 },
        success: function(res) {
            if (res.code === 200 && res.data) {
                currentActivities = res.data.records || res.data;
                renderActivities(currentActivities);
                if (res.data.total !== undefined) {
                    renderActivityPagination(res.data);
                }
            }
        }
    });
}

function renderActivities(records) {
    const container = $('#timeline-container');
    container.empty();

    if (!records || records.length === 0) {
        container.append('<div class="text-center text-muted py-4">活動記録がありません</div>');
        return;
    }

    let html = '<div class="timeline position-relative ps-4 py-2" style="border-left: 2px solid #343a40;">';
    
    // Create Date object for today and reset time to compare correctly
    const todayStr = new Date().toISOString().split('T')[0];

    records.forEach(act => {
        let icon = 'bi-list';
        if (act.activityType === '商談') icon = 'bi-people';
        else if (act.activityType === '訪問') icon = 'bi-geo-alt';
        else if (act.activityType === '電話') icon = 'bi-telephone';
        else if (act.activityType === 'メール') icon = 'bi-envelope';

        let isOverdue = act.nextActionDate && act.nextActionDate <= todayStr && act.completedFlag === 0;
        let borderClass = isOverdue ? 'border-danger' : 'border-secondary';
        let bgClass = isOverdue ? 'bg-danger bg-opacity-10' : 'bg-dark';
        
        let followUpBadge = isOverdue ? '<span class="badge bg-danger ms-2"><i class="bi bi-exclamation-circle me-1"></i>要フォロー</span>' : '';
        if (act.completedFlag === 1) {
            followUpBadge = '<span class="badge bg-success ms-2"><i class="bi bi-check-circle me-1"></i>完了済</span>';
        }

        let nextActionHtml = '';
        if (act.nextActionDate) {
            let color = isOverdue ? 'text-danger fw-bold' : 'text-info';
            nextActionHtml = `<div class="mt-3 small ${color}"><i class="bi bi-calendar-event me-1"></i>次回アクション予定: ${act.nextActionDate}</div>`;
        }

        let completeBtn = '';
        if (isOverdue) {
            completeBtn = `<button class="btn btn-sm btn-success ms-2" onclick="completeActivity(${act.id})"><i class="bi bi-check-lg me-1"></i>完了にする</button>`;
        } else if (act.completedFlag === 0 && act.nextActionDate) {
            completeBtn = `<button class="btn btn-sm btn-outline-success ms-2" onclick="completeActivity(${act.id})"><i class="bi bi-check-lg me-1"></i>完了にする</button>`;
        }

        html += `
            <div class="timeline-item position-relative mb-4">
                <div class="position-absolute bg-dark border border-secondary rounded-circle d-flex align-items-center justify-content-center text-info" style="width: 32px; height: 32px; left: -41px; top: 0;">
                    <i class="bi ${icon}"></i>
                </div>
                <div class="card ${bgClass} ${borderClass} shadow-sm">
                    <div class="card-body p-3">
                        <div class="d-flex justify-content-between align-items-start mb-2">
                            <div>
                                <h6 class="mb-1 text-light">${act.title} ${followUpBadge}</h6>
                                <div class="text-muted small">
                                    <i class="bi bi-calendar3 me-1"></i>${act.activityDate} 
                                    <span class="ms-2 badge bg-secondary">${act.activityType}</span>
                                </div>
                            </div>
                            <div class="btn-group btn-group-sm">
                                ${completeBtn}
                                <button type="button" class="btn btn-outline-info text-info ms-2" onclick="editActivity(${act.id})"><i class="bi bi-pencil"></i></button>
                                <button type="button" class="btn btn-outline-danger text-danger ms-2" onclick="deleteActivity(${act.id})"><i class="bi bi-trash"></i></button>
                            </div>
                        </div>
                        <div class="text-light text-wrap" style="white-space: pre-wrap;">${act.content || ''}</div>
                        ${nextActionHtml}
                    </div>
                </div>
            </div>
        `;
    });
    
    html += '</div>';
    container.html(html);
}

function renderActivityPagination(pageData) {
    const paginationContainer = $('#activity-pagination');
    if (pageData.total === 0) {
        paginationContainer.empty();
        return;
    }
    
    let html = `
        <div class="text-muted small ps-2">全 ${pageData.total} 件</div>
        <nav aria-label="Page navigation">
            <ul class="pagination pagination-sm mb-0 pe-2">
    `;
    
    if (pageData.current > 1) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadActivities(${pageData.current - 1})"><i class="bi bi-chevron-left"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-muted" href="javascript:void(0)" tabindex="-1"><i class="bi bi-chevron-left"></i></a></li>`;
    }
    
    for (let i = 1; i <= pageData.pages; i++) {
        if (i === pageData.current) {
            html += `<li class="page-item active" aria-current="page"><a class="page-link bg-info border-info text-dark fw-bold" href="javascript:void(0)">${i}</a></li>`;
        } else {
            html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadActivities(${i})">${i}</a></li>`;
        }
    }
    
    if (pageData.current < pageData.pages) {
        html += `<li class="page-item"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)" onclick="loadActivities(${pageData.current + 1})"><i class="bi bi-chevron-right"></i></a></li>`;
    } else {
        html += `<li class="page-item disabled"><a class="page-link bg-dark border-secondary text-light" href="javascript:void(0)"><i class="bi bi-chevron-right"></i></a></li>`;
    }
    
    html += `</ul></nav>`;
    paginationContainer.html(html);
}

function editActivity(id) {
    const act = currentActivities.find(a => a.id === id);
    if (act) {
        $('#act-id').val(act.id);
        $('#act-date').val(act.activityDate);
        $('#act-type').val(act.activityType);
        $('#act-title').val(act.title);
        $('#act-content').val(act.content || '');
        $('#act-next-date').val(act.nextActionDate || '');
        
        bootstrap.Modal.getOrCreateInstance(document.getElementById('activityModal')).show();
    }
}

function saveActivity() {
    if (!$('#act-date').val() || !$('#act-type').val() || !$('#act-title').val()) {
        Toast.error('必須項目を入力してください');
        return;
    }
    
    const id = $('#act-id').val();
    const data = {
        activityDate: $('#act-date').val(),
        activityType: $('#act-type').val(),
        title: $('#act-title').val(),
        content: $('#act-content').val(),
        nextActionDate: $('#act-next-date').val() || null
    };

    if (id) {
        data.id = parseInt(id);
    }
    
    const url = id ? `/api/customers/${customerId}/activities/${id}` : `/api/customers/${customerId}/activities`;
    const method = id ? 'PUT' : 'POST';

    $.ajax({
        url: url,
        method: method,
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(id ? '活動を更新しました' : '活動を登録しました');
                bootstrap.Modal.getOrCreateInstance(document.getElementById('activityModal')).hide();
                loadActivities(1);
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

function deleteActivity(id) {
    Swal.fire({
        title: '削除確認',
        text: 'この活動記録を削除しますか？',
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: '削除する',
        cancelButtonText: 'キャンセル'
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: `/api/customers/${customerId}/activities/${id}`,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success('削除しました');
                        loadActivities(1);
                    } else {
                        Toast.error(res.message || '削除に失敗しました');
                    }
                }
            });
        }
    });
}

function completeActivity(id) {
    $.ajax({
        url: `/api/customers/${customerId}/activities/${id}/complete`,
        method: 'PUT',
        success: function(res) {
            if (res.code === 200) {
                Toast.success('完了にしました');
                loadActivities(1);
            } else {
                Toast.error(res.message || '更新に失敗しました');
            }
        }
    });
}
