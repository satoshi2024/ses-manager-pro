// ============================================================
// staffing-capacity-planning（S12）: 需給ヒートマップ（T078 B1）
// 全社合計と内訳合計の一致はserver aggregateで保証。不足セルはクリックでdrilldown。
// ============================================================
$(function () {
    'use strict';

    const t = function (key, fallback) {
        return (window.SES && SES.i18n && SES.i18n.t) ? SES.i18n.t(key) : fallback;
    };

    let currentData = null;
    let currentDimension = 'role';

    // 既定: 当月
    const today = new Date();
    const thisMonth = today.getFullYear() + '-' + String(today.getMonth() + 1).padStart(2, '0');
    $('#hm-from').val(thisMonth);
    $('#hm-to').val(thisMonth);

    function load() {
        const from = $('#hm-from').val();
        const to = $('#hm-to').val();
        const params = new URLSearchParams();
        if (from) { params.set('from', from); }
        if (to) { params.set('to', to); }
        $.ajax({
            url: '/api/analytics/staffing-heatmap?' + params.toString(),
            method: 'GET',
            success: function (res) {
                if (res.code !== 200) {
                    showError(res.message || 'エラー');
                    return;
                }
                currentData = res.data || {};
                render();
            },
            error: function (xhr) {
                showError((xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || 'エラー');
            }
        });
    }

    function showError(message) {
        $('#heatmap-error').text(message).removeClass('d-none');
    }

    function render() {
        $('#heatmap-error').addClass('d-none');
        $('#heatmap-asof').text(t('staffing.heatmap.asOf', '基準日') + ': ' + (currentData.asOf || ''));
        renderDimension('role', currentData.role || []);
        renderDimension('skill', currentData.skill || []);
        renderDimension('location', currentData.location || []);
    }

    function renderDimension(dimension, rows) {
        const $table = $('#' + dimension + '-table');
        const months = monthsOf(rows);
        let html = '<thead><tr><th>' + t('staffing.heatmap.group', 'グループ') + '</th>';
        months.forEach(function (m) {
            html += '<th>' + m + '</th>';
        });
        html += '</tr></thead><tbody>';
        rows.forEach(function (row) {
            html += '<tr><td class="group">' + SES.escapeHtml(row.group) + '</td>';
            row.cells.forEach(function (cell) {
                html += cellHtml(cell, dimension);
            });
            html += '</tr>';
        });
        // 全社合計行
        html += '<tr class="total-row"><td class="group">' + t('staffing.heatmap.total', '全社合計') + '</td>';
        (currentData.totals || []).forEach(function (cell) {
            html += '<td>' +
                'D ' + fmt(cell.demandFte) + '<br>' +
                'S ' + fmt(cell.supplyFte) + '<br>' +
                '差 ' + fmt(cell.shortfall) + ' / 余 ' + fmt(cell.surplus) +
                (cell.benchCost != null ? '<br>BC ¥' + fmtMoney(cell.benchCost) : '') +
                '</td>';
        });
        html += '</tr></tbody>';
        $table.html(html);
    }

    function cellHtml(cell, dimension) {
        let html = '<td>';
        html += 'D ' + fmt(cell.demandFte) + '<br>';
        html += 'S ' + fmt(cell.supplyFte) + '<br>';
        if (cell.shortfall && Number(cell.shortfall) > 0) {
            const group = groupOf(cell, dimension);
            html += '<span class="cell-shortfall" data-dim="' + dimension + '" data-month="' + cell.month
                + '" data-group="' + encodeURIComponent(group) + '">不足 ' + fmt(cell.shortfall) + '</span>';
        } else {
            html += '差 ' + fmt(cell.shortfall);
        }
        html += ' / 余 ' + fmt(cell.surplus);
        if (cell.benchCost != null) {
            html += '<br><span class="text-muted">BC ¥' + fmtMoney(cell.benchCost) + '</span>';
        }
        return html + '</td>';
    }

    function groupOf(cell, dimension) {
        const rows = currentData[dimension] || [];
        for (let i = 0; i < rows.length; i++) {
            if (rows[i].cells.some(function (c) { return c.month === cell.month && c === cell; })) {
                return rows[i].group;
            }
        }
        return '';
    }

    function monthsOf(rows) {
        const seen = [];
        (rows || []).forEach(function (row) {
            (row.cells || []).forEach(function (cell) {
                if (seen.indexOf(cell.month) < 0) { seen.push(cell.month); }
            });
        });
        return seen;
    }

    function fmt(v) {
        return v == null ? '—' : Number(v).toFixed(1);
    }

    function fmtMoney(v) {
        return Number(v).toLocaleString();
    }

    // drilldown
    $(document).on('click', '.cell-shortfall', function () {
        const dimension = $(this).data('dim');
        const month = $(this).data('month');
        const group = decodeURIComponent($(this).data('group'));
        $('#hd-title').text(month + ' / ' + group);
        $('#hd-positions').html('<tr><td colspan="6" class="text-muted">' + t('common.loading', '読み込み中...') + '</td></tr>');
        $('#hd-engineers').html('<tr><td colspan="4" class="text-muted">' + t('common.loading', '読み込み中...') + '</td></tr>');
        bootstrap.Modal.getOrCreateInstance(document.getElementById('heatmapDrilldownModal')).show();
        $.ajax({
            url: '/api/analytics/staffing-heatmap/drilldown?month=' + encodeURIComponent(month)
                + '&dimension=' + encodeURIComponent(dimension)
                + '&group=' + encodeURIComponent(group),
            method: 'GET',
            success: function (res) {
                if (res.code !== 200 || !res.data) { return; }
                const data = res.data;
                const pos = (data.positions || []).map(function (p) {
                    const price = p.unitPriceMin != null || p.unitPriceMax != null
                        ? '¥' + (p.unitPriceMin != null ? fmtMoney(p.unitPriceMin) : '') + '〜' + (p.unitPriceMax != null ? fmtMoney(p.unitPriceMax) : '')
                        : '—';
                    return '<tr><td>' + SES.escapeHtml(p.positionNo || '') + '</td>'
                        + '<td>' + SES.escapeHtml(p.roleName || '') + '</td>'
                        + '<td>' + SES.escapeHtml(p.projectName || '') + '</td>'
                        + '<td>' + (p.requiredCount != null ? p.requiredCount : '') + '</td>'
                        + '<td>' + SES.escapeHtml(p.status || '') + '</td>'
                        + '<td>' + price + '</td></tr>';
                }).join('');
                $('#hd-positions').html(pos || '<tr><td colspan="6" class="text-muted">' + t('staffing.heatmap.drilldown.empty', '該当なし') + '</td></tr>');
                const eng = (data.engineers || []).map(function (e) {
                    return '<tr><td>' + SES.escapeHtml(e.engineerName || '') + '</td>'
                        + '<td>' + SES.escapeHtml(e.primarySkill || '') + '</td>'
                        + '<td>' + fmt(e.supplyFte) + '</td>'
                        + '<td>' + (e.unitPrice != null ? '¥' + fmtMoney(e.unitPrice) : '—') + '</td></tr>';
                }).join('');
                $('#hd-engineers').html(eng || '<tr><td colspan="4" class="text-muted">' + t('staffing.heatmap.drilldown.empty', '該当なし') + '</td></tr>');
            }
        });
    });

    $('#hm-reload').on('click', load);
    load();
});
