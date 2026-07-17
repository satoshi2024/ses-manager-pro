$(document).ready(function() {
    loadContracts();
    loadSelectOptions();
});

// サーバの ContractServiceImpl.ALLOWED_STATUS_TRANSITIONS の複製(唯一の権威はサーバ側)。
// 遷移可否の最終検証はサーバが行う。ここはUI表示用のため、サーバ側を変更したら本定義も追随すること。
// 値はDB格納の日本語ステータスそのまま送る。
const STATUS_TRANSITIONS = {
    '準備中': ['稼動中', '解約'],
    '稼動中': ['終了', '解約']
};

function statusLabel(status) {
    switch (status) {
        case '準備中': return SES.i18n.t('contract.status.preparing');
        case '稼動中': return SES.i18n.t('contract.status.active');
        case '終了': return SES.i18n.t('contract.status.ended');
        case '解約': return SES.i18n.t('contract.status.cancelled');
        default: return status;
    }
}

function loadContracts() {
    $('#contract-table-body').html('<tr><td colspan="8" class="text-center text-muted py-4"><div class="spinner-border spinner-border-sm me-2"></div>' + SES.i18n.t('js.common.loading') + '</td></tr>');

    const salesVal = $('#search-salesUserId').val();
    const params = {
        status: $('#search-form [name="status"]').val(),
        customerId: $('#search-customerId').val(),
        // 'none' = 担当営業未設定(sales_user_id IS NULL)での絞り込み
        salesUserId: salesVal === 'none' ? null : salesVal,
        salesUnassigned: salesVal === 'none' ? true : null,
        contractNo: $('#search-form [name="contractNo"]').val(),
        endDateFrom: $('#search-form [name="endDateFrom"]').val(),
        endDateTo: $('#search-form [name="endDateTo"]').val()
    };

    $.ajax({
        url: '/api/contracts',
        method: 'GET',
        data: params,
        success: function(res) {
            if (res.code === 200 && res.data) {
                renderContracts(res.data.records || res.data);
            } else {
                Toast.error(SES.i18n.t('js.common.error_fetch'));
                $('#contract-table-body').html(`<tr><td colspan="8" class="text-center text-muted py-4">${SES.i18n.t('js.common.error_fetch')}</td></tr>`);
            }
        },
        error: function(err) {
            console.error(err);
            Toast.error(SES.i18n.t('js.common.error_network'));
            $('#contract-table-body').html(`<tr><td colspan="8" class="text-center text-muted py-4">${SES.i18n.t('js.common.error_network')}</td></tr>`);
        }
    });
}

