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
            $('#scenario-allocations').removeClass('d-none');
            loadScenarioAllocations();
        } else {
            $('#scenario-manage').addClass('d-none');
            $('#scenario-allocations').addClass('d-none');
        }
    });

    $('#scenario-save-btn').on('click', function () {
        // baseDateは送らない（サーバーがtenantタイムゾーンの今日を既定にする。S12-R2-P2-02）
        const payload = {
            id: selectedId,
            name: $('#scenario-name').val(),
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

    // ---------------- 仮配置編集（S12-R1-P1-05） ----------------

    function loadScenarioAllocations() {
        if (selectedId == null) { return; }
        $.ajax({
            url: '/api/analytics/staffing-scenarios/' + selectedId + '/allocations',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200) { return; }
                const rows = res.data || [];
                const $tbody = $('#scenario-alloc-list').empty();
                if (rows.length === 0) {
                    $tbody.append('<tr><td colspan="5" class="text-muted">' + t('staffing.scenario.allocEmpty', '仮配置はありません') + '</td></tr>');
                }
                rows.forEach(function (a) {
                    const name = a.positionId
                        ? (a.positionNo || '') + ' ' + (a.roleName || '')
                        : t('staffing.timeline.internal', '社内/待機');
                    $tbody.append(
                        '<tr>' +
                        '  <td>' + SES.escapeHtml(a.engineerName || '—') + '</td>' +
                        '  <td>' + SES.escapeHtml(name) + '</td>' +
                        '  <td>' + (a.allocationPercent != null ? a.allocationPercent : '') + '</td>' +
                        '  <td>' + (a.startDate || '—') + ' 〜 ' + (a.endDate || '—') + '</td>' +
                        '  <td class="text-end">' +
                        '    <button type="button" class="btn btn-sm btn-outline-secondary py-0 px-1 saa-edit" data-id="' + a.id + '" data-engineer="' + a.engineerId + '" data-position="' + (a.positionId || '') + '" data-project="' + (a.projectId || '') + '" data-from="' + (a.startDate || '') + '" data-to="' + (a.endDate || '') + '" data-percent="' + (a.allocationPercent != null ? a.allocationPercent : 100) + '" title="' + t('staffing.timeline.edit', '編集') + '"><i class="bi bi-pencil"></i></button>' +
                        '    <button type="button" class="btn btn-sm btn-outline-danger py-0 px-1 saa-delete" data-id="' + a.id + '" title="' + t('staffing.timeline.discard', '削除') + '"><i class="bi bi-trash"></i></button>' +
                        '  </td>' +
                        '</tr>'
                    );
                });
            }
        });
    }

    function openScenarioAllocationModal(alloc) {
        $('#saa-id').val(alloc ? alloc.id : '');
        $('#saa-from').val(alloc ? alloc.startDate : '');
        $('#saa-to').val(alloc ? alloc.endDate : '');
        $('#saa-percent').val(alloc ? alloc.allocationPercent : 100);
        $('#saa-projectId').val('');
        $('#saa-positionId').empty().append('<option value="">—</option>');
        loadScenarioEngineerOptions(alloc ? alloc.engineerId : '');
        loadScenarioProjectOptions(alloc ? alloc.projectId : '', alloc ? alloc.positionId : '');
        bootstrap.Modal.getOrCreateInstance(document.getElementById('scenarioAllocationModal')).show();
    }

    function loadScenarioProjectOptions(selectedProjectId, selectedPositionId) {
        $.ajax({
            url: '/api/projects/options',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200 || !res.data) { return; }
                const $select = $('#saa-projectId').empty().append('<option value="">—</option>');
                (res.data || []).forEach(function (p) {
                    $('<option>').val(p.id).text(p.name || p.id).appendTo($select);
                });
                if (selectedProjectId) {
                    $select.val(String(selectedProjectId));
                    loadScenarioPositionOptions(selectedProjectId, selectedPositionId);
                }
            }
        });
    }

    function loadScenarioPositionOptions(projectId, selectedPositionId) {
        if (!projectId) {
            $('#saa-positionId').empty().append('<option value="">—</option>');
            return;
        }
        $.ajax({
            url: '/api/projects/' + projectId + '/positions',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200) { return; }
                const $select = $('#saa-positionId').empty().append('<option value="">—</option>');
                (res.data || []).forEach(function (p) {
                    $('<option>').val(p.id).text(p.positionNo + ' ' + (p.roleName || '') + (p.status ? ' [' + p.status + ']' : '')).appendTo($select);
                });
                if (selectedPositionId) { $select.val(String(selectedPositionId)); }
            }
        });
    }

    function loadScenarioEngineerOptions(selectedEngineerId) {
        $.ajax({
            url: '/api/engineers/options',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200 || !res.data) { return; }
                const $select = $('#saa-engineerId').empty();
                (res.data || []).forEach(function (e) {
                    $('<option>').val(e.id).text(e.name || e.id).appendTo($select);
                });
                if (selectedEngineerId) { $select.val(String(selectedEngineerId)); }
            }
        });
    }

    function saveScenarioAllocation() {
        // 二重クリック・再入防止（S12-R2-P2-08）
        if (window.__saaSaving) { return; }
        window.__saaSaving = true;
        $('#scenario-alloc-save').prop('disabled', true);
        const engineerId = $('#saa-engineerId').val();
        const from = $('#saa-from').val();
        const to = $('#saa-to').val();
        if (!engineerId || !from || !to) {
            window.__saaSaving = false;
            $('#scenario-alloc-save').prop('disabled', false);
            showError(t('staffing.scenario.allocRequired', '要員と対象日は必須です'));
            return;
        }
        if (to < from) {
            showError(t('error.staffing.invalidPeriod', '終了日は開始日以降を指定してください'));
            return;
        }
        // 対象日を日単位で列挙（UTC変換で日付がずれないよう入力値をそのまま使う。上限は24か月）
        const dates = [];
        const d = new Date(from + 'T12:00:00');
        const last = new Date(to + 'T12:00:00');
        const fmt = function (day) {
            return day.getFullYear() + '-' + String(day.getMonth() + 1).padStart(2, '0')
                + '-' + String(day.getDate()).padStart(2, '0');
        };
        while (d <= last && dates.length <= 4000) {
            dates.push(fmt(d));
            d.setDate(d.getDate() + 1);
        }
        const payload = {
            id: $('#saa-id').val() ? Number($('#saa-id').val()) : null,
            engineerId: Number(engineerId),
            positionId: $('#saa-positionId').val() ? Number($('#saa-positionId').val()) : null,
            percent: Number($('#saa-percent').val()),
            dates: JSON.stringify(dates)
        };
        $.ajax({
            url: '/api/analytics/staffing-scenarios/' + selectedId + '/allocations',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            success: function (res) {
                window.__saaSaving = false;
                $('#scenario-alloc-save').prop('disabled', false);
                if (res.code !== 200) {
                    showError(res.message || t('error.systemError', 'エラーが発生しました'));
                    return;
                }
                bootstrap.Modal.getInstance(document.getElementById('scenarioAllocationModal')).hide();
                loadScenarioAllocations();
            },
            error: function (xhr) {
                window.__saaSaving = false;
                $('#scenario-alloc-save').prop('disabled', false);
                showError((xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || t('error.systemError', 'エラーが発生しました'));
            }
        });
    }

    function deleteScenarioAllocation(allocationId) {
        Swal.fire({
            title: t('staffing.scenario.allocDeleteConfirm', '仮配置を削除しますか'),
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: t('common.ok', 'OK'),
            cancelButtonText: t('common.cancel', 'キャンセル')
        }).then(function (result) {
            if (!result.isConfirmed) { return; }
            $.ajax({
                url: '/api/analytics/staffing-scenarios/' + selectedId + '/allocations/' + allocationId,
                method: 'DELETE',
                success: function (res) {
                    if (res.code !== 200) {
                        showError(res.message || t('error.systemError', 'エラーが発生しました'));
                        return;
                    }
                    loadScenarioAllocations();
                }
            });
        });
    }

    $(document).on('click', '#scenario-alloc-add', function () {
        openScenarioAllocationModal(null);
    });
    $(document).on('change', '#saa-projectId', function () {
        loadScenarioPositionOptions($(this).val(), '');
    });
    $(document).on('click', '.saa-edit', function () {
        openScenarioAllocationModal({
            id: Number($(this).data('id')),
            engineerId: $(this).data('engineer'),
            positionId: $(this).data('position'),
            projectId: $(this).data('project'),
            startDate: $(this).data('from'),
            endDate: $(this).data('to'),
            allocationPercent: $(this).data('percent')
        });
    });
    $(document).on('click', '.saa-delete', function () {
        deleteScenarioAllocation(Number($(this).data('id')));
    });
    $('#scenario-alloc-save').on('click', saveScenarioAllocation);

    loadScenarios();
});
