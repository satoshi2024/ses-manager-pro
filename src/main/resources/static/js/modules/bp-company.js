/**
 * BP会社管理 JS モジュール
 */
$(document.ready ? $(document).ready(initBpCompany) : $(initBpCompany));

function initBpCompany() {
    if ($('#bpCompanyTableBody').length) {
        loadBpCompanies(1);
    }
    if ($('#bpCompanyId').length && $('#bpCompanyId').val()) {
        loadBpCompanyDetail($('#bpCompanyId').val());
    }
}

function loadBpCompanies(page) {
    const keyword = $('#keyword').val();
    const entityType = $('#entityType').val();
    const status = $('#status').val();

    $.ajax({
        url: '/api/bp-companies',
        type: 'GET',
        data: { keyword, entityType, status, page, size: 10 },
        success: function (res) {
            if (res.code === 200) {
                renderTable(res.data.records);
                renderPagination(res.data);
            }
        }
    });
}

function renderTable(records) {
    const tbody = $('#bpCompanyTableBody');
    tbody.empty();

    if (!records || records.length === 0) {
        tbody.append('<tr><td colspan="8" class="text-center text-muted py-4">データが存在しません</td></tr>');
        return;
    }

    records.forEach(c => {
        const entityLabel = c.entityType === 'CORPORATE' ? '法人' : (c.entityType === 'INDIVIDUAL' ? '個人事業主' : 'フリーランス');
        const statusBadge = c.status === 'ACTIVE' ? '<span class="badge bg-success">有効</span>' :
            (c.status === 'SUSPENDED' ? '<span class="badge bg-danger">取引停止</span>' : '<span class="badge bg-secondary">停止</span>');

        const complianceLabel = !c.complianceApplicability ? '<span class="badge bg-warning text-dark">未確認</span>' :
            (c.complianceApplicability === 'FREELANCE_ACT' ? '<span class="badge bg-info">フリーランス法</span>' : '<span class="badge bg-primary">取適法</span>');

        const row = `
            <tr class="border-secondary">
                <td>${c.id}</td>
                <td>
                    <a href="/bp-company/${c.id}" class="text-info fw-bold text-decoration-none">${escapeHtml(c.legalName)}</a>
                    <div class="text-muted small">${escapeHtml(c.nameKana || '')}</div>
                </td>
                <td><span class="badge bg-outline-light border">${entityLabel}</span></td>
                <td class="small">
                    <div>法: ${escapeHtml(c.corporateNumber || '-')}</div>
                    <div>番: ${escapeHtml(c.invoiceRegistrationNumber || '-')}</div>
                </td>
                <td>${complianceLabel}</td>
                <td><i class="bi bi-star-fill text-warning me-1"></i>${c.rating || 0}</td>
                <td>${statusBadge}</td>
                <td class="text-end">
                    <a href="/bp-company/${c.id}" class="btn btn-outline-info btn-sm"><i class="bi bi-eye"></i> 詳細</a>
                </td>
            </tr>
        `;
        tbody.append(row);
    });
}

function renderPagination(pageData) {
    $('#totalCountInfo').text(`全 ${pageData.total} 件 (${pageData.current} / ${pageData.pages} ページ)`);
    const ul = $('#pagination');
    ul.empty();

    if (pageData.pages <= 1) return;

    for (let i = 1; i <= pageData.pages; i++) {
        const active = i === pageData.current ? 'active' : '';
        ul.append(`<li class="page-item ${active}"><a class="page-link bg-dark border-secondary text-light" href="#" onclick="loadBpCompanies(${i}); return false;">${i}</a></li>`);
    }
}

function openBpCompanyModal() {
    $('#bpCompanyForm')[0].reset();
    $('#bpCompanyIdHidden').val('');
    $('#bpCompanyModalTitle').text('新規BP登録');
    new bootstrap.Modal('#bpCompanyModal').show();
}

