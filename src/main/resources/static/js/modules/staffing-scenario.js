// ============================================================
// staffing-capacity-planning（S12）: シナリオ比較（T079 B2）
// シナリオ操作は実データ（t_allocation_plan/契約/提案）を一切変更しない（R3.3）。
// ============================================================
$(function () {
    'use strict';

    const t = function (key, fallback) {
        return (window.SES && SES.i18n && SES.i18n.t) ? SES.i18n.t(key) : fallback;
    };

    let scenarios = [];
    let selectedId = null;

    function loadScenarios() {
        $.ajax({
            url: '/api/analytics/staffing-scenarios',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200) { return; }
                scenarios = res.data || [];
                const select = $('#scenario-select').empty();
                scenarios.forEach(function (s) {
                    $('<option>').val(s.id).text(s.name + (s.sharedFlag === 1 ? ' (共有)' : '')).appendTo(select);
                });
            }
        });
    }

    function showError(message) {
        $('#scenario-error').text(message).removeClass('d-none');
    }

    function compare() {
        const ids = $('#scenario-select').val();
        if (!ids || ids.length === 0) {
            showError(t('staffing.scenario.selectRequired', 'シナリオを選択してください'));
            return;
        }
        $('#scenario-error').addClass('d-none');
        $.ajax({
            url: '/api/analytics/staffing-scenarios/compare?scenarioIds=' + ids.join(','),
            method: 'GET',
            success: function (res) {
                if (res.code !== 200) {
                    showError(res.message || 'エラー');
                    return;
                }
                renderCompare(res.data || []);
            },
            error: function (xhr) {
                showError((xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || 'エラー');
            }
        });
    }

    function renderCompare(rows) {
        if (rows.length === 0) { return; }
        const months = [];
        const byScenario = {};
        rows.forEach(function (row) {
            if (months.indexOf(row.month) < 0) { months.push(row.month); }
            if (!byScenario[row.scenarioId]) {
                byScenario[row.scenarioId] = { name: row.scenarioName, rows: [] };
            }
            byScenario[row.scenarioId].rows.push(row);
        });
        let html = '<thead><tr><th>' + t('staffing.scenario.name', 'シナリオ') + '</th>';
        months.forEach(function (m) { html += '<th>' + m + '</th>'; });
        html += '</tr></thead><tbody>';
        Object.keys(byScenario).forEach(function (scenarioId) {
            const s = byScenario[scenarioId];
            html += '<tr><td class="scenario-name">' + SES.escapeHtml(s.name) + '</td>';
            months.forEach(function (m) {
                const row = s.rows.find(function (r) { return r.month === m; });
                html += '<td>' + (row ? fmt(row) : '—') + '</td>';
            });
            html += '</tr>';
        });
        html += '</tbody>';
        $('#scenario-compare-table').html(html);
        $('#scenario-compare-result').removeClass('d-none');
    }

    function fmt(row) {
        let text = '';
        text += t('staffing.scenario.supplyFte', '供給FTE') + ' ' + Number(row.supplyFte).toFixed(2) + '<br>';
        text += t('staffing.scenario.utilization', '稼働率') + ' ' + Number(row.utilizationRate).toFixed(1) + '%<br>';
        text += t('staffing.scenario.engineers', '要員') + ' ' + row.engineerCount;
        if (row.grossProfit != null) {
            text += '<br>' + t('staffing.scenario.grossProfit', '粗利') + ' ¥' + Number(row.grossProfit).toLocaleString();
        }
        return text;
    }

    // ---- 管理 ----
    $('#scenario-add').on('click', function () {
        selectedId = null;
        $('#scenario-manage').removeClass('d-none');
        $('#scenario-name').val('');
        $('#scenario-shared').prop('checked', false);
    });

    $('#scenario-select').on('change', function () {
        const ids = $(this).val() || [];
        selectedId = ids.length === 1 ? Number(ids[0]) : null;
        if (selectedId != null) {
            const s = scenarios.find(function (x) { return x.id === selectedId; });
            $('#scenario-manage').removeClass('d-none');
            $('#scenario-name').val(s ? s.name : '');
            $('#scenario-shared').prop('checked', !!(s && s.sharedFlag === 1));
        } else {
            $('#scenario-manage').addClass('d-none');
        }
    });

    $('#scenario-save-btn').on('click', function () {
        const payload = {
            id: selectedId,
            name: $('#scenario-name').val(),
            baseDate: new Date().toISOString().slice(0, 10),
            sharedFlag: $('#scenario-shared').is(':checked') ? 1 : 0
        };
        const method = selectedId ? 'PUT' : 'POST';
        const url = selectedId
            ? '/api/analytics/staffing-scenarios/' + selectedId
            : '/api/analytics/staffing-scenarios';
        $.ajax({
            url: url,
            method: method,
            contentType: 'application/json',
            data: JSON.stringify(payload),
            success: function (res) {
                if (res.code !== 200) {
                    showError(res.message || 'エラー');
                    return;
                }
                loadScenarios();
                if (!selectedId && res.data) {
                    $('#scenario-select').val([String(res.data.id)]);
                    selectedId = res.data.id;
                }
            },
            error: function (xhr) {
                showError((xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || 'エラー');
            }
        });
    });

    $('#scenario-delete-btn').on('click', function () {
        if (!selectedId) { return; }
        Swal.fire({
            title: t('staffing.scenario.deleteConfirm', 'シナリオを削除しますか'),
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: t('common.ok', 'OK'),
            cancelButtonText: t('common.cancel', 'キャンセル')
        }).then(function (result) {
            if (!result.isConfirmed) { return; }
            $.ajax({
                url: '/api/analytics/staffing-scenarios/' + selectedId,
                method: 'DELETE',
                success: function (res) {
                    if (res.code !== 200) {
                        showError(res.message || 'エラー');
                        return;
                    }
                    selectedId = null;
                    $('#scenario-manage').addClass('d-none');
                    loadScenarios();
                }
            });
        });
    });

    $('#scenario-compare-btn').on('click', compare);
    loadScenarios();
});