function loadSelectOptions() {
    // マスタは全件セレクトに載せる(既定 size=10 のページングだと11件目以降の要員/案件/顧客を持つ
    // 契約が編集不能になるため、十分大きい size を明示する)。
    // Load Engineers
    $.get('/api/engineers?size=1000', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#cont-engineerId');
            select.empty().append(`<option value="">${SES.i18n.t('proposal.engineer.select')}</option>`);
            (res.data.records || res.data).forEach(e => select.append(`<option value="${e.id}">${SES.escapeHtml(e.fullName)}</option>`));
        }
    });
    // Load Projects
    $.get('/api/projects?size=1000', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#cont-projectId');
            select.empty().append(`<option value="">${SES.i18n.t('proposal.project.select')}</option>`);
            (res.data.records || res.data).forEach(p => select.append(`<option value="${p.id}">${SES.escapeHtml(p.projectName)}</option>`));
        }
    });
    // Load Customers
    $.get('/api/customers?size=1000', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#cont-customerId');
            const searchSelect = $('#search-customerId');
            select.empty().append(`<option value="">${SES.i18n.t('contract.customer.select')}</option>`);
            searchSelect.empty().append(`<option value="">${SES.i18n.t('contract.customer.filter')}</option>`);
            (res.data.records || res.data).forEach(c => {
                select.append(`<option value="${c.id}">${SES.escapeHtml(c.companyName)}</option>`);
                searchSelect.append(`<option value="${c.id}">${SES.escapeHtml(c.companyName)}</option>`);
            });
        }
    });
    // Load Sales Reps
    $.get('/api/engineers/sales-user-options', function(res) {
        if(res.code === 200 && res.data) {
            const select = $('#cont-salesUserId');
            const searchSelect = $('#search-salesUserId');
            select.empty().append(`<option value="">${SES.i18n.t('contract.salesRep.select')}</option>`);
            searchSelect.empty().append(`<option value="">${SES.i18n.t('contract.salesRep.filter')}</option>`);
            // 担当営業未設定での絞り込みオプション(営業成績「未帰属」行からのリンク先)
            searchSelect.append(`<option value="none">${SES.i18n.t('contract.salesRep.unassigned')}</option>`);
            res.data.forEach(u => {
                select.append(`<option value="${u.id}">${SES.escapeHtml(u.realName)}</option>`);
                searchSelect.append(`<option value="${u.id}">${SES.escapeHtml(u.realName)}</option>`);
            });
            // URL に ?salesUserId=none が付いていれば未設定フィルタを初期選択して再検索する
            const urlSales = new URLSearchParams(window.location.search).get('salesUserId');
            if (urlSales === 'none') {
                searchSelect.val('none');
                loadContracts();
            }
        }
    });

    // Auto preset primary sales rep when engineer is selected (新規登録時のみ有効。編集時のプリセットを壊さないよう、ユーザー操作の change でのみ発火)
    $('#cont-engineerId').on('change', function() {
        const engId = $(this).val();
        $('#cont-salesUserId').val('');
        if (engId) {
            $.get(`/api/engineers/${engId}/sales-reps`, function(res) {
                if(res.code === 200 && res.data) {
                    const primary = res.data.find(r => r.primaryFlag === 1);
                    if (primary) {
                        $('#cont-salesUserId').val(primary.salesUserId);
                    }
                }
            });
        }
    });
}

function renderContracts(list) {
    const tbody = $('#contract-table-body');
    tbody.empty();

    if (!list || list.length === 0) {
        tbody.append(`<tr><td colspan="8" class="text-center text-muted py-4">${SES.i18n.t('common.noData')}</td></tr>`);
        return;
    }

    list.forEach(c => {
        const engineerName = c.engineerName != null ? c.engineerName : SES.i18n.t('js.contract.engineer_deleted');
        const customerName = c.customerName != null ? c.customerName : '-';
        const projectName = c.projectName != null ? c.projectName : '-';
        const contractNo = c.contractNo != null ? c.contractNo : ('C-' + c.id);

        // 遷移可能なステータスがあれば状態変更ボタンを表示
        const transitions = STATUS_TRANSITIONS[c.status] || [];
        const statusBtn = transitions.length > 0
            ? `<button type="button" class="btn btn-outline-info btn-sm me-1" title="${SES.i18n.t('contract.action.changeStatus')}" onclick="changeContractStatus(${c.id}, '${c.status}')"><i class="bi bi-arrow-left-right"></i></button>`
            : '';

        const tr = `
            <tr>
                <td class="px-4 py-3"><span class="font-monospace text-muted">${SES.escapeHtml(contractNo)}</span></td>
                <td class="py-3">
                    <div class="fw-bold text-white">${SES.escapeHtml(engineerName)}</div>
                    <div class="small text-muted">${SES.escapeHtml(c.contractType || SES.i18n.t('js.contract.type.quasi'))}</div>
                </td>
                <td class="py-3">
                    <div class="text-white">${SES.escapeHtml(customerName)}</div>
                    <div class="small text-muted text-truncate" style="max-width:200px;">${SES.escapeHtml(projectName)}</div>
                </td>
                <td class="py-3 text-white small">
                    ${SES.escapeHtml(c.salesUserName || '-')}
                </td>
                <td class="py-3 text-white small">
                    <div><i class="bi bi-play-circle text-success me-1"></i>${c.startDate || '-'}</div>
                    <div><i class="bi bi-stop-circle text-danger me-1"></i>${c.endDate || '-'}</div>
                </td>
                <td class="py-3">
                    <div class="text-accent-green fw-bold">¥${c.sellingPrice != null ? c.sellingPrice.toLocaleString() : '---'}</div>
                    <div class="small text-muted">¥${c.costPrice != null ? c.costPrice.toLocaleString() : '---'}</div>
                </td>
                <td class="py-3">
                    ${getStatusBadge(c.status)}
                </td>
                <td class="px-4 py-3 text-end text-nowrap">
                    <button type="button" class="btn btn-outline-secondary btn-sm me-1" title="${SES.i18n.t('contract.action.edit')}" onclick="openEditContract(${c.id})"><i class="bi bi-pencil"></i></button>
                    ${statusBtn}
                    <button type="button" class="btn btn-outline-danger btn-sm text-danger border-danger" onclick="deleteContract(${c.id})"><i class="bi bi-trash"></i></button>
                </td>
            </tr>
        `;
        tbody.append(tr);
    });
}

