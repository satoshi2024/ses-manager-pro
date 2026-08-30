/**
 * 資産棚卸し管理モジュール (asset-inventory.js)
 */
$(function () {
    'use strict';

    let currentRunId = null;
    const startModal = new bootstrap.Modal(document.getElementById('startModal'));
    const checkModal = new bootstrap.Modal(document.getElementById('checkModal'));

    loadRuns();

    $('#btnOpenStartModal').on('click', function () {
        $('#startForm')[0].reset();
        $('#startDate').val(new Date().toISOString().substring(0, 10));
        startModal.show();
    });

    $('#startForm').on('submit', function (e) {
        e.preventDefault();
        const payload = {
            inventoryCode: $('#startCode').val().trim(),
            title: $('#startTitle').val().trim(),
            targetDate: $('#startDate').val()
        };

        $.ajax({
            url: '/api/asset-inventory/runs',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            headers: SES.csrf.header(),
            success: function (res) {
                if (res.code === 200) {
                    startModal.hide();
                    SES.toast.success('棚卸し計画を開始しました。');
                    loadRuns();
                    openRunDetail(res.data.id, res.data.title, res.data.status);
                } else {
                    SES.toast.error(res.message || '開始に失敗しました。');
                }
            },
            error: function (xhr) {
                SES.toast.error(xhr.responseJSON ? xhr.responseJSON.message : 'エラーが発生しました。');
            }
        });
    });

    $('#checkForm').on('submit', function (e) {
        e.preventDefault();
        const itemId = $('#checkItemId').val();
        const payload = {
            observedStatus: $('#checkObservedStatus').val(),
            observedLocation: $('#checkObservedLocation').val().trim(),
            discrepancyType: $('#checkDiscrepancyType').val(),
            discrepancyReason: $('#checkDiscrepancyReason').val().trim(),
            resolutionAction: $('#checkResolutionAction').val().trim()
        };

        $.ajax({
            url: `/api/asset-inventory/items/${itemId}/check`,
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            headers: SES.csrf.header(),
            success: function (res) {
                if (res.code === 200) {
                    checkModal.hide();
                    SES.toast.success('照合結果を保存しました。');
                    loadRunItems(currentRunId);
                } else {
                    SES.toast.error(res.message || '保存に失敗しました。');
                }
            },
            error: function (xhr) {
                SES.toast.error(xhr.responseJSON ? xhr.responseJSON.message : 'エラーが発生しました。');
            }
        });
    });

    $('#btnCompleteRun').on('click', function () {
        if (!currentRunId) return;

        Swal.fire({
            title: '棚卸しを完了・確定しますか？',
            text: '差異件数を集計し、スナップショットを確定します。',
            icon: 'question',
            showCancelButton: true,
            confirmButtonText: '完了確定',
            cancelButtonText: 'キャンセル'
        }).then((result) => {
            if (result.isConfirmed) {
                $.ajax({
                    url: `/api/asset-inventory/runs/${currentRunId}/complete`,
                    method: 'POST',
                    headers: SES.csrf.header(),
                    success: function (res) {
                        if (res.code === 200) {
                            SES.toast.success('棚卸しが完了・確定されました。');
                            loadRuns();
                            openRunDetail(currentRunId, res.data.title, res.data.status);
                        } else {
                            SES.toast.error(res.message || '完了確定に失敗しました。');
                        }
                    }
                });
            }
        });
    });

    $('#btnCloseDetail').on('click', function () {
        $('#detailCard').addClass('d-none');
        currentRunId = null;
    });

    function loadRuns() {
        $.ajax({
            url: '/api/asset-inventory/runs',
            method: 'GET',
            success: function (res) {
                if (res.code === 200) {
                    renderRunsTable(res.data.records);
                }
            }
        });
    }

    function renderRunsTable(records) {
        const tbody = $('#inventoryRunTableBody');
        tbody.empty();

        if (!records || records.length === 0) {
            tbody.append('<tr><td colspan="10" class="text-center py-4 text-muted">棚卸し実施履歴がありません</td></tr>');
            return;
        }

        records.forEach(function (r) {
            const statusBadge = r.status === 'COMPLETED' ? '<span class="badge bg-success">COMPLETED</span>' : '<span class="badge bg-primary">IN_PROGRESS</span>';
            const tr = $(`
                <tr>
                    <td><strong class="text-info">${escapeHtml(r.inventoryCode)}</strong></td>
                    <td class="fw-bold">${escapeHtml(r.title)}</td>
                    <td>${r.targetDate || '-'}</td>
                    <td>${statusBadge}</td>
                    <td><span class="badge bg-dark">${r.totalAssets}</span></td>
                    <td class="text-success fw-bold">${r.matchedCount}</td>
                    <td class="text-warning fw-bold">${r.discrepancyCount}</td>
                    <td class="text-danger fw-bold">${r.missingCount}</td>
                    <td><small class="text-muted">${r.completedAt ? r.completedAt.replace('T', ' ').substring(0, 16) : '-'}</small></td>
                    <td class="text-end">
                        <button class="btn btn-outline-primary btn-sm btn-open-detail" data-id="${r.id}" data-title="${escapeHtml(r.title)}" data-status="${r.status}">
                            <i class="bi bi-eye me-1"></i>照合ワークスペース
                        </button>
                    </td>
                </tr>
            `);
            tbody.append(tr);
        });

        $('.btn-open-detail').on('click', function () {
            const id = $(this).data('id');
            const title = $(this).data('title');
            const status = $(this).data('status');
            openRunDetail(id, title, status);
        });
    }

    function openRunDetail(id, title, status) {
        currentRunId = id;
        $('#detailTitle').text(`棚卸し実地照合 [${title}]`);
        $('#detailStatus').text(status).removeClass('bg-primary bg-success').addClass(status === 'COMPLETED' ? 'bg-success' : 'bg-primary');
        $('#btnCompleteRun').prop('disabled', status === 'COMPLETED');
        $('#detailCard').removeClass('d-none');
        loadRunItems(id);
    }

    function loadRunItems(runId) {
        $.ajax({
            url: `/api/asset-inventory/runs/${runId}/items`,
            method: 'GET',
            success: function (res) {
                if (res.code === 200) {
                    renderItemsTable(res.data);
                }
            }
        });
    }

    function renderItemsTable(items) {
        const tbody = $('#inventoryItemTableBody');
        tbody.empty();

        if (!items || items.length === 0) {
            tbody.append('<tr><td colspan="9" class="text-center py-4 text-muted">棚卸し明細がありません</td></tr>');
            return;
        }

        items.forEach(function (it) {
            const discBadge = getDiscrepancyBadge(it.discrepancyType);
            const tr = $(`
                <tr>
                    <td><strong>AST #${it.assetId}</strong></td>
                    <td>${escapeHtml(it.expectedStatus || '-')}</td>
                    <td>${escapeHtml(it.expectedLocation || '-')}</td>
                    <td>${escapeHtml(it.observedStatus || '-')}</td>
                    <td>${escapeHtml(it.observedLocation || '-')}</td>
                    <td>${discBadge}</td>
                    <td>
                        <small>
                            ${it.discrepancyReason ? `<div><span class="text-warning">理由:</span> ${escapeHtml(it.discrepancyReason)}</div>` : ''}
                            ${it.resolutionAction ? `<div><span class="text-info">措置:</span> ${escapeHtml(it.resolutionAction)}</div>` : ''}
                        </small>
                    </td>
                    <td><small class="text-muted">${it.checkedAt ? it.checkedAt.replace('T', ' ').substring(0, 16) : '未確認'}</small></td>
                    <td class="text-end">
                        <button class="btn btn-outline-info btn-sm btn-item-check" data-id="${it.id}" data-type="${it.discrepancyType}" data-obs-status="${it.observedStatus || ''}" data-obs-loc="${it.observedLocation || ''}" data-reason="${escapeHtml(it.discrepancyReason || '')}" data-action="${escapeHtml(it.resolutionAction || '')}">
                            確認入力
                        </button>
                    </td>
                </tr>
            `);
            tbody.append(tr);
        });

        $('.btn-item-check').on('click', function () {
            $('#checkItemId').val($(this).data('id'));
            $('#checkObservedStatus').val($(this).data('obs-status') || 'IN_STOCK');
            $('#checkObservedLocation').val($(this).data('obs-loc'));
            $('#checkDiscrepancyType').val($(this).data('type') === 'UNCHECKED' ? 'MATCH' : $(this).data('type'));
            $('#checkDiscrepancyReason').val($(this).data('reason'));
            $('#checkResolutionAction').val($(this).data('action'));
            checkModal.show();
        });
    }

    function getDiscrepancyBadge(type) {
        switch (type) {
            case 'MATCH':
                return '<span class="badge bg-success">MATCH (一致)</span>';
            case 'DISCREPANCY':
                return '<span class="badge bg-warning text-dark">DISCREPANCY (差異あり)</span>';
            case 'MISSING':
                return '<span class="badge bg-danger">MISSING (所在不明)</span>';
            case 'UNREGISTERED':
                return '<span class="badge bg-info text-dark">UNREGISTERED (未登録)</span>';
            default:
                return '<span class="badge bg-secondary">UNCHECKED (未確認)</span>';
        }
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
    }
});
