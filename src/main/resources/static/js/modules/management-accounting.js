(function (window, $) {
    'use strict';

    function t(key, fallback) { return window.SES && SES.i18n && SES.i18n.t ? SES.i18n.t(key) : fallback; }

    /** DB・API・Javaとも金額は円。表示だけ桁区切りする（万円へ変換しない）。 */
    function yen(value) { return '¥' + Number(value || 0).toLocaleString('ja-JP'); }

    function params() {
        return {
            month: $('#accountingMonth').val(),
            organizationId: $('#accountingOrganizationId').val() || null,
            costCenterId: $('#accountingCostCenterId').val() || null,
            customerId: $('#accountingCustomerId').val() || null
        };
    }

    function loadOptions() {
        $.get('/api/autocomplete/organizations').done(function (res) {
            if (res.code !== 200) { return; }
            $('#accountingOrganizationId').html('<option value="">—</option>' + (res.data || []).map(function (item) {
                return '<option value="' + item.id + '">' + escapeHtml(item.code + ' ' + item.name) + '</option>';
            }).join(''));
        });
        $.get('/api/autocomplete/cost-centers').done(function (res) {
            if (res.code !== 200) { return; }
            $('#accountingCostCenterId').html('<option value="">—</option>' + (res.data || []).map(function (item) {
                return '<option value="' + item.id + '">' + escapeHtml(item.code + ' ' + item.name) + '</option>';
            }).join(''));
        });
    }

    function loadManagementAccounting() {
        $.get('/api/management-accounting/summary', params()).done(function (res) {
            if (res.code !== 200) { showError(res.message); return; }
            var data = res.data || {};
            $('#accountingRevenue').text(yen(data.totalRevenue));
            $('#accountingGrossProfit').text(yen(data.totalGrossProfit));
            $('#accountingRevenueVariance').text(yen(data.revenueVariance));
            $('#accountingGrossProfitVariance').text(yen(data.grossProfitVariance));

            // 予実行は「組織 × 原価部門」粒度。予算はこの粒度でしか存在しないため、
            // ここより細かい行に予算差を出してはいけない（内訳表には予算列を置かない）。
            $('#accountingRows').html((data.rows || []).map(function (row) {
                return '<tr><td>' + escapeHtml(row.organizationName) + '</td><td>' + escapeHtml(row.costCenterId || '—')
                    + '</td><td class="text-end">' + yen(row.revenue) + '</td><td class="text-end">' + yen(row.cost)
                    + '</td><td class="text-end">' + yen(row.grossProfit) + '</td><td class="text-end">' + yen(row.waitCost)
                    + '</td><td class="text-end">' + yen(row.budgetRevenue) + '</td><td class="text-end">' + variance(row.revenueVariance)
                    + '</td></tr>';
            }).join('') || '<tr><td colspan="8" class="text-center text-muted">—</td></tr>');

            $('#accountingDetailRows').html((data.details || []).map(function (row) {
                return '<tr><td>' + escapeHtml(row.organizationName) + '</td><td>' + escapeHtml(row.customerId || '—')
                    + '</td><td>' + escapeHtml(row.projectId || '—') + '</td><td>' + escapeHtml(row.salesUserId || '—')
                    + '</td><td class="text-end">' + yen(row.revenue) + '</td><td class="text-end">' + yen(row.cost)
                    + '</td><td class="text-end">' + yen(row.grossProfit) + '</td></tr>';
            }).join('') || '<tr><td colspan="7" class="text-center text-muted">—</td></tr>');
        }).fail(function (xhr) {
            showError((xhr.responseJSON && xhr.responseJSON.message)
                || t('managementAccounting.loadFailed', '管理会計データの取得に失敗しました'));
        });
    }

    /** 予算差は色だけで良し悪しを示さない。符号を必ず文字で添える。 */
    function variance(value) {
        var amount = Number(value || 0);
        var sign = amount > 0 ? '+' : '';
        var cls = amount < 0 ? 'text-warning' : 'text-light';
        return '<span class="' + cls + '">' + sign + yen(amount) + '</span>';
    }

    function exportManagementAccounting() {
        window.location.href = '/api/management-accounting/export?' + $.param(params());
    }

    function escapeHtml(value) { return $('<div>').text(value == null ? '' : value).html(); }
    function showError(text) { if (window.Toast && Toast.error) { Toast.error(text || t('error.networkError', '通信に失敗しました')); } }

    window.loadManagementAccounting = loadManagementAccounting;
    window.exportManagementAccounting = exportManagementAccounting;

    $(function () {
        var now = new Date();
        // 既定は前月。当月は月次締め前でsnapshotが無く、部門損益が見込みだけになるため。
        now.setDate(1);
        now.setMonth(now.getMonth() - 1);
        $('#accountingMonth').val(now.getFullYear() + '-' + String(now.getMonth() + 1).padStart(2, '0'));
        loadOptions();
        loadManagementAccounting();
    });
}(window, jQuery));