function getStatusBadge(status) {
    let bg = 'status-secondary';
    if(status === '稼動中') bg = 'status-success';
    if(status === '準備中') bg = 'status-primary';
    if(status === '終了') bg = 'status-secondary';
    if(status === '解約') bg = 'status-danger';
    return `<span class="status-badge ${bg}">${statusLabel(status || '準備中')}</span>`;
}

// 新規登録モーダルを開く(フォームをリセットし hidden id をクリア)
function openNewContract() {
    $('#contract-form')[0].reset();
    $('#cont-id').val('');
    $('#contractModalTitle').text(SES.i18n.t('contract.new'));
    $('#contractSaveBtnLabel').text(SES.i18n.t('common.register'));
    bootstrap.Modal.getOrCreateInstance(document.getElementById('contractModal')).show();
}

// 編集モーダルを開く。GET /api/contracts/{id} で全項目を取得しプリセット。
function openEditContract(id) {
    $.get('/api/contracts/' + id, function(res) {
        if (res.code !== 200 || !res.data) {
            Toast.error(res.message || SES.i18n.t('js.common.error_fetch'));
            return;
        }
        const c = res.data;
        $('#contract-form')[0].reset();
        $('#cont-id').val(c.id);
        $('#cont-engineerId').val(c.engineerId != null ? String(c.engineerId) : '');
        $('#cont-projectId').val(c.projectId != null ? String(c.projectId) : '');
        $('#cont-customerId').val(c.customerId != null ? String(c.customerId) : '');
        // 担当営業が退職済み等でセレクト対象外(在職営業のみロードされる)の場合、原値を保持する
        // option を動的補完する。これがないと val() が空になり PUT で salesUserId=null(ALWAYS)が
        // 書き込まれ、成約帰属・営業成績が無警告で消える。
        if (c.salesUserId != null && $(`#cont-salesUserId option[value="${c.salesUserId}"]`).length === 0) {
            $('#cont-salesUserId').append(`<option value="${c.salesUserId}">${SES.escapeHtml(SES.i18n.t('contract.salesRep.inactive'))}</option>`);
        }
        $('#cont-salesUserId').val(c.salesUserId != null ? String(c.salesUserId) : '');
        $('#cont-contractType').val(c.contractType || '準委任');
        $('#cont-startDate').val(c.startDate || '');
        $('#cont-endDate').val(c.endDate || '');
        $('#cont-sellingPrice').val(c.sellingPrice != null ? c.sellingPrice : '');
        $('#cont-costPrice').val(c.costPrice != null ? c.costPrice : '');
        $('#cont-settlementHoursMin').val(c.settlementHoursMin != null ? c.settlementHoursMin : '');
        $('#cont-settlementHoursMax').val(c.settlementHoursMax != null ? c.settlementHoursMax : '');
        $('#cont-fractionRule').val(c.fractionRule || '');
        $('#cont-autoRenew').val(c.autoRenew != null ? String(c.autoRenew) : '0');
        $('#cont-commissionBaseType').val(c.commissionBaseType || '');
        $('#cont-commissionRate').val(c.commissionRate != null ? c.commissionRate : '');
        $('#contractModalTitle').text(SES.i18n.t('contract.edit'));
        $('#contractSaveBtnLabel').text(SES.i18n.t('common.save'));
        bootstrap.Modal.getOrCreateInstance(document.getElementById('contractModal')).show();
    });
}

