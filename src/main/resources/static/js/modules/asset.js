/**
 * 資産台帳管理モジュール (asset.js)
 */
$(function () {
    'use strict';

    let currentPage = 1;
    const pageSize = 10;
    let assetModal = null;
    let assignModal = null;
    let returnModal = null;
    let historyModal = null;

    if (document.getElementById('assetModal')) {
        assetModal = new bootstrap.Modal(document.getElementById('assetModal'));
    }
    if (document.getElementById('assignModal')) {
        assignModal = new bootstrap.Modal(document.getElementById('assignModal'));
    }
    if (document.getElementById('returnModal')) {
        returnModal = new bootstrap.Modal(document.getElementById('returnModal'));
    }
    if (document.getElementById('historyModal')) {
        historyModal = new bootstrap.Modal(document.getElementById('historyModal'));
    }

    // 初期化
    loadAssets(1);

    // 検索イベント
    $('#searchForm').on('submit', function (e) {
        e.preventDefault();
        loadAssets(1);
    });

    $('#btnResetSearch').on('click', function () {
        $('#searchKeyword').val('');
        $('#searchCategory').val('');
        $('#searchStatus').val('');
        loadAssets(1);
    });

    // 新規登録モーダル
    $('#btnOpenCreateModal').on('click', function () {
        $('#assetForm')[0].reset();
        $('#assetId').val('');
        $('#inputAssetTag').prop('readonly', false);
        $('#assetModalTitle').text('資産新規登録');
        assetModal.show();
    });

    // 資産保存
    $('#assetForm').on('submit', function (e) {
        e.preventDefault();
        const id = $('#assetId').val();
        const payload = {
            assetTag: $('#inputAssetTag').val().trim(),
            assetName: $('#inputAssetName').val().trim(),
            category: $('#inputCategory').val(),
            serialNo: $('#inputSerialNo').val().trim(),
            location: $('#inputLocation').val().trim(),
            purchaseDate: $('#inputPurchaseDate').val() || null,
            purchasePrice: $('#inputPurchasePrice').val() || null,
            leaseExpiry: $('#inputLeaseExpiry').val() || null,
            note: $('#inputNote').val().trim()
        };

        const isEdit = !!id;
        const url = isEdit ? `/api/assets/${id}` : '/api/assets';
        const method = isEdit ? 'PUT' : 'POST';

        $.ajax({
            url: url,
            method: method,
            contentType: 'application/json',
            data: JSON.stringify(payload),
            headers: SES.csrf.header(),
            success: function (res) {
                if (res.code === 200) {
                    assetModal.hide();
                    SES.toast.success(isEdit ? '資産情報を更新しました。' : '資産を登録しました。');
                    loadAssets(currentPage);
                } else {
                    SES.toast.error(res.message || '保存に失敗しました。');
                }
            },
            error: function (xhr) {
                const msg = xhr.responseJSON ? xhr.responseJSON.message : 'エラーが発生しました。';
                SES.toast.error(msg);
            }
        });
    });

    // 貸与確定
    $('#assignForm').on('submit', function (e) {
        e.preventDefault();
        const payload = {
            assetId: $('#assignAssetId').val(),
            assigneeType: $('#assignType').val(),
            assigneeId: $('#assignTargetId').val(),
            startDate: $('#assignStartDate').val(),
            expectedReturnDate: $('#assignExpectedReturnDate').val() || null,
            note: $('#assignNote').val().trim()
        };

        $.ajax({
            url: '/api/asset-assignments',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            headers: SES.csrf.header(),
            success: function (res) {
                if (res.code === 200) {
                    assignModal.hide();
                    SES.toast.success('資産を貸与しました。');
                    loadAssets(currentPage);
                } else {
                    SES.toast.error(res.message || '貸与に失敗しました。');
                }
            },
            error: function (xhr) {
                const msg = xhr.responseJSON ? xhr.responseJSON.message : '貸与エラーが発生しました。';
                SES.toast.error(msg);
            }
        });
    });

    // 返却確定
    $('#returnForm').on('submit', function (e) {
        e.preventDefault();
        const id = $('#returnAssignmentId').val();
        const payload = {
            actualReturnDate: $('#returnActualDate').val(),
            note: $('#returnNote').val().trim()
        };

        $.ajax({
            url: `/api/asset-assignments/${id}/return`,
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            headers: SES.csrf.header(),
            success: function (res) {
                if (res.code === 200) {
                    returnModal.hide();
                    SES.toast.success('資産の返却を確認しました。');
                    loadAssets(currentPage);
                } else {
                    SES.toast.error(res.message || '返却処理に失敗しました。');
                }
            },
            error: function (xhr) {
                const msg = xhr.responseJSON ? xhr.responseJSON.message : '返却処理エラーが発生しました。';
                SES.toast.error(msg);
            }
        });
    });

    // 一覧読み込み
    function loadAssets(page) {
        currentPage = page;
        const params = {
            page: page,
            size: pageSize,
            keyword: $('#searchKeyword').val().trim(),
            category: $('#searchCategory').val(),
            status: $('#searchStatus').val()
        };

        $.ajax({
            url: '/api/assets',
            method: 'GET',
            data: params,
            success: function (res) {
                if (res.code === 200) {
                    renderTable(res.data.records);
                    renderPagination(res.data);
                }
            }
        });
    }

    function renderTable(records) {
        const tbody = $('#assetTableBody');
        tbody.empty();

        if (!records || records.length === 0) {
            tbody.append('<tr><td colspan="8" class="text-center py-4 text-muted">該当する資産がありません</td></tr>');
            return;
        }

        records.forEach(function (a) {
            const statusBadge = getStatusBadge(a.status);
            const tr = $(`
                <tr>
                    <td><strong class="text-info">${escapeHtml(a.assetTag)}</strong></td>
                    <td>
                        <div class="fw-bold">${escapeHtml(a.assetName)}</div>
                        <small class="text-muted">${escapeHtml(a.note || '')}</small>
                    </td>
                    <td><span class="badge bg-dark border border-secondary">${escapeHtml(a.category)}</span></td>
                    <td class="small">${escapeHtml(a.serialNo || '-')}</td>
                    <td>${statusBadge}</td>
                    <td>${escapeHtml(a.location || '-')}</td>
                    <td class="small">
                        <div>${a.purchaseDate || '-'}</div>
                        <div class="text-muted">${a.purchasePrice ? Number(a.purchasePrice).toLocaleString() + ' 円' : '-'}</div>
                    </td>
                    <td class="text-end">
                        <div class="btn-group btn-group-sm">
                            ${a.status === 'IN_STOCK' ? `<button class="btn btn-outline-primary btn-sm btn-assign" data-id="${a.id}" data-tag="${escapeHtml(a.assetTag)}" data-name="${escapeHtml(a.assetName)}">貸与</button>` : ''}
                            ${a.status === 'ASSIGNED' ? `<button class="btn btn-outline-success btn-sm btn-return-open" data-id="${a.id}">返却</button>` : ''}
                            <button class="btn btn-outline-secondary btn-sm btn-edit" data-id="${a.id}">編集</button>
                            <button class="btn btn-outline-info btn-sm btn-history" data-id="${a.id}" data-tag="${escapeHtml(a.assetTag)}">履歴</button>
                            ${a.status !== 'DISPOSED' && a.status !== 'ASSIGNED' ? `<button class="btn btn-outline-danger btn-sm btn-dispose" data-id="${a.id}">廃棄</button>` : ''}
                        </div>
                    </td>
                </tr>
            `);
            tbody.append(tr);
        });

        // 貸与ボタン
        $('.btn-assign').on('click', function () {
            const id = $(this).data('id');
            const tag = $(this).data('tag');
            const name = $(this).data('name');
            $('#assignAssetId').val(id);
            $('#assignAssetSummary').val(`${tag} - ${name}`);
            $('#assignStartDate').val(new Date().toISOString().substring(0, 10));
            assignModal.show();
        });

        // 返却ボタン
        $('.btn-return-open').on('click', function () {
            const assetId = $(this).data('id');
            $.ajax({
                url: `/api/assets/${assetId}/assignments`,
                method: 'GET',
                success: function (res) {
                    if (res.code === 200 && res.data.length > 0) {
                        const active = res.data.find(x => x.status === 'ACTIVE');
                        if (active) {
                            $('#returnAssignmentId').val(active.id);
                            $('#returnActualDate').val(new Date().toISOString().substring(0, 10));
                            returnModal.show();
                        } else {
                            SES.toast.error('有効な貸与レコードが見つかりません。');
                        }
                    }
                }
            });
        });

        // 編集ボタン
        $('.btn-edit').on('click', function () {
            const id = $(this).data('id');
            $.ajax({
                url: `/api/assets/${id}`,
                method: 'GET',
                success: function (res) {
                    if (res.code === 200) {
                        const a = res.data;
                        $('#assetId').val(a.id);
                        $('#inputAssetTag').val(a.assetTag).prop('readonly', true);
                        $('#inputAssetName').val(a.assetName);
                        $('#inputCategory').val(a.category);
                        $('#inputSerialNo').val(a.serialNo);
                        $('#inputLocation').val(a.location);
                        $('#inputPurchaseDate').val(a.purchaseDate);
                        $('#inputPurchasePrice').val(a.purchasePrice);
                        $('#inputLeaseExpiry').val(a.leaseExpiry);
                        $('#inputNote').val(a.note);
                        $('#assetModalTitle').text('資産情報編集');
                        assetModal.show();
                    }
                }
            });
        });

        // 履歴ボタン
        $('.btn-history').on('click', function () {
            const id = $(this).data('id');
            const tag = $(this).data('tag');
            $('#historyModalTitle').text(`資産履歴 [${tag}]`);

            $.ajax({
                url: `/api/assets/${id}/events`,
                method: 'GET',
                success: function (res) {
                    const eBody = $('#historyEventsBody');
                    eBody.empty();
                    if (res.data && res.data.length > 0) {
                        res.data.forEach(function (e) {
                            eBody.append(`<tr><td><small>${e.eventTime ? e.eventTime.replace('T', ' ').substring(0, 19) : '-'}</small></td><td><span class="badge bg-secondary">${escapeHtml(e.eventType)}</span></td><td>${escapeHtml(e.fromStatus || '-')} → ${escapeHtml(e.toStatus || '-')}</td><td>${escapeHtml(e.eventSummary)}</td></tr>`);
                        });
                    } else {
                        eBody.append('<tr><td colspan="4" class="text-center text-muted">イベント履歴なし</td></tr>');
                    }
                }
            });

            $.ajax({
                url: `/api/assets/${id}/assignments`,
                method: 'GET',
                success: function (res) {
                    const aBody = $('#historyAssignmentsBody');
                    aBody.empty();
                    if (res.data && res.data.length > 0) {
                        res.data.forEach(function (as) {
                            aBody.append(`<tr><td>${escapeHtml(as.assigneeType)} #${as.assigneeId}</td><td>${as.startDate || '-'}</td><td>${as.expectedReturnDate || '-'}</td><td>${as.actualReturnDate || '<span class="text-warning">貸与中</span>'}</td><td><span class="badge bg-dark">${escapeHtml(as.status)}</span></td></tr>`);
                        });
                    } else {
                        aBody.append('<tr><td colspan="5" class="text-center text-muted">貸与履歴なし</td></tr>');
                    }
                }
            });

            historyModal.show();
        });

        // 廃棄ボタン
        $('.btn-dispose').on('click', function () {
            const id = $(this).data('id');
            Swal.fire({
                title: '資産を廃棄しますか？',
                text: 'この操作を行うとステータスが DISPOSED に変更されます。',
                icon: 'warning',
                showCancelButton: true,
                confirmButtonText: '廃棄する',
                cancelButtonText: 'キャンセル',
                confirmButtonColor: '#d33'
            }).then((result) => {
                if (result.isConfirmed) {
                    $.ajax({
                        url: `/api/assets/${id}/dispose`,
                        method: 'POST',
                        contentType: 'application/json',
                        data: JSON.stringify({ reason: '手動廃棄処理' }),
                        headers: SES.csrf.header(),
                        success: function (res) {
                            if (res.code === 200) {
                                SES.toast.success('資産を廃棄処理しました。');
                                loadAssets(currentPage);
                            } else {
                                SES.toast.error(res.message || '廃棄に失敗しました。');
                            }
                        }
                    });
                }
            });
        });
    }

    function getStatusBadge(status) {
        switch (status) {
            case 'IN_STOCK':
                return '<span class="badge bg-success">保管中 (貸出可)</span>';
            case 'ASSIGNED':
                return '<span class="badge bg-primary">貸与中</span>';
            case 'UNDER_MAINTENANCE':
                return '<span class="badge bg-warning text-dark">修理/保守中</span>';
            case 'LOST':
                return '<span class="badge bg-danger">紛失</span>';
            case 'DISPOSED':
                return '<span class="badge bg-secondary">廃棄済</span>';
            case 'RESERVED':
                return '<span class="badge bg-info text-dark">予約済</span>';
            default:
                return `<span class="badge bg-dark">${escapeHtml(status)}</span>`;
        }
    }

    function renderPagination(pageData) {
        $('#paginationInfo').text(`全 ${pageData.total} 件 (${pageData.current} / ${pageData.pages || 1} ページ)`);
        const p = $('#pagination');
        p.empty();

        if (pageData.pages <= 1) return;

        if (pageData.current > 1) {
            p.append(`<li class="page-item"><a class="page-link bg-dark text-white border-secondary" href="javascript:void(0)" onclick="loadAssets(${pageData.current - 1})">前へ</a></li>`);
        }

        for (let i = 1; i <= pageData.pages; i++) {
            if (i === pageData.current) {
                p.append(`<li class="page-item active"><span class="page-link bg-primary border-primary">${i}</span></li>`);
            } else if (i === 1 || i === pageData.pages || Math.abs(i - pageData.current) <= 2) {
                p.append(`<li class="page-item"><a class="page-link bg-dark text-white border-secondary" href="javascript:void(0)" onclick="loadAssets(${i})">${i}</a></li>`);
            }
        }

        if (pageData.current < pageData.pages) {
            p.append(`<li class="page-item"><a class="page-link bg-dark text-white border-secondary" href="javascript:void(0)" onclick="loadAssets(${pageData.current + 1})">次へ</a></li>`);
        }
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    window.loadAssets = loadAssets;
});
