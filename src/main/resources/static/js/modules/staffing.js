// ============================================================
// staffing-capacity-planning（S12）: ポジションボード/要員タイムライン/配置モーダル
// 過配賦になる操作はservice層が拒否し、D&D失敗時はカードを元の位置へ戻す（design §3）。
// ============================================================
window.SES_staffing = (function () {
    'use strict';

    const TYPE_PROJECT = '案件';
    const TYPE_INTERNAL = '社内';
    const TYPE_BENCH = '待機';
    const STATUS_DRAFT = '下書き';
    const STATUS_CONFIRMED = '確定';

    const t = function (key, fallback) {
        return (window.SES && SES.i18n && SES.i18n.t) ? SES.i18n.t(key) : fallback;
    };

    // ---------------- 配置モーダル（共有） ----------------

    let modalEngineerId = null;   // 保存先の要員ID
    let modalAllocationId = null; // null=新規
    let modalVersion = null;

    /**
     * 配置の作成/編集モーダルを開く。
     * @param opts {engineerId, engineerName, allocation(既存), positionId(既定), projectId}
     */
    function openAllocationModal(opts) {
        modalEngineerId = opts.engineerId;
        modalAllocationId = opts.allocation ? opts.allocation.id : null;
        modalVersion = opts.allocation ? opts.allocation.version : null;

        $('#sa-engineer-name').text(opts.engineerName || '—');
        $('#sa-engineer-name').data('engineer-id', modalEngineerId);
        $('#sa-engineer-select-wrap').toggleClass('d-none', opts.engineerId != null);
        $('#sa-engineer-name-wrap').toggleClass('d-none', opts.engineerId == null);
        const alloc = opts.allocation || {};
        $('#sa-id').val(modalAllocationId || '');
        $('#sa-type').val(alloc.allocationType || TYPE_PROJECT);
        $('#sa-startDate').val(alloc.startDate || '');
        $('#sa-endDate').val(alloc.endDate || '');
        $('#sa-percent').val(alloc.allocationPercent != null ? alloc.allocationPercent : '100');
        $('#sa-exceptionReason').val(alloc.exceptionReason || '');
        $('#sa-projectId').val('');
        $('#sa-project-wrap').toggleClass('d-none', opts.projectId != null);
        $('#sa-positionId').empty();
        if (opts.projectId != null) {
            loadPositions(opts.projectId, alloc.positionId != null ? alloc.positionId : (opts.positionId != null ? opts.positionId : null));
        }
        onTypeChanged();
        if (opts.engineerId == null) {
            loadEngineerOptions();
        }
        bootstrap.Modal.getOrCreateInstance(document.getElementById('staffingAllocationModal')).show();
    }

    function onTypeChanged() {
        const type = $('#sa-type').val();
        $('#sa-position-wrap').toggleClass('d-none', type !== TYPE_PROJECT);
        $('#sa-internal-hint').toggleClass('d-none', type === TYPE_PROJECT);
    }

    /** 案件配下のポジションをロードしてセレクトへ入れる。 */
    function loadPositions(projectId, selectedPositionId) {
        $.ajax({
            url: '/api/projects/' + projectId + '/positions',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200) { return; }
                const select = $('#sa-positionId');
                select.empty();
                (res.data || []).forEach(function (p) {
                    $('<option>').val(p.id).text(p.positionNo + ' ' + (p.roleName || '') + (p.status ? ' [' + p.status + ']' : '')).appendTo(select);
                });
                if (selectedPositionId != null) { select.val(String(selectedPositionId)); }
            }
        });
    }

    /** 要員optionsをロードしてセレクトへ入れる（board用）。 */
    function loadEngineerOptions() {
        $.ajax({
            url: '/api/engineers/options',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200) { return; }
                const select = $('#sa-engineerId');
                select.empty();
                (res.data || []).forEach(function (e) {
                    $('<option>').val(e.id).text(e.name || e.id).appendTo(select);
                });
                const current = $('#sa-engineer-name').data('engineer-id');
                if (current != null) { select.val(String(current)); }
            }
        });
    }

    /** 案件セレクト変更でポジションを再ロードする。 */
    function bindProjectSelect() {
        $(document).on('change', '#sa-projectId', function () {
            const projectId = $(this).val();
            if (!projectId) {
                $('#sa-positionId').empty();
                return;
            }
            loadPositions(Number(projectId), null);
        });
    }

    function saveAllocation() {
        const selectId = $('#sa-engineerId').val();
        const engineerId = selectId ? Number(selectId) : ($('#sa-engineer-name').data('engineer-id') || null);
        if (!engineerId) {
            Toast.fire({ icon: 'error', title: t('error.staffing.engineerRequired', '要員が指定されていません') });
            return;
        }
        const startDate = $('#sa-startDate').val();
        const endDate = $('#sa-endDate').val();
        if (!startDate) {
            Toast.fire({ icon: 'error', title: t('error.staffing.startDateRequired', '開始日は必須です') });
            return;
        }
        if (endDate && endDate < startDate) {
            Toast.fire({ icon: 'error', title: t('error.staffing.invalidPeriod', '終了日は開始日以降を指定してください') });
            return;
        }
        const payload = {
            id: $('#sa-id').val() ? Number($('#sa-id').val()) : null,
            allocationType: $('#sa-type').val(),
            positionId: $('#sa-type').val() === TYPE_PROJECT && $('#sa-positionId').val()
                ? Number($('#sa-positionId').val()) : null,
            startDate: startDate,
            endDate: endDate || null,
            allocationPercent: Number($('#sa-percent').val()),
            exceptionReason: $('#sa-exceptionReason').val() || null,
            version: modalVersion
        };
        const url = '/api/engineers/' + engineerId + '/allocations';
        $.ajax({
            url: url,
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            success: function (res) {
                if (res.code !== 200) {
                    Toast.fire({ icon: 'error', title: res.message || t('error.systemError', 'エラーが発生しました') });
                    return;
                }
                bootstrap.Modal.getInstance(document.getElementById('staffingAllocationModal')).hide();
                Toast.fire({ icon: 'success', title: t('staffing.msg.saved', '配置を保存しました') });
                reloadCurrentView();
            },
            error: function (xhr) {
                const msg = (xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || null;
                Toast.fire({ icon: 'error', title: msg || t('error.systemError', 'エラーが発生しました') });
            }
        });
    }

    function confirmAllocation(allocationId, engineerId) {
        $.ajax({
            url: '/api/engineers/' + engineerId + '/allocations/' + allocationId + '/confirm',
            method: 'POST',
            success: function (res) {
                if (res.code !== 200) {
                    Toast.fire({ icon: 'error', title: res.message || t('error.systemError', 'エラーが発生しました') });
                    return;
                }
                Toast.fire({ icon: 'success', title: t('staffing.msg.confirmed', '配置を確定しました') });
                reloadCurrentView();
            },
            error: function (xhr) {
                const msg = (xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || null;
                Toast.fire({ icon: 'error', title: msg || t('error.systemError', 'エラーが発生しました') });
            }
        });
    }

    function discardAllocation(allocationId, engineerId) {
        Swal.fire({
            title: t('staffing.confirm.discardTitle', '配置を破棄しますか'),
            text: t('staffing.confirm.discardText', '破棄した配置は一覧から除外されます'),
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: t('common.ok', 'OK'),
            cancelButtonText: t('common.cancel', 'キャンセル')
        }).then(function (result) {
            if (!result.isConfirmed) { return; }
            $.ajax({
                url: '/api/engineers/' + engineerId + '/allocations/' + allocationId + '/discard',
                method: 'POST',
                success: function (res) {
                    if (res.code !== 200) {
                        Toast.fire({ icon: 'error', title: res.message || t('error.systemError', 'エラーが発生しました') });
                        return;
                    }
                    Toast.fire({ icon: 'success', title: t('staffing.msg.discarded', '配置を破棄しました') });
                    reloadCurrentView();
                },
                error: function (xhr) {
                    const msg = (xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || null;
                    Toast.fire({ icon: 'error', title: msg || t('error.systemError', 'エラーが発生しました') });
                }
            });
        });
    }

    // ---------------- ポジションボード（案件詳細） ----------------

    let boardProjectId = null;
    let boardColumns = [];   // [{positionId, positionNo, roleName, requiredCount, filledCount, status, allocations:[card]}]
    let boardEngineerNameById = {};

    function initProjectBoard(projectId) {
        boardProjectId = projectId;
        loadBoard();
        bindBoardDragDrop();
    }

    function loadBoard() {
        $.ajax({
            url: '/api/projects/' + boardProjectId + '/board',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200) {
                    $('#position-board-body').html('<div class="text-danger py-3">' + (res.message || 'エラー') + '</div>');
                    return;
                }
                renderBoard(res.data || { columns: [], benchAllocations: [] });
            },
            error: function (xhr) {
                $('#position-board-body').html('<div class="text-danger py-3">' + (xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error) || 'エラー') + '</div>');
            }
        });
    }

    function renderBoard(data) {
        boardColumns = (data.columns || []).map(function (col) {
            return {
                positionId: col.position.id,
                positionNo: col.position.positionNo,
                roleName: col.position.roleName,
                requiredCount: col.position.requiredCount,
                filledCount: col.filledCount,
                status: col.position.status,
                allocations: col.allocations || []
            };
        });
        const $cols = $('#position-board-columns').empty();
        if (boardColumns.length === 0) {
            $cols.append('<div class="col-12 text-muted small py-3">' + t('staffing.board.noPositions', 'ポジションが未登録です') + '</div>');
        }
        boardColumns.forEach(function (col) {
            const $col = $('<div>').addClass('col-md-6 col-xl-4 mb-3');
            const $card = $('<div>').addClass('card bg-card border-dark shadow-sm h-100');
            const header =
                '<div class="card-header bg-transparent border-dark d-flex justify-content-between align-items-center py-2">' +
                '  <div class="d-flex align-items-center gap-2 flex-wrap">' +
                '    <span class="badge bg-primary">' + SES.escapeHtml(col.positionNo || '') + '</span>' +
                '    <span class="text-white fw-bold small">' + SES.escapeHtml(col.roleName || '—') + '</span>' +
                '    <span class="badge bg-secondary text-light">' + SES.escapeHtml(col.status || '') + '</span>' +
                '    <span class="badge bg-info text-dark">' + t('staffing.board.filledCount', '充足') + ' ' + (col.filledCount || 0) + '/' + (col.requiredCount || 0) + '</span>' +
                '  </div>' +
                '  <div class="d-flex gap-1">' +
                '    <button type="button" class="btn btn-sm btn-outline-primary py-0 px-1" data-action="add-allocation" data-position-id="' + col.positionId + '" title="' + t('staffing.timeline.add', '配置追加') + '"><i class="bi bi-person-plus"></i></button>' +
                '    <button type="button" class="btn btn-sm btn-outline-primary py-0 px-1" data-action="edit-position" data-position-id="' + col.positionId + '" title="' + t('staffing.board.positionEdit', '編集') + '"><i class="bi bi-pencil"></i></button>' +
                '    <button type="button" class="btn btn-sm btn-outline-danger py-0 px-1" data-action="delete-position" data-position-id="' + col.positionId + '" title="' + t('staffing.board.positionDelete', '削除') + '"><i class="bi bi-trash"></i></button>' +
                '  </div>' +
                '</div>';
            const $body = $('<div>').addClass('card-body p-2 staff-drop-column').attr('data-position-id', col.positionId).css('min-height', '90px');
            col.allocations.forEach(function (alloc) {
                $body.append(cardElement(alloc));
            });
            $card.append(header).append($body);
            $col.append($card);
            $cols.append($col);
        });
        // 社内/待機列
        const bench = data.benchAllocations || [];
        const $bench = $('#position-board-bench').empty();
        bench.forEach(function (alloc) {
            $bench.append(cardElement(alloc));
        });
        bindCardDrag();
    }

    /** 配置カード要素（actualはドラッグ不可）。 */
    function cardElement(alloc) {
        const isActual = alloc.sourceContractId != null;
        const isDraft = alloc.status === STATUS_DRAFT;
        const statusClass = isActual ? 'bg-success' : (isDraft ? 'bg-warning text-dark' : 'bg-primary');
        const statusLabel = isActual ? t('staffing.timeline.actual', '実績') : (isDraft ? t('staffing.timeline.draft', '下書き') : t('staffing.timeline.plan', '計画'));
        const approval = alloc.approvalStatus === 'approved'
            ? '<span class="badge bg-success text-light ms-1">' + t('staffing.timeline.approval.approved', '承認済') + '</span>'
            : (alloc.approvalStatus ? '<span class="badge bg-warning text-dark ms-1">' + t('staffing.timeline.approval.pending', '承認待ち') + '</span>' : '');
        const btnEdit = isActual ? '' :
            '<button type="button" class="btn btn-sm btn-outline-secondary py-0 px-1 staff-alloc-edit" data-alloc-id="' + alloc.id + '" title="' + t('staffing.timeline.edit', '編集') + '"><i class="bi bi-pencil"></i></button>';
        const btnConfirm = isActual || !isDraft ? '' :
            '<button type="button" class="btn btn-sm btn-outline-success py-0 px-1 staff-alloc-confirm" data-alloc-id="' + alloc.id + '" title="' + t('staffing.timeline.confirm', '確定') + '"><i class="bi bi-check-lg"></i></button>';
        const btnDiscard = isActual ? '' :
            '<button type="button" class="btn btn-sm btn-outline-danger py-0 px-1 staff-alloc-discard" data-alloc-id="' + alloc.id + '" title="' + t('staffing.timeline.discard', '破棄') + '"><i class="bi bi-x-lg"></i></button>';
        return $('<div>')
            .addClass('staff-alloc-card border rounded p-2 mb-1 small bg-dark bg-opacity-50')
            .attr('draggable', isActual ? 'false' : 'true')
            .attr('data-alloc-id', alloc.id)
            .attr('data-engineer-id', alloc.engineerId)
            .attr('data-position-id', alloc.positionId != null ? alloc.positionId : '')
            .attr('data-project-id', alloc.projectId != null ? alloc.projectId : '')
            .attr('data-is-actual', isActual ? '1' : '0')
            .attr('data-start', alloc.startDate || '')
            .attr('data-end', alloc.endDate || '')
            .attr('data-percent', alloc.allocationPercent != null ? alloc.allocationPercent : 100)
            .attr('data-version', alloc.version != null ? alloc.version : 0)
            .append(
                '<div class="d-flex justify-content-between align-items-start gap-1">' +
                '  <div class="text-truncate">' +
                '    <span class="text-white fw-bold">' + SES.escapeHtml(alloc.engineerName || '—') + '</span>' +
                '    <span class="badge ' + statusClass + ' ms-1">' + statusLabel + '</span>' + approval +
                '  </div>' +
                '  <div class="d-flex gap-1 flex-shrink-0">' + btnEdit + btnConfirm + btnDiscard + '</div>' +
                '</div>' +
                '<div class="text-muted">' + SES.escapeHtml(alloc.allocationType || '') + ' ' +
                (alloc.allocationPercent != null ? alloc.allocationPercent : '') + '% · ' +
                (alloc.startDate || '—') + ' 〜 ' + (alloc.endDate || t('staffing.board.openEnd', '未定')) + '</div>' +
                (alloc.exceptionReason ? '<div class="text-warning" title="' + SES.escapeHtml(alloc.exceptionReason) + '">⚠ ' + SES.escapeHtml(alloc.exceptionReason) + '</div>' : '')
            );
    }

    function bindCardDrag() {
        $('.staff-alloc-card[draggable="true"]').off('dragstart dragend').on('dragstart', function (e) {
            e.originalEvent.dataTransfer.setData('text/plain', String($(this).data('alloc-id')));
            $(this).addClass('opacity-50');
            $(this).data('from-position', String($(this).data('position-id') || 'bench'));
        }).on('dragend', function () {
            $(this).removeClass('opacity-50');
        });
    }

    function bindBoardDragDrop() {
        $(document).on('dragover', '.staff-drop-column', function (e) {
            e.preventDefault();
        });
        $(document).on('drop', '.staff-drop-column', function (e) {
            e.preventDefault();
            const allocId = e.originalEvent.dataTransfer.getData('text/plain');
            const targetPositionId = $(this).data('position-id');
            const $card = $('.staff-alloc-card[data-alloc-id="' + allocId + '"]');
            if (!$card.length || String($card.data('position-id') || 'bench') === String(targetPositionId)) { return; }
            if ($card.data('is-actual') === '1') {
                Toast.fire({ icon: 'error', title: t('staffing.msg.actualNotMovable', '実契約由来の配置は変更できません') });
                return;
            }
            // 移動は配置の上書き保存で行う。失敗時はカードを元の位置へ戻す（UI rollback）。
            const fromColumn = $card.closest('.staff-drop-column');
            $(this).append($card);
            const engineerId = $card.data('engineer-id');
            $.ajax({
                url: '/api/engineers/' + engineerId + '/allocations',
                method: 'POST',
                contentType: 'application/json',
                data: JSON.stringify({
                    id: Number(allocId),
                    allocationType: TYPE_PROJECT,
                    positionId: Number(targetPositionId),
                    startDate: $card.attr('data-start') || null,
                    endDate: $card.attr('data-end') || null,
                    allocationPercent: Number($card.attr('data-percent') || 100),
                    version: Number($card.attr('data-version') || 0)
                }),
                success: function (res) {
                    if (res.code !== 200) {
                        rollbackCard($card, fromColumn);
                        Toast.fire({ icon: 'error', title: res.message || t('error.systemError', 'エラーが発生しました') });
                        return;
                    }
                    Toast.fire({ icon: 'success', title: t('staffing.msg.moved', '配置を移動しました') });
                    loadBoard();
                },
                error: function (xhr) {
                    rollbackCard($card, fromColumn);
                    const msg = (xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || null;
                    Toast.fire({ icon: 'error', title: msg || t('error.systemError', 'エラーが発生しました') });
                }
            });
        });
        $(document).on('click', '[data-action="edit-position"]', function () {
            openPositionModal(Number($(this).data('position-id')));
        });
        $(document).on('click', '[data-action="add-allocation"]', function () {
            openAllocationModal({ engineerId: null, engineerName: null, projectId: boardProjectId, positionId: Number($(this).data('position-id')) });
        });
        $(document).on('click', '[data-action="delete-position"]', function () {
            deletePosition(Number($(this).data('position-id')));
        });
        $(document).on('click', '.staff-alloc-edit', function () {
            const alloc = cardDataOf($(this).data('alloc-id'));
            const projectId = $('.staff-alloc-card[data-alloc-id="' + alloc.id + '"]').data('project-id');
            openAllocationModal({ allocation: alloc, engineerId: null, projectId: projectId || null });
        });
        $(document).on('click', '.staff-alloc-confirm', function () {
            const alloc = cardDataOf($(this).data('alloc-id'));
            confirmAllocation(alloc.id, alloc.engineerId);
        });
        $(document).on('click', '.staff-alloc-discard', function () {
            const alloc = cardDataOf($(this).data('alloc-id'));
            discardAllocation(alloc.id, alloc.engineerId);
        });
    }

    /** カードを元の列へ戻す（D&D失敗時のUI rollback）。 */
    function rollbackCard($card, fromColumn) {
        if (fromColumn && fromColumn.length) {
            fromColumn.append($card);
        } else {
            loadBoard();
        }
        $card.removeClass('opacity-50');
    }

    function cardDataOf(allocId) {
        const $card = $('.staff-alloc-card[data-alloc-id="' + allocId + '"]');
        return {
            id: Number(allocId),
            engineerId: $card.data('engineer-id'),
            positionId: $card.data('position-id') ? Number($card.data('position-id')) : null,
            startDate: $card.attr('data-start') || null,
            endDate: $card.attr('data-end') || null,
            allocationPercent: Number($card.attr('data-percent') || 100),
            version: Number($card.attr('data-version') || 0),
            allocationType: $card.text().indexOf(TYPE_PROJECT) >= 0 ? TYPE_PROJECT : ($card.text().indexOf(TYPE_INTERNAL) >= 0 ? TYPE_INTERNAL : TYPE_BENCH)
        };
    }

    // ---------------- ポジションモーダル（案件詳細） ----------------

    let modalPositionId = null;

    function openPositionModal(positionId) {
        modalPositionId = positionId || null;
        const column = positionId ? boardColumns.find(function (c) { return c.positionId === positionId; }) : null;
        $('#sp-id').val(positionId || '');
        $('#sp-positionNo').val(column ? column.positionNo : '');
        $('#sp-roleName').val(column ? column.roleName : '');
        $('#sp-requiredCount').val(column ? column.requiredCount : 1);
        $('#sp-startDate').val('');
        $('#sp-endDate').val('');
        $('#sp-location').val('');
        $('#sp-percent').val(column ? 100 : 100);
        bootstrap.Modal.getOrCreateInstance(document.getElementById('staffingPositionModal')).show();
    }

    function savePosition() {
        const positionNo = $('#sp-positionNo').val();
        const roleName = $('#sp-roleName').val();
        if (!positionNo || !roleName) {
            Toast.fire({ icon: 'error', title: t('error.staffing.positionRequired', 'ポジション番号と役割名は必須です') });
            return;
        }
        const payload = {
            id: $('#sp-id').val() ? Number($('#sp-id').val()) : null,
            positionNo: positionNo,
            roleName: roleName,
            requiredCount: Number($('#sp-requiredCount').val() || 1),
            startDate: $('#sp-startDate').val() || null,
            endDate: $('#sp-endDate').val() || null,
            location: $('#sp-location').val() || null,
            allocationPercent: Number($('#sp-percent').val() || 100),
            priority: null
        };
        const method = payload.id ? 'PUT' : 'POST';
        const url = payload.id
            ? '/api/projects/' + boardProjectId + '/positions/' + payload.id
            : '/api/projects/' + boardProjectId + '/positions';
        $.ajax({
            url: url,
            method: method,
            contentType: 'application/json',
            data: JSON.stringify(payload),
            success: function (res) {
                if (res.code !== 200) {
                    Toast.fire({ icon: 'error', title: res.message || t('error.systemError', 'エラーが発生しました') });
                    return;
                }
                bootstrap.Modal.getInstance(document.getElementById('staffingPositionModal')).hide();
                Toast.fire({ icon: 'success', title: t('staffing.msg.positionSaved', 'ポジションを保存しました') });
                loadBoard();
            },
            error: function (xhr) {
                const msg = (xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || null;
                Toast.fire({ icon: 'error', title: msg || t('error.systemError', 'エラーが発生しました') });
            }
        });
    }

    function deletePosition(positionId) {
        const column = boardColumns.find(function (c) { return c.positionId === positionId; });
        Swal.fire({
            title: t('staffing.board.positionDeleteConfirm', 'ポジションを削除しますか'),
            text: (column ? column.positionNo + ' ' : '') + t('staffing.board.positionDeleteText', '充足済みポジションは削除できません'),
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: t('common.ok', 'OK'),
            cancelButtonText: t('common.cancel', 'キャンセル')
        }).then(function (result) {
            if (!result.isConfirmed) { return; }
            $.ajax({
                url: '/api/projects/' + boardProjectId + '/positions/' + positionId,
                method: 'DELETE',
                success: function (res) {
                    if (res.code !== 200) {
                        Toast.fire({ icon: 'error', title: res.message || t('error.systemError', 'エラーが発生しました') });
                        return;
                    }
                    Toast.fire({ icon: 'success', title: t('staffing.msg.positionDeleted', 'ポジションを削除しました') });
                    loadBoard();
                },
                error: function (xhr) {
                    const msg = (xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error)) || null;
                    Toast.fire({ icon: 'error', title: msg || t('error.systemError', 'エラーが発生しました') });
                }
            });
        });
    }

    // ---------------- 要員タイムライン（要員詳細） ----------------

    let timelineEngineerId = null;

    function initEngineerTimeline(engineerId) {
        timelineEngineerId = engineerId;
        loadTimeline();
    }

    function loadTimeline() {
        $.ajax({
            url: '/api/engineers/' + timelineEngineerId + '/allocations',
            method: 'GET',
            success: function (res) {
                if (res.code !== 200) {
                    $('#staffing-timeline-body').html('<div class="text-danger py-2">' + (res.message || 'エラー') + '</div>');
                    return;
                }
                renderTimeline(res.data || []);
            },
            error: function (xhr) {
                $('#staffing-timeline-body').html('<div class="text-danger py-2">' + (xhr.responseJSON && (xhr.responseJSON.message || xhr.responseJSON.error) || 'エラー') + '</div>');
            }
        });
    }

    function renderTimeline(cards) {
        const $body = $('#staffing-timeline-body').empty();
        if (cards.length === 0) {
            $body.append('<div class="text-muted small py-2">' + t('staffing.timeline.empty', '配置計画はありません') + '</div>');
            return;
        }
        cards.forEach(function (alloc) {
            const isActual = alloc.sourceContractId != null;
            const isDraft = alloc.status === STATUS_DRAFT;
            const statusClass = isActual ? 'bg-success' : (isDraft ? 'bg-warning text-dark' : 'bg-primary');
            const statusLabel = isActual ? t('staffing.timeline.actual', '実績') : (isDraft ? t('staffing.timeline.draft', '下書き') : t('staffing.timeline.plan', '計画'));
            const approval = alloc.approvalStatus === 'approved'
                ? '<span class="badge bg-success text-light ms-1">' + t('staffing.timeline.approval.approved', '承認済') + '</span>'
                : (alloc.approvalStatus ? '<span class="badge bg-warning text-dark ms-1">' + t('staffing.timeline.approval.pending', '承認待ち') + '</span>' : '');
            const btnEdit = isActual ? '' :
                '<button type="button" class="btn btn-sm btn-outline-secondary py-0 px-1 st-edit" data-alloc-id="' + alloc.id + '" title="' + t('staffing.timeline.edit', '編集') + '"><i class="bi bi-pencil"></i></button>';
            const btnConfirm = isActual || !isDraft ? '' :
                '<button type="button" class="btn btn-sm btn-outline-success py-0 px-1 st-confirm" data-alloc-id="' + alloc.id + '" title="' + t('staffing.timeline.confirm', '確定') + '"><i class="bi bi-check-lg"></i></button>';
            const btnDiscard = isActual ? '' :
                '<button type="button" class="btn btn-sm btn-outline-danger py-0 px-1 st-discard" data-alloc-id="' + alloc.id + '" title="' + t('staffing.timeline.discard', '破棄') + '"><i class="bi bi-x-lg"></i></button>';
            const name = alloc.positionId
                ? (alloc.positionNo || '') + ' ' + (alloc.roleName || '') + ' · ' + (alloc.projectName || '')
                : (alloc.allocationType === TYPE_INTERNAL ? t('staffing.timeline.internal', '社内業務') : t('staffing.timeline.bench', '待機'));
            $body.append(
                '<div class="d-flex justify-content-between align-items-center border border-dark rounded p-2 mb-2 bg-dark bg-opacity-25 gap-2 flex-wrap">' +
                '  <div class="min-w-0">' +
                '    <div class="d-flex align-items-center flex-wrap">' +
                '      <span class="badge ' + statusClass + '">' + statusLabel + '</span>' +
                '      <span class="text-white fw-bold small ms-2">' + SES.escapeHtml(name) + '</span>' + approval +
                '    </div>' +
                '    <div class="text-muted small mt-1">' +
                (alloc.startDate || '—') + ' 〜 ' + (alloc.endDate || t('staffing.board.openEnd', '未定')) +
                ' · ' + (alloc.allocationPercent != null ? alloc.allocationPercent : '') + '%' +
                (alloc.exceptionReason ? ' · <span class="text-warning">' + SES.escapeHtml(alloc.exceptionReason) + '</span>' : '') +
                '    </div>' +
                '  </div>' +
                '  <div class="d-flex gap-1 flex-shrink-0">' + btnEdit + btnConfirm + btnDiscard + '</div>' +
                '</div>'
            );
        });
        $body.find('.st-edit').on('click', function () {
            const alloc = cards.find(function (c) { return c.id === Number($(this).data('alloc-id')); }.bind(this));
            openAllocationModal({ allocation: alloc, engineerId: timelineEngineerId, projectId: alloc.projectId });
        });
        $body.find('.st-confirm').on('click', function () {
            confirmAllocation(Number($(this).data('alloc-id')), timelineEngineerId);
        });
        $body.find('.st-discard').on('click', function () {
            discardAllocation(Number($(this).data('alloc-id')), timelineEngineerId);
        });
    }

    /** 現在の画面（boardまたはtimeline）を再読み込みする。 */
    function reloadCurrentView() {
        if (boardProjectId != null) { loadBoard(); }
        if (timelineEngineerId != null) { loadTimeline(); }
    }

    // ---------------- 公開 ----------------

    bindProjectSelect();

    return {
        initProjectBoard: initProjectBoard,
        initEngineerTimeline: initEngineerTimeline,
        openAllocationModal: openAllocationModal,
        saveAllocation: saveAllocation,
        openPositionModal: openPositionModal,
        savePosition: savePosition,
        onTypeChanged: onTypeChanged
    };
})();
