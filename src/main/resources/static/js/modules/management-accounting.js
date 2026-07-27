(function (window, $) {
    'use strict';
    function t(key, fallback) { return window.SES && SES.i18n ? SES.i18n.t(key) : fallback; }
    function yen(value) { return '¥' + Number(value || 0).toLocaleString('ja-JP'); }
    function loadManagementAccounting() {
        var month = $('#accountingMonth').val();
        var params = {month: month, organizationId: $('#accountingOrganizationId').val() || null, costCenterId: $('#accountingCostCenterId').val() || null, customerId: $('#accountingCustomerId').val() || null, projectId: $('#accountingProjectId').val() || null, salesUserId: $('#accountingSalesUserId').val() || null};
        $.get('/api/management-accounting/summary', params).done(function (res) {
            if (res.code !== 200) { showError(res.message); return; }
            var data = res.data || {};
            $('#accountingRevenue').text(yen(data.totalRevenue));
            $('#accountingGrossProfit').text(yen(data.totalGrossProfit));
            $('#accountingRevenueVariance').text(yen(data.revenueVariance));
            $('#accountingGrossProfitVariance').text(yen(data.grossProfitVariance));
            $('#accountingRows').html((data.rows || []).map(function (row) {
                return '<tr><td>' + escapeHtml(row.organizationName) + '</td><td>' + escapeHtml(row.costCenterId || '—') + '</td><td>' + escapeHtml(row.customerId || '—') + '</td><td class="text-end">' + yen(row.revenue) + '</td><td class="text-end">' + yen(row.cost) + '</td><td class="text-end">' + yen(row.grossProfit) + '</td><td class="text-end">' + yen(row.waitCost) + '</td><td class="text-end">' + yen(row.budgetRevenue) + '</td><td class="text-end">' + yen(row.revenueVariance) + '</td></tr>';
            }).join('') || '<tr><td colspan="9" class="text-center text-muted">—</td></tr>');
        }).fail(function (xhr) { showError(xhr.responseJSON && xhr.responseJSON.message || t('managementAccounting.loadFailed', '管理会計データの取得に失敗しました')); });
    }
    function exportManagementAccounting() {
        var month = $('#accountingMonth').val();
        var query = $.param({month: month, organizationId: $('#accountingOrganizationId').val() || null, costCenterId: $('#accountingCostCenterId').val() || null, customerId: $('#accountingCustomerId').val() || null, projectId: $('#accountingProjectId').val() || null, salesUserId: $('#accountingSalesUserId').val() || null});
        window.location.href = '/api/management-accounting/export?' + query;
    }
    function escapeHtml(value) { return $('<div>').text(value == null ? '' : value).html(); }
    function showError(text) { if (window.Toast && Toast.error) Toast.error(text || t('error.networkError', '通信に失敗しました')); }
    window.loadManagementAccounting = loadManagementAccounting;
    window.exportManagementAccounting = exportManagementAccounting;
    $(function () { var now = new Date(); $('#accountingMonth').val(now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0')); loadManagementAccounting(); });
}(window, jQuery));