// 契約フォームの全項目を組み立てる。PUT/POST 共通で全フィールドを常に送信する
// (FieldStrategy.ALWAYS 前提: 未選択 select は明示的に null を積み、「既定に戻す」を機能させる)。
function buildContractPayload() {
    const val = (sel) => { const v = $(sel).val(); return v !== '' && v != null ? v : null; };
    return {
        engineerId: val('#cont-engineerId') ? parseInt(val('#cont-engineerId')) : null,
        projectId: val('#cont-projectId') ? parseInt(val('#cont-projectId')) : null,
        customerId: val('#cont-customerId') ? parseInt(val('#cont-customerId')) : null,
        salesUserId: val('#cont-salesUserId') ? parseInt(val('#cont-salesUserId')) : null,
        contractType: val('#cont-contractType'),
        startDate: val('#cont-startDate'),
        endDate: val('#cont-endDate'),
        sellingPrice: val('#cont-sellingPrice') ? parseInt(val('#cont-sellingPrice')) : null,
        costPrice: val('#cont-costPrice') ? parseInt(val('#cont-costPrice')) : null,
        settlementHoursMin: val('#cont-settlementHoursMin') ? parseFloat(val('#cont-settlementHoursMin')) : null,
        settlementHoursMax: val('#cont-settlementHoursMax') ? parseFloat(val('#cont-settlementHoursMax')) : null,
        fractionRule: val('#cont-fractionRule'),
        autoRenew: val('#cont-autoRenew') != null ? parseInt(val('#cont-autoRenew')) : 0,
        commissionBaseType: val('#cont-commissionBaseType'),
        commissionRate: val('#cont-commissionRate') ? parseFloat(val('#cont-commissionRate')) : null
    };
}

function saveContract() {
    const id = $('#cont-id').val();
    const engineerId = $('#cont-engineerId').val();
    const projectId = $('#cont-projectId').val();

    if (!engineerId || !projectId) {
        Toast.error(SES.i18n.t('js.contract.error.engineer_project'));
        return;
    }

    const data = buildContractPayload();
    if (!id) {
        // 新規時のみ既定ステータス。更新時は status を送らない(サーバが無視するが混同を避ける)。
        data.status = '準備中';
    }

    $.ajax({
        url: id ? ('/api/contracts/' + id) : '/api/contracts',
        method: id ? 'PUT' : 'POST',
        contentType: 'application/json',
        data: JSON.stringify(data),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(id ? SES.i18n.t('js.contract.success.update') : SES.i18n.t('js.contract.success.register'));
                bootstrap.Modal.getOrCreateInstance(document.getElementById('contractModal')).hide();
                $('#contract-form')[0].reset();
                $('#cont-id').val('');
                loadContracts();
            } else {
                Toast.error(res.message || SES.i18n.t('js.contract.error.register'));
            }
        },
        error: function(err) {
            console.error(err);
            const msg = err.responseJSON && err.responseJSON.message ? err.responseJSON.message : SES.i18n.t('js.common.error_network');
            Toast.error(msg);
        }
    });
}

