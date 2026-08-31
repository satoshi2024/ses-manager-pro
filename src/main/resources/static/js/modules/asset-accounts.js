/**
 * 外部アカウント・ライセンス管理モジュール (asset-accounts.js)
 */
$(function () {
    'use strict';

    let systemsCache = [];
    const accountModal = new bootstrap.Modal(document.getElementById('accountModal'));
    const planModal = new bootstrap.Modal(document.getElementById('planModal'));

    loadSystems();
    loadPlans();
    loadAccounts();

    $('#filterSystemSelect, #filterStatusSelect').on('change', function () {
        loadAccounts();
    });

    $('#btnOpenAccountModal').on('click', function () {
        $('#accountForm')[0].reset();
        accountModal.show();
    });

    $('#btnOpenPlanModal').on('click', function () {
        $('#planForm')[0].reset();
        $('#planId').val('');
        planModal.show();
    });

    $('#accountForm').on('submit', function (e) {
        e.preventDefault();
        const payload = {
            systemId: $('#inputSystemId').val(),
            accountIdentifier: $('#inputAccountIdentifier').val().trim(),
            assigneeType: $('#inputAssigneeType').val(),
            assigneeId: $('#inputAssigneeId').val(),
            permissionLevel: $('#inputPermissionLevel').val().trim()
        };

        $.ajax({
            url: '/api/external-accounts',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            headers: SES.csrf.header(),
            success: function (res) {
                if (res.code === 200) {
                    accountModal.hide();
                    SES.toast.success('外部アカウント参照を登録しました。');
                    loadAccounts();
                } else {
                    SES.toast.error(res.message || '登録に失敗しました。');
                }
            },
            error: function (xhr) {
                SES.toast.error(xhr.responseJSON ? xhr.responseJSON.message : 'エラーが発生しました。');
            }
        });
    });

    $('#planForm').on('submit', function (e) {
        e.preventDefault();
        const id = $('#planId').val();
        const payload = {
            id: id || null,
            planCode: $('#inputPlanCode').val().trim(),
            planName: $('#inputPlanName').val().trim(),
            seatLimit: $('#inputSeatLimit').val(),
            costPerSeat: $('#inputCostPerSeat').val() || null,
            status: 'ACTIVE'
        };

        $.ajax({
            url: '/api/licenses/plans',
            method: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            headers: SES.csrf.header(),
            success: function (res) {
                if (res.code === 200) {
                    planModal.hide();
                    SES.toast.success('ライセンスプランを保存しました。');
                    loadPlans();
                } else {
                    SES.toast.error(res.message || '保存に失敗しました。');
                }
            },
            error: function (xhr) {
                SES.toast.error(xhr.responseJSON ? xhr.responseJSON.message : 'エラーが発生しました。');
            }
        });
    });

    function loadSystems() {
        $.ajax({
            url: '/api/external-accounts/systems',
            method: 'GET',
            success: function (res) {
                if (res.code === 200) {
                    systemsCache = res.data;
                    const sel = $('#filterSystemSelect');
                    const modalSel = $('#inputSystemId');
                    sel.find('option:not(:first)').remove();
                    modalSel.empty();

                    res.data.forEach(function (s) {
                        sel.append(`<option value="${s.id}">${escapeHtml(s.systemName)} (${escapeHtml(s.systemCode)})</option>`);
                        modalSel.append(`<option value="${s.id}">${escapeHtml(s.systemName)} (${escapeHtml(s.systemCode)})</option>`);
                    });
                }
            }
        });
    }

    function loadPlans() {
        $.ajax({
            url: '/api/licenses/plans',
            method: 'GET',
            data: { page: 1, size: 20 },
            success: function (res) {
                if (res.code === 200) {
                    renderPlans(res.data.records);
                }
            }
        });
    }

    function renderPlans(plans) {
        const container = $('#licensePlanCards');
        container.empty();

        if (!plans || plans.length === 0) {
            container.append('<div class="col-12 text-center text-muted py-2">登録されているライセンスプランはありません</div>');
            return;
        }

        plans.forEach(function (p) {
            const usagePercent = Math.min(100, Math.round((p.allocatedCount / p.seatLimit) * 100));
            const progressColor = usagePercent >= 90 ? 'bg-danger' : usagePercent >= 75 ? 'bg-warning' : 'bg-success';

            const card = $(`
                <div class="col-md-4">
                    <div class="card bg-dark border-secondary p-3">
                        <div class="d-flex justify-content-between align-items-center mb-2">
                            <span class="fw-bold text-white">${escapeHtml(p.planName)}</span>
                            <span class="badge bg-secondary">${escapeHtml(p.planCode)}</span>
                        </div>
                        <div class="d-flex justify-content-between small text-muted mb-1">
                            <span>席数消費: ${p.allocatedCount} / ${p.seatLimit} 席</span>
                            <span>${usagePercent}%</span>
                        </div>
                        <div class="progress bg-secondary" style="height: 6px;">
                            <div class="progress-bar ${progressColor}" role="progressbar" style="width: ${usagePercent}%"></div>
                        </div>
                        <div class="d-flex justify-content-between align-items-center mt-2 small text-muted">
                            <span>単価: ${p.costPerSeat ? Number(p.costPerSeat).toLocaleString() + ' 円/席' : '-'}</span>
                            <span class="badge ${p.status === 'ACTIVE' ? 'bg-success' : 'bg-secondary'}">${p.status}</span>
                        </div>
                    </div>
                </div>
            `);
            container.append(card);
        });
    }

    function loadAccounts() {
        const params = {
            page: 1,
            size: 50,
            systemId: $('#filterSystemSelect').val() || null,
            status: $('#filterStatusSelect').val() || null
        };

        $.ajax({
            url: '/api/external-accounts',
            method: 'GET',
            data: params,
            success: function (res) {
                if (res.code === 200) {
                    renderAccounts(res.data.records);
                }
            }
        });
    }

    function renderAccounts(records) {
        const tbody = $('#accountTableBody');
        tbody.empty();

        if (!records || records.length === 0) {
            tbody.append('<tr><td colspan="11" class="text-center py-4 text-muted">該当する外部アカウント参照はありません</td></tr>');
            return;
        }

        records.forEach(function (acc) {
            const statusBadge = acc.status === 'ACTIVE' ? '<span class="badge bg-success">ACTIVE</span>' : acc.status === 'REVOKED' ? '<span class="badge bg-danger">REVOKED</span>' : '<span class="badge bg-warning text-dark">SUSPENDED</span>';
            const sys = systemsCache.find(x => x.id === acc.systemId);
            const sysName = sys ? sys.systemName : `Sys #${acc.systemId}`;
            const actorType = acc.actorType || 'LEGACY_UNRESOLVED';
            const confirmationSource = acc.confirmationSource || 'LEGACY_UNRESOLVED';

            const tr = $(`
                <tr>
                    <td><strong>${escapeHtml(sysName)}</strong></td>
                    <td><span class="text-info">${escapeHtml(acc.accountIdentifier)}</span></td>
                    <td>${escapeHtml(acc.assigneeType)} #${acc.assigneeId}</td>
                    <td><span class="badge bg-dark">${escapeHtml(acc.permissionLevel || 'MEMBER')}</span></td>
                    <td>${statusBadge}</td>
                    <td><small class="text-muted">${acc.provisionedAt ? acc.provisionedAt.substring(0, 10) : '-'}</small></td>
                    <td><small class="text-muted">${acc.revokeRequestedBy ? 'User#' + acc.revokeRequestedBy : '-'}</small></td>
                    <td><small class="text-muted">${acc.revokeConfirmedAt ? acc.revokeConfirmedAt.replace('T', ' ').substring(0, 16) : '-'}</small></td>
                    <td><span class="badge bg-secondary">${escapeHtml(actorType)}</span></td>
                    <td><span class="badge bg-dark">${escapeHtml(confirmationSource)}</span></td>
                    <td class="text-end">
                        ${acc.status !== 'REVOKED' ? `<button class="btn btn-outline-danger btn-sm btn-confirm-revoke" data-id="${acc.id}" data-id-str="${escapeHtml(acc.accountIdentifier)}">失効確認</button>` : ''}
                    </td>
                </tr>
            `);
            tbody.append(tr);
        });

        $('.btn-confirm-revoke').on('click', function () {
            const id = $(this).data('id');
            const idStr = $(this).data('id-str');
            Swal.fire({
                title: '外部アカウントの失効を確認しますか？',
                text: `アカウント「${idStr}」が外部プロバイダ側で削除/無効化されたことを確認し、台帳ステータスを REVOKED に更新します。`,
                icon: 'warning',
                showCancelButton: true,
                confirmButtonText: '失効確認完了',
                cancelButtonText: 'キャンセル',
                confirmButtonColor: '#d33'
            }).then((result) => {
                if (result.isConfirmed) {
                    $.ajax({
                        url: `/api/external-accounts/${id}/confirm-revoke`,
                        method: 'POST',
                        headers: SES.csrf.header(),
                        success: function (res) {
                            if (res.code === 200) {
                                SES.toast.success('アカウントの失効を確認しました。');
                                loadAccounts();
                            } else {
                                SES.toast.error(res.message || '失効確認に失敗しました。');
                            }
                        }
                    });
                }
            });
        });
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
    }
});