function saveBpCompany() {
    const legalName = $('#formLegalName').val();
    if (!legalName) {
        if (window.Toast) Toast.show('正式名称を入力してください', 'warning');
        return;
    }

    const data = {
        legalName: legalName,
        nameKana: $('#formNameKana').val(),
        entityType: $('#formEntityType').val(),
        corporateNumber: $('#formCorporateNumber').val(),
        invoiceRegistrationNumber: $('#formInvoiceRegistrationNumber').val(),
        representative: $('#formRepresentative').val(),
        address: $('#formAddress').val(),
        status: 'ACTIVE'
    };

    $.ajax({
        url: '/api/bp-companies',
        type: 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        headers: { 'X-XSRF-TOKEN': getCsrfToken() },
        success: function (res) {
            if (res.code === 200) {
                bootstrap.Modal.getInstance(document.getElementById('bpCompanyModal')).hide();
                if (window.Toast) Toast.show('BP会社を登録しました', 'success');
                loadBpCompanies(1);
            }
        }
    });
}

function loadBpCompanyDetail(id) {
    $.ajax({
        url: `/api/bp-companies/${id}`,
        type: 'GET',
        success: function (res) {
            if (res.code === 200) {
                const c = res.data;
                $('#headerLegalName').text(c.legalName);
                $('#headerEntityType').text(c.entityType);
                $('#headerStatus').text(c.status);
                $('#detailLegalName').text(c.legalName);
                $('#detailNameKana').text(c.nameKana || '-');
                $('#detailCorporateNumber').text(c.corporateNumber || '-');
                $('#detailInvoiceRegistrationNumber').text(c.invoiceRegistrationNumber || '-');
                $('#detailRepresentative').text(c.representative || '-');
                $('#detailAddress').text(c.address || '-');
                $('#complianceApplicabilitySelect').val(c.complianceApplicability || '');
                $('#complianceNote').val(c.applicabilityNote || '');
            }
        }
    });
}

function loadBankAccounts() {
    const id = $('#bpCompanyId').val();
    if (!id) return;

    $.ajax({
        url: `/api/bp-companies/${id}/bank-accounts`,
        type: 'GET',
        success: function (res) {
            if (res.code === 200) {
                const tbody = $('#bankTableBody');
                tbody.empty();
                if (res.data.length === 0) {
                    tbody.append('<tr><td colspan="6" class="text-center text-muted py-3">登録された口座はありません</td></tr>');
                    return;
                }
                res.data.forEach(b => {
                    tbody.append(`
                        <tr class="border-secondary">
                            <td>${escapeHtml(b.bankName || '')} ${escapeHtml(b.branchName || '')}</td>
                            <td>${escapeHtml(b.accountType || '')}</td>
                            <td>${escapeHtml(b.accountHolder || '')}</td>
                            <td class="fw-bold text-info">${escapeHtml(b.maskedLabel)}</td>
                            <td>${b.validFrom || ''} ～ ${b.validTo || '無期限'}</td>
                            <td><span class="badge bg-success">${b.approvalStatus}</span></td>
                        </tr>
                    `);
                });
            }
        }
    });
}

function saveComplianceApplicability() {
    const id = $('#bpCompanyId').val();
    const data = {
        applicability: $('#complianceApplicabilitySelect').val(),
        note: $('#complianceNote').val()
    };

    $.ajax({
        url: `/api/bp-companies/${id}/compliance-applicability`,
        type: 'PUT',
        contentType: 'application/json',
        data: JSON.stringify(data),
        headers: { 'X-XSRF-TOKEN': getCsrfToken() },
        success: function (res) {
            if (res.code === 200) {
                if (window.Toast) Toast.show('法適用判定を更新しました', 'success');
            }
        }
    });
}

function resetSearch() {
    $('#searchForm')[0].reset();
    loadBpCompanies(1);
}

function getCsrfToken() {
    const match = document.cookie.match(new RegExp('(^| )XSRF-TOKEN=([^;]+)'));
    return match ? match[2] : '';
}

function escapeHtml(str) {
    if (!str) return '';
    return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
