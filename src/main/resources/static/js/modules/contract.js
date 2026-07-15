$(document).ready(function() {
    loadContracts();
    loadSelectOptions();
});

function loadContracts() {
    $('#contract-table-body').html('<tr><td colspan="7" class="text-center text-muted py-4"><div class="spinner-border spinner-border-sm me-2"></div>' + SES.i18n.t('js.common.loading') + '</td></tr>');
    
    const params = {
        status: $('#search-form [name="status"]').val(),
        customerId: $('#search-customerId').val(),
        salesUserId: $('#search-salesUserId').val(),
        contractNo: $('#search-form [name="contractNo"]').val(),
        endDateFrom: $('#search-form [name="endDateFrom"]').val(),
        endDateTo: $('#search-form [name="endDateTo"]').val()
    };

    $.ajax({
        url: '/api/contracts',
        method: 'GET',
        data: params,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderContracts(res.data.records || res.data);
            } else {
                Toast.error(SES.i18n.t('js.common.error_fetch'));
                $('#contract-table-body').html(`<tr><td colspan="7" class="text-center text-muted py-4">${SES.i18n.t('js.common.error_fetch')}</td></tr>`);
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('js.common.error_network'));
            $('#contract-table-body').html(`<tr><td colspan="7" class="text-center text-muted py-4">${SES.i18n.t('js.common.error_network')}</td></tr>`);
        }
    });
}

function loadSelectOptions() {
    // Load Engineers
    $.get('/api/engineers', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#cont-engineerId');
            select.empty().append(`<option value="">${SES.i18n.t('proposal.engineer.select')}</option>`);
            (res.data.records || res.data).forEach(e => select.append(`<option value="${e.id}">${SES.escapeHtml(e.fullName)}</option>`));
        }
    });
    // Load Projects
    $.get('/api/projects', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#cont-projectId');
            select.empty().append(`<option value="">${SES.i18n.t('proposal.project.select')}</option>`);
            (res.data.records || res.data).forEach(p => select.append(`<option value="${p.id}">${SES.escapeHtml(p.projectName)}</option>`));
        }
    });
    // Load Customers
    $.get('/api/customers', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#cont-customerId');
            const searchSelect = $('#search-customerId');
            select.empty().append(`<option value="">${SES.i18n.t('contract.customer.select')}</option>`);
            searchSelect.empty().append(`<option value="">${SES.i18n.t('contract.customer.filter')}</option>`);
            (res.data.records || res.data).forEach(c => {
                select.append(`<option value="${c.id}">${SES.escapeHtml(c.companyName)}</option>`);
                searchSelect.append(`<option value="${c.id}">${SES.escapeHtml(c.companyName)}</option>`);
            });
        }
    });
    // Load Sales Reps
    $.get('/api/engineers/sales-user-options', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#cont-salesUserId');
            const searchSelect = $('#search-salesUserId');
            select.empty().append(`<option value="">${SES.i18n.t('contract.salesRep.select')}</option>`);
            searchSelect.empty().append(`<option value="">${SES.i18n.t('contract.salesRep.filter')}</option>`);
            res.data.forEach(u => {
                select.append(`<option value="${u.id}">${SES.escapeHtml(u.realName)}</option>`);
                searchSelect.append(`<option value="${u.id}">${SES.escapeHtml(u.realName)}</option>`);
            });
        }
    });

    // Auto preset primary sales rep when engineer is selected
    $('#cont-engineerId').on('change', function() {
        const engId = $(this).val();
        $('#cont-salesUserId').val('');
        if (engId) {
            $.get(`/api/engineers/${engId}/sales-reps`, function(res) {
                if(res.code === 200 && res.data) {
                    const primary = res.data.find(r => r.primaryFlag === 1);
                    if (primary) {
                        $('#cont-salesUserId').val(primary.salesUserId);
                    }
                }
            });
        }
    });
}

