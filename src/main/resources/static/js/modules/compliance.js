// コンプライアンス（リスク項目）一覧（labor-compliance-check / FR-10）
$(document).ready(function() {
    loadComplianceFindings();
});

function loadComplianceFindings() {
    $.ajax({
        url: '/api/compliance/findings',
        method: 'GET',
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderCompliance(res.data);
            } else {
                Toast.error(res.message || SES.i18n.t('js.common.error_fetch'));
            }
        },
        error: function() {
            Toast.error(SES.i18n.t('js.common.error_network'));
            $('#complianceTableBody').html(`<tr><td colspan="5" class="text-center text-muted py-4">${SES.i18n.t('js.common.error_network')}</td></tr>`);
        }
    });
}

function renderCompliance(rows) {
    const $body = $('#complianceTableBody');
    if (!rows.length) {
        $body.html(`<tr><td colspan="5" class="text-center text-muted py-4">${SES.i18n.t('compliance.noRisk')}</td></tr>`);
        return;
    }
    let html = '';
    rows.forEach(function(r) {
        const badges = (r.findings || []).map(f =>
            `<span class="badge bg-warning text-dark me-1 mb-1" title="${SES.escapeHtml(f.message)}">${SES.escapeHtml(f.message)}</span>`
        ).join('');
        html += `<tr>
            <td>${SES.escapeHtml(r.contractNo || '')}</td>
            <td>${SES.escapeHtml(r.engineerName || '')}</td>
            <td>${SES.escapeHtml(r.projectName || '')}</td>
            <td>${SES.escapeHtml(r.contractType || '')}</td>
            <td>${badges}</td>
        </tr>`;
    });
    $body.html(html);
}
