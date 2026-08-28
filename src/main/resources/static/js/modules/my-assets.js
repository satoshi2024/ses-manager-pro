/**
 * 要員マイポータル 貸与資産・アカウントモジュール (my-assets.js)
 */
$(function () {
    'use strict';

    let myAssetsCache = [];
    const lostModal = new bootstrap.Modal(document.getElementById('lostModal'));

    loadMyAssets();

    $('#btnOpenLostModal').on('click', function () {
        $('#lostForm')[0].reset();
        const sel = $('#lostAssetSelect');
        sel.empty();

        if (myAssetsCache.length === 0) {
            SES.toast.error('現在貸与されている資産がありません。');
            return;
        }

        myAssetsCache.forEach(function (a) {
            sel.append(`<option value="${a.assetId}">${escapeHtml(a.assetTag)} - ${escapeHtml(a.assetName)} (${escapeHtml(a.category)})</option>`);
        });

        lostModal.show();
    });

    $('#lostForm').on('submit', function (e) {
        e.preventDefault();
        const payload = {
            assetId: $('#lostAssetSelect').val(),
            incidentDetails: $('#lostIncidentDetails').val().trim()
        };

        Swal.fire({
            title: '紛失報告を送信しますか？',
            text: '緊急アラートが管理部門に通知されます。虚偽の報告は厳禁です。',
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: '送信する',
            cancelButtonText: 'キャンセル',
            confirmButtonColor: '#d33'
        }).then((result) => {
            if (result.isConfirmed) {
                $.ajax({
                    url: '/api/my/assets/report-lost',
                    method: 'POST',
                    contentType: 'application/json',
                    data: JSON.stringify(payload),
                    headers: SES.csrf.header(),
                    success: function (res) {
                        if (res.code === 200) {
                            lostModal.hide();
                            Swal.fire('報告完了', '紛失報告が正常に受付・通知されました。管理担当者からの指示に従ってください。', 'success');
                            loadMyAssets();
                        } else {
                            SES.toast.error(res.message || '報告に失敗しました。');
                        }
                    },
                    error: function (xhr) {
                        SES.toast.error(xhr.responseJSON ? xhr.responseJSON.message : 'エラーが発生しました。');
                    }
                });
            }
        });
    });

    function loadMyAssets() {
        $.ajax({
            url: '/api/my/assets',
            method: 'GET',
            success: function (res) {
                if (res.code === 200) {
                    myAssetsCache = res.data.assets || [];
                    renderAssets(myAssetsCache);
                    renderAccounts(res.data.accounts || []);
                    renderLicenses(res.data.licenses || []);
                }
            },
            error: function () {
                $('#myAssetTableBody').html('<tr><td colspan="7" class="text-center text-muted">読み込みに失敗しました</td></tr>');
            }
        });
    }

    function renderAssets(assets) {
        const tbody = $('#myAssetTableBody');
        tbody.empty();

        if (!assets || assets.length === 0) {
            tbody.append('<tr><td colspan="7" class="text-center py-4 text-muted">現在貸与されている端末・資産はありません</td></tr>');
            return;
        }

        assets.forEach(function (a) {
            const statusBadge = a.status === 'ASSIGNED' ? '<span class="badge bg-primary">貸与中</span>' : a.status === 'LOST' ? '<span class="badge bg-danger">紛失報告済</span>' : `<span class="badge bg-secondary">${escapeHtml(a.status)}</span>`;
            const tr = $(`
                <tr>
                    <td><strong class="text-info">${escapeHtml(a.assetTag)}</strong></td>
                    <td class="fw-bold">${escapeHtml(a.assetName)}</td>
                    <td><span class="badge bg-dark border border-secondary">${escapeHtml(a.category)}</span></td>
                    <td class="small">${escapeHtml(a.serialNo || '-')}</td>
                    <td>${a.startDate || '-'}</td>
                    <td>${a.expectedReturnDate || '<span class="text-muted">未定</span>'}</td>
                    <td>${statusBadge}</td>
                </tr>
            `);
            tbody.append(tr);
        });
    }

    function renderAccounts(accounts) {
        const tbody = $('#myAccountTableBody');
        tbody.empty();

        if (!accounts || accounts.length === 0) {
            tbody.append('<tr><td colspan="4" class="text-center py-3 text-muted">付与されている外部アカウントはありません</td></tr>');
            return;
        }

        accounts.forEach(function (acc) {
            const statusBadge = acc.status === 'ACTIVE' ? '<span class="badge bg-success">ACTIVE</span>' : '<span class="badge bg-secondary">' + escapeHtml(acc.status) + '</span>';
            const tr = $(`
                <tr>
                    <td>System #${acc.systemId}</td>
                    <td><strong class="text-info">${escapeHtml(acc.accountIdentifier)}</strong></td>
                    <td><span class="badge bg-dark">${escapeHtml(acc.permissionLevel || 'MEMBER')}</span></td>
                    <td>${statusBadge}</td>
                </tr>
            `);
            tbody.append(tr);
        });
    }

    function renderLicenses(licenses) {
        const tbody = $('#myLicenseTableBody');
        tbody.empty();

        if (!licenses || licenses.length === 0) {
            tbody.append('<tr><td colspan="3" class="text-center py-3 text-muted">割り当てられている有償ライセンスはありません</td></tr>');
            return;
        }

        licenses.forEach(function (lic) {
            const statusBadge = lic.status === 'ACTIVE' ? '<span class="badge bg-success">ACTIVE</span>' : '<span class="badge bg-secondary">' + escapeHtml(lic.status) + '</span>';
            const tr = $(`
                <tr>
                    <td>Plan #${lic.planId}</td>
                    <td>${lic.assignedDate || '-'}</td>
                    <td>${statusBadge}</td>
                </tr>
            `);
            tbody.append(tr);
        });
    }

    function escapeHtml(str) {
        if (!str) return '';
        return String(str).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&#039;');
    }
});
