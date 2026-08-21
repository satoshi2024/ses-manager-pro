document.addEventListener('DOMContentLoaded', function () {
    var fromDate = document.getElementById('fromDate');
    var toDate = document.getElementById('toDate');
    var btnFetch = document.getElementById('btnFetch');
    var fetchSummary = document.getElementById('fetchSummary');
    var tableBody = document.getElementById('pendingTableBody');
    var invoiceOptionsHtml = '';

    loadInvoiceOptions(function () {
        loadPending();
    });

    btnFetch.addEventListener('click', fetchDeposits);

    // 候補請求書への消込ボタン（動的に生成される要素なのでイベント委譲）
    $(tableBody).on('click', '.btn-apply-candidate', function () {
        var $btn = $(this);
        var depositId = $btn.data('deposit-id');
        var invoiceId = $btn.data('invoice-id');
        var invoiceNo = $btn.data('invoice-no');
        confirmAndApply(depositId, invoiceId, invoiceNo);
    });

    // 手動選択（候補なし）からの消込ボタン
    $(tableBody).on('click', '.btn-apply-manual', function () {
        var $btn = $(this);
        var depositId = $btn.data('deposit-id');
        var $sel = $('#manualInvoiceId-' + depositId);
        var invoiceId = $.trim($sel.val());
        if (!invoiceId) {
            Toast.error(SES.i18n.t('reconciliation.invoiceRequired', '請求書を選択してください'));
            return;
        }
        var invoiceLabel = $sel.find('option:selected').text() || invoiceId;
        confirmAndApply(depositId, invoiceId, invoiceLabel);
    });

    function loadInvoiceOptions(done) {
        $.ajax({
            url: '/api/invoices',
            type: 'GET',
            data: { current: 1, size: 1000 },
            success: function (res) {
                var placeholder = '<option value="">' + escapeHtml(SES.i18n.t('reconciliation.selectInvoice', '請求書を選択...')) + '</option>';
                if (res && res.code === 200) {
                    var records = (res.data && res.data.records) || [];
                    invoiceOptionsHtml = placeholder + records.map(function (inv) {
                        var label = (inv.invoiceNo || ('#' + inv.id)) + (inv.billingMonth ? ' / ' + inv.billingMonth : '');
                        return '<option value="' + escapeHtml(String(inv.id)) + '">' + escapeHtml(label) + '</option>';
                    }).join('');
                } else {
                    invoiceOptionsHtml = placeholder;
                }
                if (done) done();
            },
            error: function () {
                invoiceOptionsHtml = '<option value="">' + escapeHtml(SES.i18n.t('reconciliation.selectInvoice', '請求書を選択...')) + '</option>';
                if (done) done();
            }
        });
    }

    function fetchDeposits() {
        var body = {};
        if (fromDate.value) body.from = fromDate.value;
        if (toDate.value) body.to = toDate.value;

        btnFetch.disabled = true;
        $.ajax({
            url: '/api/reconciliation/fetch',
            type: 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
            data: JSON.stringify(body),
            success: function (res) {
                if (res && res.code === 200) {
                    var d = res.data || {};
                    var msg = (d.fetchedCount || 0) + '件取得、'
                        + (d.newCount || 0) + '件新規、'
                        + (d.autoMatchedCount || 0) + '件自動消込、'
                        + (d.pendingCount || 0) + '件要確認';
                    fetchSummary.textContent = msg;
                    fetchSummary.style.display = '';
                    Toast.success(msg);
                    loadPending();
                } else {
                    Toast.error((res && res.message) || '入金データの取得に失敗しました');
                }
            },
            error: function (xhr) {
                var msg = '入金データの取得に失敗しました';
                if (xhr && xhr.responseJSON && xhr.responseJSON.message) msg = xhr.responseJSON.message;
                Toast.error(msg);
            },
            complete: function () {
                btnFetch.disabled = false;
            }
        });
    }

    function loadPending() {
        $.ajax({
            url: '/api/reconciliation/pending',
            type: 'GET',
            success: function (res) {
                if (res && res.code === 200) {
                    renderTable(res.data || []);
                } else {
                    Toast.error((res && res.message) || '未消込入金の取得に失敗しました');
                }
            },
            error: function () {
                Toast.error('未消込入金の取得に失敗しました');
            }
        });
    }

    function confirmAndApply(depositId, invoiceId, invoiceLabel) {
        Swal.fire({
            title: '確認',
            text: 'この入金を請求書「' + invoiceLabel + '」に消込しますか？',
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: '消込する',
            cancelButtonText: 'キャンセル'
        }).then(function (result) {
            if (!result.isConfirmed) return;
            applyDeposit(depositId, invoiceId);
        });
    }

    function applyDeposit(depositId, invoiceId) {
        $.ajax({
            url: '/api/reconciliation/' + depositId + '/apply',
            type: 'POST',
            headers: Object.assign({ 'Content-Type': 'application/json' }, SES.csrf.header()),
            data: JSON.stringify({ invoiceId: Number(invoiceId) }),
            success: function (res) {
                if (res && res.code === 200) {
                    Toast.success('消込しました');
                    loadPending();
                } else {
                    Toast.error((res && res.message) || '消込に失敗しました');
                }
            },
            error: function (xhr) {
                var msg = '消込に失敗しました';
                if (xhr && xhr.responseJSON && xhr.responseJSON.message) msg = xhr.responseJSON.message;
                Toast.error(msg);
            }
        });
    }

    function renderTable(data) {
        tableBody.innerHTML = '';
        if (!data || data.length === 0) {
            tableBody.innerHTML = '<tr><td colspan="5" class="text-center text-muted py-4">未消込の入金はありません</td></tr>';
            return;
        }

        data.forEach(function (deposit) {
            var tr = document.createElement('tr');

            var statusBadge = deposit.classification === 'candidate'
                ? '<span class="badge bg-info text-dark">候補あり</span>'
                : '<span class="badge bg-secondary">要確認</span>';

            var actionHtml;
            if (deposit.classification === 'candidate' && deposit.candidates && deposit.candidates.length > 0) {
                actionHtml = '<div class="d-flex flex-column gap-2">' + deposit.candidates.map(function (c) {
                    return '' +
                        '<div class="d-flex justify-content-between align-items-center border border-secondary rounded p-2">' +
                        '  <div class="small">' +
                        '    <div><span class="fw-bold">' + escapeHtml(c.invoiceNo) + '</span>' +
                        '      <span class="badge bg-dark border border-secondary ms-1">score ' + escapeHtml(String(c.score)) + '</span></div>' +
                        '    <div class="text-muted">' + escapeHtml(c.customerName) + ' / 期日: ' + escapeHtml(c.dueDate || '-') + ' / 残高: ' + formatCurrency(c.balance) + '</div>' +
                        '  </div>' +
                        '  <button type="button" class="btn btn-sm btn-primary bg-gradient-blue border-0 btn-apply-candidate" ' +
                        '    data-deposit-id="' + escapeHtml(String(deposit.depositId)) + '" ' +
                        '    data-invoice-id="' + escapeHtml(String(c.invoiceId)) + '" ' +
                        '    data-invoice-no="' + escapeHtml(c.invoiceNo) + '">消込</button>' +
                        '</div>';
                }).join('') + '</div>';
            } else {
                actionHtml = '' +
                    '<div class="input-group input-group-sm" style="max-width: 320px;">' +
                    '  <select class="form-select bg-dark text-white border-secondary" id="manualInvoiceId-' + escapeHtml(String(deposit.depositId)) + '">' +
                    (invoiceOptionsHtml || ('<option value="">' + escapeHtml(SES.i18n.t('reconciliation.selectInvoice', '請求書を選択...')) + '</option>')) +
                    '  </select>' +
                    '  <button type="button" class="btn btn-outline-light btn-apply-manual" data-deposit-id="' + escapeHtml(String(deposit.depositId)) + '">消込</button>' +
                    '</div>';
            }

            tr.innerHTML =
                '<td>' + escapeHtml(deposit.depositDate || '') + '</td>' +
                '<td class="text-end">' + formatCurrency(deposit.amount) + '</td>' +
                '<td>' + escapeHtml(deposit.payerName || '') + '</td>' +
                '<td>' + statusBadge + '</td>' +
                '<td>' + actionHtml + '</td>';
            tableBody.appendChild(tr);
        });
    }

    function escapeHtml(str) {
        if (str === null || str === undefined) return '';
        return String(str).replace(/[&<>'"]/g,
            function (tag) {
                return {
                    '&': '&amp;',
                    '<': '&lt;',
                    '>': '&gt;',
                    "'": '&#39;',
                    '"': '&quot;'
                }[tag] || tag;
            }
        );
    }

    function formatCurrency(num) {
        if (num === null || num === undefined) return '¥0';
        return '¥' + Number(num).toLocaleString();
    }
});