function renderContracts(list) {
    const tbody = $('#contract-table-body');
    tbody.empty();
    
    if (!list || list.length === 0) {
        tbody.append(`<tr><td colspan="7" class="text-center text-muted py-4">${SES.i18n.t('common.noData')}</td></tr>`);
        return;
    }
    
    list.forEach(c => {
        const engineerName = c.engineerName != null ? c.engineerName : SES.i18n.t('js.contract.engineer_deleted');
        const customerName = c.customerName != null ? c.customerName : '-';
        const projectName = c.projectName != null ? c.projectName : '-';
        const contractNo = c.contractNo != null ? c.contractNo : ('C-' + c.id);

        const tr = `
            <tr>
                <td class="px-4 py-3"><span class="font-monospace text-muted">${SES.escapeHtml(contractNo)}</span></td>
                <td class="py-3">
                    <div class="fw-bold text-white">${SES.escapeHtml(engineerName)}</div>
                    <div class="small text-muted">${SES.escapeHtml(c.contractType || SES.i18n.t('js.contract.type.quasi'))}</div>
                </td>
                <td class="py-3">
                    <div class="text-white">${SES.escapeHtml(customerName)}</div>
                    <div class="small text-muted text-truncate" style="max-width:200px;">${SES.escapeHtml(projectName)}</div>
                </td>
                <td class="py-3 text-white small">
                    ${SES.escapeHtml(c.salesUserName || '-')}
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
    if(status === SES.i18n.t('contract.status.active')) bg = 'status-success';
    if(status === SES.i18n.t('contract.status.preparing')) bg = 'status-primary';
    if(status === SES.i18n.t('contract.status.ended')) bg = 'status-secondary';
    if(status === SES.i18n.t('contract.status.cancelled')) bg = 'status-danger';
    return `<span class="status-badge ${bg}">${status || SES.i18n.t('contract.status.preparing')}</span>`;
}

function saveContract() {
    const engineerId = $('#cont-engineerId').val();
    const projectId = $('#cont-projectId').val();
    
    if (!engineerId || !projectId) {
        Toast.error(SES.i18n.t('js.contract.error.engineer_project'));
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
        salesUserId: $('#cont-salesUserId').val() ? parseInt($('#cont-salesUserId').val()) : null,
        commissionBaseType: $('#cont-commissionBaseType').val() || null,
        commissionRate: $('#cont-commissionRate').val() ? parseFloat($('#cont-commissionRate').val()) : null,
        status: SES.i18n.t('contract.status.preparing') // Default status
    };

    $.ajax({
        url: '/api/contracts',
        method: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.contract.success.register'));
                bootstrap.Modal.getOrCreateInstance(document.getElementById('contractModal')).hide();
                $('#contract-form')[0].reset();
                loadContracts();
            } else {
                Toast.error(res.message || SES.i18n.t('js.contract.error.register'));
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('js.common.error_network'));
        }
    });
}

// 現在の検索条件(#search-form)を反映してExcel出力する。
// バイナリレスポンスのため $.ajax ではなく window.location.href で直接ダウンロードさせる
// (common.js の ajaxSetup complete ハンドラが非JSONレスポンスをセッション切れと誤検知するのを避けるため)
function exportContracts() {
    const params = {
        status: $('#search-form [name="status"]').val(),
        customerId: $('#search-customerId').val(),
        salesUserId: $('#search-salesUserId').val(),
        contractNo: $('#search-form [name="contractNo"]').val(),
        endDateFrom: $('#search-form [name="endDateFrom"]').val(),
        endDateTo: $('#search-form [name="endDateTo"]').val()
    };
    window.location.href = '/api/contracts/export?' + $.param(params, true);
}

function deleteContract(id) {
    Swal.fire({
        title: SES.i18n.t('js.project.delete.title'),
        text: SES.i18n.t('js.contract.delete.text'),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: SES.i18n.t('js.project.delete.confirm'),
        cancelButtonText: SES.i18n.t('js.project.delete.cancel')
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/api/contracts/' + id,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success(SES.i18n.t('js.project.delete.success'));
                        loadContracts();
                    } else {
                        Toast.error(res.message || SES.i18n.t('js.project.delete.error'));
                    }
                },
                error: function(err) {
                    console.error(err);
                    Toast.error(SES.i18n.t('js.common.error_network'));
                }
            });
        }
    });
}
