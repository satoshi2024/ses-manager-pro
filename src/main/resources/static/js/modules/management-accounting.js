(function (window, $) {
    'use strict';
    function t(key, fallback) { return window.SES && SES.i18n ? SES.i18n.t(key) : fallback; }
    function yen(value) { return '¥' + Number(value || 0).toLocaleString('ja-JP'); }
    function loadManagementAccounting() {
        var month = $('#accountingMonth').val();
        $.get('/api/management-accounting/summary', {month: month}).done(function (res) {
            if (res.code !== 200) { showError(res.message); return; }
            var data = res.data || {};
            $('#accountingRevenue').text(yen(data.totalRevenue));
            $('#accountingGrossProfit').text(yen(data.totalGrossProfit));
            $('#accountingRevenueVariance').text(yen(data.revenueVariance));
            $('#accountingGrossProfitVariance').text(yen(data.grossProfitVariance));
            $('#accountingRows').html((data.rows || []).map(function (row) {
                return '<tr><td>' + escapeHtml(row.organizationName) + '</td><td class="text-end">' + yen(row.revenue) + '</td><td class="text-end">' + yen(row.cost) + '</td><td class="text-end">' + yen(row.grossProfit) + '</td><td class="text-end">' + yen(row.budgetRevenue) + '</td><td class="text-end">' + yen(row.revenueVariance) + '</td></tr>';
            }).join('') || '<tr><td colspan="6" class="text-center text-muted">—</td></tr>');
        }).fail(function (xhr) { showError(xhr.responseJSON && xhr.responseJSON.message || t('managementAccounting.loadFailed', '管理会計データの取得に失敗しました')); });
    }
    function exportManagementAccounting() {
        var month = $('#accountingMonth').val();
        $.get('/api/management-accounting/export', {month: month}).done(function (res) { if (res.code === 200) { Toast.success(t('managementAccounting.exported', '同じ組織scopeで出力データを取得しました')); } else { showError(res.message); } }).fail(function (xhr) { showError(xhr.responseJSON && xhr.responseJSON.message); });
    }
    function escapeHtml(value) { return $('<div>').text(value == null ? '' : value).html(); }
    function showError(text) { if (window.Toast && Toast.error) Toast.error(text || t('error.networkError', '通信に失敗しました')); }
    window.loadManagementAccounting = loadManagementAccounting;
    window.exportManagementAccounting = exportManagementAccounting;
    $(function () { $('#accountingMonth').val(new Date().toISOString().slice(0, 7)); loadManagementAccounting(); });
}(window, jQuery));