// 状態遷移。解約のみ解約日入力ダイアログを挟む。
function changeContractStatus(id, currentStatus) {
    const transitions = STATUS_TRANSITIONS[currentStatus] || [];
    if (transitions.length === 0) {
        return;
    }
    const inputOptions = {};
    transitions.forEach(s => { inputOptions[s] = statusLabel(s); });

    Swal.fire({
        title: SES.i18n.t('contract.action.changeStatus'),
        input: 'select',
        inputOptions: inputOptions,
        inputPlaceholder: '...',
        showCancelButton: true,
        confirmButtonColor: '#0d6efd',
        cancelButtonColor: '#6c757d',
        confirmButtonText: SES.i18n.t('common.confirm'),
        cancelButtonText: SES.i18n.t('js.project.delete.cancel')
    }).then((result) => {
        if (!result.isConfirmed || !result.value) return;
        const newStatus = result.value;
        if (newStatus === '解約') {
            promptCancelDate(id);
        } else {
            postStatusChange(id, newStatus, null);
        }
    });
}

function promptCancelDate(id) {
    // ローカル日付(JSTなど)で今日を組み立てる。toISOString()はUTCのため0:00〜9:00に前日になる問題を回避。
    const d = new Date();
    const today = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
    Swal.fire({
        title: SES.i18n.t('contract.cancel.title'),
        text: SES.i18n.t('contract.cancel.datePrompt'),
        input: 'date',
        inputValue: today,
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: SES.i18n.t('contract.action.cancel'),
        cancelButtonText: SES.i18n.t('js.project.delete.cancel'),
        inputValidator: (value) => {
            if (!value) return SES.i18n.t('error.contract.cancelDateRequired') || '';
        }
    }).then((result) => {
        if (!result.isConfirmed || !result.value) return;
        postStatusChange(id, '解約', result.value);
    });
}

function postStatusChange(id, newStatus, cancelDate) {
    $.ajax({
        url: '/api/contracts/' + id + '/status',
        method: 'PUT',
        contentType: 'application/json',
        data: JSON.stringify({ status: newStatus, cancelDate: cancelDate }),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('js.contract.success.update'));
                loadContracts();
            } else {
                Toast.error(res.message || SES.i18n.t('js.contract.error.register'));
            }
        },
        error: function(err) {
            console.error(err);
            // 不正遷移(409)などのAPIメッセージをそのままトースト表示する
            const msg = err.responseJSON && err.responseJSON.message ? err.responseJSON.message : SES.i18n.t('js.common.error_network');
            Toast.error(msg);
        }
    });
}

// 現在の検索条件(#search-form)を反映してExcel出力する。
// バイナリレスポンスのため $.ajax ではなく window.location.href で直接ダウンロードさせる
// (common.js の ajaxSetup complete ハンドラが非JSONレスポンスをセッション切れと誤検知するのを避けるため)
function exportContracts() {
    const params = {
        status: $('#search-form [name="status"]').val(),
        customerId: $('#search-customerId').val(),
        salesUserId: $('#search-salesUserId').val(),
        contractNo: $('#search-form [name="contractNo"]').val(),
        endDateFrom: $('#search-form [name="endDateFrom"]').val(),
        endDateTo: $('#search-form [name="endDateTo"]').val()
    };
    window.location.href = '/api/contracts/export?' + $.param(params, true);
}

function deleteContract(id) {
    Swal.fire({
        title: SES.i18n.t('js.project.delete.title'),
        text: SES.i18n.t('js.contract.delete.text'),
        icon: 'warning',
        showCancelButton: true,
        confirmButtonColor: '#dc3545',
        cancelButtonColor: '#6c757d',
        confirmButtonText: SES.i18n.t('js.project.delete.confirm'),
        cancelButtonText: SES.i18n.t('js.project.delete.cancel')
    }).then((result) => {
        if (result.isConfirmed) {
            $.ajax({
                url: '/api/contracts/' + id,
                method: 'DELETE',
                success: function(res) {
                    if (res.code === 200) {
                        Toast.success(SES.i18n.t('js.project.delete.success'));
                        loadContracts();
                    } else {
                        Toast.error(res.message || SES.i18n.t('js.project.delete.error'));
                    }
                },
                error: function(err) {
                    console.error(err);
                    Toast.error(SES.i18n.t('js.common.error_network'));
                }
            });
        }
    });
}
