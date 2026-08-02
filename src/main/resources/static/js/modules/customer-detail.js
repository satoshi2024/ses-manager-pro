let customerId = null;
let currentActivities = [];

$(document).ready(function() {
    const pathParts = window.location.pathname.split('/');
    customerId = pathParts[pathParts.length - 1];
    
    if (customerId && !isNaN(customerId)) {
        loadCustomerInfo();
        loadCustomerSummary();
        loadCrmContext();
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

function loadCrmContext() {
    $('#contact-export').attr('href', '/api/customers/' + customerId + '/contacts/export');
    $.ajax({
        url: '/api/customers/' + customerId + '/timeline',
        method: 'GET',
        success: function(res) {
            if (res.code !== 200 || !res.data) return;
            renderContacts(res.data.contacts || []);
            renderOpportunities(res.data.opportunities || []);
            populateActivityRelations(res.data.contacts || [], res.data.opportunities || []);
            loadActivityAssignees();
        }
    });
}

function renderContacts(contacts) {
    window._crmContacts = contacts || [];
    const tbody = $('#customer-contact-table tbody');
    if (!contacts.length) {
        tbody.html('<tr><td colspan="5" class="text-center text-muted py-3">' + SES.i18n.t('customer.contacts.empty', '担当者情報がありません') + '</td></tr>');
        return;
    }
    tbody.html(contacts.map(c => `<tr>
        <td>${SES.escapeHtml(c.name || '-')} ${c.primaryFlag === 1 ? '<span class="badge bg-info text-dark ms-1">主</span>' : ''}</td>
        <td>${SES.escapeHtml([c.department, c.position].filter(Boolean).join(' / ') || '-')}</td>
        <td class="font-monospace">${SES.escapeHtml(c.email || '-')}<br><span class="text-muted small">${SES.escapeHtml(c.phone || '')}</span></td>
        <td><span class="badge ${c.status === '有効' ? 'bg-success' : 'bg-secondary'}">${SES.escapeHtml(c.status || '-')}</span></td>
        <td class="text-end"><button class="btn btn-sm btn-outline-info" onclick="openContactModal(${c.id})"><i class="bi bi-pencil"></i></button>${c.status === '有効' ? `<button class="btn btn-sm btn-outline-danger ms-1" onclick="retireContact(${c.id}, ${c.version || 1})"><i class="bi bi-person-dash"></i></button>` : ''}</td>
    </tr>`).join(''));
}

function openContactModal(id) {
    const contacts = window._crmContacts || [];
    const c = id ? contacts.find(x => x.id === id) : null;
    $('#contact-form')[0].reset(); $('#contact-id').val(c ? c.id : ''); $('#contact-version').val(c ? c.version : '');
    $('#contact-valid-from').val(c && c.validFrom ? c.validFrom : SES.util.getLocalDateString());
    if (c) { $('#contact-name').val(c.name || ''); $('#contact-name-kana').val(c.nameKana || ''); $('#contact-department').val(c.department || ''); $('#contact-position').val(c.position || ''); $('#contact-roles').val(c.rolesJson || ''); $('#contact-email').val(c.email || ''); $('#contact-phone').val(c.phone || ''); $('#contact-valid-to').val(c.validTo || ''); $('#contact-primary').prop('checked', c.primaryFlag === 1); }
    bootstrap.Modal.getOrCreateInstance(document.getElementById('contactModal')).show();
}

function saveContact() {
    const id = $('#contact-id').val();
    const data = { name: $('#contact-name').val(), nameKana: $('#contact-name-kana').val(), department: $('#contact-department').val(), position: $('#contact-position').val(), rolesJson: $('#contact-roles').val() || null, email: $('#contact-email').val() || null, phone: $('#contact-phone').val() || null, primaryFlag: $('#contact-primary').prop('checked') ? 1 : 0, validFrom: $('#contact-valid-from').val(), validTo: $('#contact-valid-to').val() || null, status: '有効', version: $('#contact-version').val() ? Number($('#contact-version').val()) : null };
    const save = () => $.ajax({ url: `/api/customers/${customerId}/contacts${id ? '/' + id : ''}`, method: id ? 'PUT' : 'POST', contentType: 'application/json', data: JSON.stringify(data) }).done(res => { if (res.code === 200) { bootstrap.Modal.getInstance(document.getElementById('contactModal')).hide(); loadCrmContext(); Toast.success(SES.i18n.t('success.save')); } else Toast.error(res.message); }).fail(xhr => Toast.error((xhr.responseJSON || {}).message || SES.i18n.t('error.saveFailed')));
    $.get(`/api/customers/${customerId}/contacts/duplicates`, { email: data.email, phone: data.phone, excludeId: id || null }).done(res => {
        if (res.code === 200 && (res.data || []).length) {
            Swal.fire({ title: SES.i18n.t('customer.contacts.duplicateTitle', '重複候補があります'), text: SES.i18n.t('customer.contacts.duplicateText', '同じメールまたは電話番号の担当者が存在します。自動統合は行いません。'), icon: 'warning', showCancelButton: true, confirmButtonText: SES.i18n.t('common.save'), cancelButtonText: SES.i18n.t('common.cancel') }).then(r => { if (r.isConfirmed) save(); });
        } else save();
    }).fail(save);
}

function retireContact(id, version) {
    Swal.fire({ title: SES.i18n.t('common.deleteConfirmTitle'), text: SES.i18n.t('customer.contacts.retireConfirm'), icon: 'warning', showCancelButton: true, confirmButtonText: SES.i18n.t('customer.contacts.retire'), cancelButtonText: SES.i18n.t('common.cancel') }).then(r => { if (!r.isConfirmed) return; $.ajax({ url: `/api/customers/${customerId}/contacts/${id}/retire`, method: 'PUT', data: { version } }).done(res => { if (res.code === 200) loadCrmContext(); else Toast.error(res.message); }); });
}

function populateActivityRelations(contacts, opportunities) {
    const contactOptions = ['<option value="">-</option>'].concat((contacts || []).map(c => `<option value="${c.id}">${SES.escapeHtml(c.name || '-')}</option>`));
    const opportunityOptions = ['<option value="">-</option>'].concat((opportunities || []).map(o => `<option value="${o.id}">${SES.escapeHtml(o.title || '-')}</option>`));
    $('#act-contact').html(contactOptions.join(''));
    $('#act-opportunity').html(opportunityOptions.join(''));
}

function loadActivityAssignees() {
    $.get('/api/crm/leads/assignees', function(res) {
        if (res.code !== 200) return;
        $('#act-assignee').html(['<option value="">-</option>'].concat((res.data || []).map(u => `<option value="${u.value}">${SES.escapeHtml(u.label || '-')}</option>`)).join(''));
    });
}

function renderOpportunities(opportunities) {
    const container = $('#customer-opportunity-list');
    if (!opportunities.length) {
        container.html('<div class="text-muted">' + SES.i18n.t('customer.opportunities.empty', '関連商機がありません') + '</div>');
        return;
    }
    container.html(opportunities.map(o => `<div class="border-bottom border-secondary pb-2 mb-2">
        <div class="fw-bold text-light">${SES.escapeHtml(o.title || '-')}</div>
        <div class="small text-muted">${SES.escapeHtml(o.stage || '-')} · ${SES.escapeHtml(o.expectedStartMonth || '-')}</div>
    </div>`).join(''));
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
        container.append('<div class="text-center text-muted py-4">' + SES.i18n.t('customer.activity.empty') + '</div>');
        return;
    }

    let html = '<div class="timeline position-relative ps-4 py-2" style="border-left: 2px solid #343a40;">';
    
    // Create Date object for today and reset time to compare correctly
    const todayStr = SES.util.getLocalDateString();

    records.forEach(act => {
        let icon = 'bi-list';
        if (act.activityType === '商談') icon = 'bi-people';
        else if (act.activityType === '訪問' || act.activityType === 'visit') icon = 'bi-geo-alt';
        else if (act.activityType === '電話' || act.activityType === 'phone') icon = 'bi-telephone';
        else if (act.activityType === 'メール' || act.activityType === 'email') icon = 'bi-envelope';

        let isOverdue = act.nextActionDate && act.nextActionDate <= todayStr && act.completedFlag === 0;
        let borderClass = isOverdue ? 'border-danger' : 'border-secondary';
        let bgClass = isOverdue ? 'bg-danger bg-opacity-10' : 'bg-dark';
        
        let followUpBadge = isOverdue ? `<span class="badge bg-danger ms-2"><i class="bi bi-exclamation-circle me-1"></i>${SES.i18n.t('customer.followUpNeeded')}</span>` : '';
        if (act.completedFlag === 1) {
            followUpBadge = `<span class="badge bg-success ms-2"><i class="bi bi-check-circle me-1"></i>${SES.i18n.t('customer.activity.completed')}</span>`;
        }

        let nextActionHtml = '';
        if (act.nextActionDate) {
            let color = isOverdue ? 'text-danger fw-bold' : 'text-info';
            nextActionHtml = `<div class="mt-3 small ${color}"><i class="bi bi-calendar-event me-1"></i>${SES.i18n.t('customer.activity.nextDate')}: ${act.nextActionDate}</div>`;
        }

        let completeBtn = '';
        if (isOverdue) {
            completeBtn = `<button class="btn btn-sm btn-success ms-2" onclick="completeActivity(${act.id})"><i class="bi bi-check-lg me-1"></i>${SES.i18n.t('customer.activity.markCompleted')}</button>`;
        } else if (act.completedFlag === 0 && act.nextActionDate) {
            completeBtn = `<button class="btn btn-sm btn-outline-success ms-2" onclick="completeActivity(${act.id})"><i class="bi bi-check-lg me-1"></i>${SES.i18n.t('customer.activity.markCompleted')}</button>`;
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
                                <h6 class="mb-1 text-light">${SES.escapeHtml(act.title)} ${followUpBadge}</h6>
                                <div class="text-muted small">
                                    <i class="bi bi-calendar3 me-1"></i>${act.activityDate}
                                    <span class="ms-2 badge bg-secondary">${SES.i18n.e('customerActivityType', act.activityType)}</span>
                                </div>
                            </div>
                            <div class="btn-group btn-group-sm">
                                ${completeBtn}
                                <button type="button" class="btn btn-outline-info text-info ms-2" onclick="editActivity(${act.id})"><i class="bi bi-pencil"></i></button>
                                <button type="button" class="btn btn-outline-danger text-danger ms-2" onclick="deleteActivity(${act.id})"><i class="bi bi-trash"></i></button>
                            </div>
                        </div>
                        <div class="text-light text-wrap" style="white-space: pre-wrap;">${SES.escapeHtml(act.content || '')}</div>
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
        <div class="text-muted small ps-2">${SES.i18n.t('common.page.totalCount', [pageData.total])}</div>
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
        $('#act-contact').val(act.contactId || '');
        $('#act-opportunity').val(act.opportunityId || '');
        $('#act-assignee').val(act.assigneeUserId || '');
        
        bootstrap.Modal.getOrCreateInstance(document.getElementById('activityModal')).show();
    }
}

function saveActivity() {
    if (!$('#act-date').val() || !$('#act-type').val() || !$('#act-title').val()) {
        Toast.error(SES.i18n.t('error.requiredFields'));
        return;
    }
    
    const id = $('#act-id').val();
    const data = {
        activityDate: $('#act-date').val(),
        activityType: $('#act-type').val(),
        title: $('#act-title').val(),
        content: $('#act-content').val(),
        nextActionDate: $('#act-next-date').val() || null,
        contactId: $('#act-contact').val() ? Number($('#act-contact').val()) : null,
        opportunityId: $('#act-opportunity').val() ? Number($('#act-opportunity').val()) : null,
        assigneeUserId: $('#act-assignee').val() ? Number($('#act-assignee').val()) : null
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
                Toast.success(id ? SES.i18n.t('success.updateActivity') : SES.i18n.t('success.createActivity'));
                bootstrap.Modal.getOrCreateInstance(document.getElementById('activityModal')).hide();
                loadActivities(1);
            } else {
                Toast.error(res.message || SES.i18n.t('error.saveFailed'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('error.networkError'));
        }
    });
}

function deleteActivity(id) {
    Swal.fire({
        title: SES.i18n.t('common.deleteConfirmTitle'),
        text: SES.i18n.t('confirm.deleteActivity'),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: SES.i18n.t('common.delete'),
        cancelButtonText: SES.i18n.t('common.cancel')
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: `/api/customers/${customerId}/activities/${id}`,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success(SES.i18n.t('success.delete'));
                        loadActivities(1);
                    } else {
                        Toast.error(res.message || SES.i18n.t('error.deleteFailed'));
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
                Toast.success(SES.i18n.t('success.completed'));
                loadActivities(1);
            } else {
                Toast.error(res.message || SES.i18n.t('error.updateFailed'));
            }
        }
    });
}
