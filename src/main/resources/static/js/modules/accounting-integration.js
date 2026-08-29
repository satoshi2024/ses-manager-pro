/**
 * 会計・支払連携モジュール (accounting-integration.js / A1)
 * 4言語 i18n 対応 (ja, en, zh_CN, ko) & DOM XSS 完全サニタイズ
 */
window.AccountingIntegration = (function () {
    function toKebab(str) {
        return str.replace(/([a-z0-9]|(?=[A-Z]))([A-Z])/g, '$1-$2').toLowerCase();
    }

    /**
     * 単一翻訳源 (messages*.properties) から HTML data 属性経由でローカライズ文字列を取得 (R1-P1-11)
     */
    function t(key) {
        let el = $('#i18n-data');
        if (el.length) {
            let val = el.attr('data-' + toKebab(key));
            if (val) return val;
        }
        return key;
    }

    /**
     * XSS対策: 任意文字列をHTMLとして安全にエスケープする (P1-10)。
     */
    function escapeHtml(str) {
        if (str === null || str === undefined) return '';
        return String(str)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#x27;');
    }

    let currentConnId = null;

    function getJobModal() {
        let el = document.getElementById('jobDetailModal');
        return el ? bootstrap.Modal.getOrCreateInstance(el) : null;
    }

    function getMapModal() {
        let el = document.getElementById('mappingModal');
        return el ? bootstrap.Modal.getOrCreateInstance(el) : null;
    }

    function getIgnoreModal() {
        let el = document.getElementById('ignoreModal');
        return el ? bootstrap.Modal.getOrCreateInstance(el) : null;
    }

    function loadPreviewInvoiceOptions() {
        let sel = $('#previewInvoiceId');
        if (!sel.length) return;
        let current = sel.val();
        $.ajax({
            url: '/api/invoices',
            type: 'GET',
            data: { current: 1, size: 1000 }
        }).done(function (res) {
            if (!res || res.code !== 200) return;
            let placeholder = sel.find('option[value=""]').first().prop('outerHTML')
                || `<option value="">${escapeHtml(t('selectInvoice') || SES.i18n.t('accounting.preview.selectInvoice', '請求書を選択...'))}</option>`;
            let options = ((res.data && res.data.records) || []).map(function (inv) {
                let label = (inv.invoiceNo || ('#' + inv.id)) + (inv.billingMonth ? ' / ' + inv.billingMonth : '');
                return `<option value="${escapeHtml(String(inv.id))}">${escapeHtml(label)}</option>`;
            }).join('');
            sel.html(placeholder + options);
            if (current) sel.val(current);
        });
    }

    // === 接続設定 ===
    function loadConnections() {
        $.ajax({
            url: '/api/accounting/connections',
            type: 'GET'
        }).done(function (res) {
            let list = res.data || [];
            let container = $('#connectionsContainer').empty();
            let select = $('#mappingConnSelect').empty();

            if (list.length === 0) {
                container.html(`<div class="col-12 text-center text-muted py-4">${escapeHtml(t('noConnections'))}</div>`);
                return;
            }

            list.forEach(function (c, idx) {
                select.append(`<option value="${escapeHtml(String(c.id))}">${escapeHtml(c.provider)} (${escapeHtml(c.product)}) - ${escapeHtml(c.companyName || t('disconnected'))}</option>`);
                if (idx === 0) currentConnId = c.id;

                let statusBadge = c.status === 'CONNECTED' ? `<span class="badge bg-success">${escapeHtml(t('connected'))}</span>` :
                    c.status === 'REAUTH_REQUIRED' ? `<span class="badge bg-warning text-dark">${escapeHtml(t('reauth'))}</span>` :
                        `<span class="badge bg-secondary">${escapeHtml(t('disconnected'))}</span>`;

                let cardHtml = `
                <div class="col-md-6">
                    <div class="card shadow-sm border-0 h-100">
                        <div class="card-body">
                            <div class="d-flex justify-content-between align-items-center mb-2">
                                <h6 class="card-title fw-bold mb-0">
                                    <i class="bi bi-link-45deg me-1"></i>${escapeHtml(c.provider.toUpperCase())} (${escapeHtml(c.product)})
                                </h6>
                                ${statusBadge}
                            </div>
                            <div class="small text-muted mb-2">
                                <div>${escapeHtml(t('companyOffice'))}: <strong>${escapeHtml(c.companyName || '-')}</strong> (ID: ${escapeHtml(String(c.externalCompanyId || '-'))})</div>
                                <div>${escapeHtml(t('validUntil'))}: ${escapeHtml(c.expiresAt ? c.expiresAt.replace('T', ' ') : '-')}</div>
                                <div>${escapeHtml(t('lastRefreshed'))}: ${escapeHtml(c.lastRefreshedAt ? c.lastRefreshedAt.replace('T', ' ') : '-')}</div>
                            </div>
                        </div>
                    </div>
                </div>`;
                container.append(cardHtml);
            });

            if (currentConnId) {
                loadMappings(currentConnId);
            }
        });
    }

    // === マッピング一覧 ===
    function loadMappings(connectionId) {
        if (!connectionId) return;
        let objectType = $('#mappingTypeFilter').val();
        let url = `/api/accounting/mappings?connectionId=${connectionId}` + (objectType ? `&objectType=${objectType}` : '');

        $.ajax({
            url: url,
            type: 'GET'
        }).done(function (res) {
            let list = res.data || [];
            let tbody = $('#mappingsTbody').empty();

            if (list.length === 0) {
                tbody.html(`<tr><td colspan="6" class="text-center text-muted py-4">${escapeHtml(t('noMappings'))}</td></tr>`);
                return;
            }

            list.forEach(function (m) {
                let verifiedBadge = m.verifiedAt ?
                    `<span class="badge bg-success"><i class="bi bi-check2 me-1"></i>${escapeHtml(t('verified'))} (${escapeHtml(m.verifiedAt.substring(0, 10))})</span>` :
                    `<span class="badge bg-danger"><i class="bi bi-x me-1"></i>${escapeHtml(t('unverified'))}</span>`;

                let tr = `
                <tr>
                    <td><span class="badge bg-light text-dark border">${escapeHtml(m.objectType)}</span></td>
                    <td class="fw-bold font-monospace">${escapeHtml(m.internalCode)}</td>
                    <td class="font-monospace">${escapeHtml(m.externalId)}</td>
                    <td>${escapeHtml(m.externalCode || '-')}</td>
                    <td>${verifiedBadge}</td>
                    <td><div class="d-flex flex-wrap justify-content-end align-items-center gap-1"><button class="btn btn-xs btn-outline-primary btn-verify-mapping" data-id="${m.id}" title="${escapeHtml(t('btnVerify'))}" aria-label="${escapeHtml(t('btnVerify'))}">
                            <i class="bi bi-check-circle" aria-hidden="true"></i>
                        </button>
                        <button class="btn btn-xs btn-outline-secondary btn-edit-mapping" data-id="${m.id}" title="${escapeHtml(t('btnEdit'))}" aria-label="${escapeHtml(t('btnEdit'))}">
                            <i class="bi bi-pencil" aria-hidden="true"></i>
                        </button>
                    </div></td>
                </tr>`;
                tbody.append(tr);
            });

            $('.btn-verify-mapping').on('click', function () {
                let id = $(this).data('id');
                $.ajax({
                    url: '/api/accounting/mappings/' + id + '/verify',
                    type: 'POST'
                }).done(function () {
                    SES.toast.success(t('mappingVerified'));
                    loadMappings(connectionId);
                }).fail(function () {
                    SES.toast.error(t('verifyFailed'));
                });
            });

            $('.btn-edit-mapping').on('click', function () {
                let id = $(this).data('id');
                let target = list.find(x => x.id === id);
                if (target) {
                    $('#modalMappingId').val(target.id);
                    $('#modalConnId').val(target.connectionId);
                    $('#modalObjectType').val(target.objectType);
                    $('#modalInternalCode').val(target.internalCode);
                    $('#modalExternalId').val(target.externalId);
                    $('#modalExternalCode').val(target.externalCode || '');
                    let modal = getMapModal();
                    if (modal) modal.show();
                }
            });
        });
    }

    // === ジョブ一覧 ===
    function loadJobs(page) {
        page = page || 1;
        let status = $('#jobStatusFilter').val();
        let jobType = $('#jobTypeFilter').val();
        let url = `/api/accounting/jobs?current=${page}&size=20` +
            (status ? `&status=${status}` : '') +
            (jobType ? `&jobType=${jobType}` : '');

        $.ajax({
            url: url,
            type: 'GET'
        }).done(function (res) {
            let pageData = res.data;
            let list = pageData ? pageData.records || [] : [];
            let tbody = $('#jobsTbody').empty();

            if (list.length === 0) {
                tbody.html(`<tr><td colspan="8" class="text-center text-muted py-4">${escapeHtml(t('noJobs'))}</td></tr>`);
                renderJobsPagination(pageData);
                return;
            }

            list.forEach(function (j) {
                let statusBadge = j.status === 'SUCCEEDED' ? '<span class="badge bg-success">SUCCEEDED</span>' :
                    j.status === 'RUNNING' ? '<span class="badge bg-info text-dark">RUNNING</span>' :
                        j.status === 'RETRYABLE' ? '<span class="badge bg-warning text-dark">RETRYABLE</span>' :
                            j.status === 'FAILED' ? '<span class="badge bg-danger">FAILED</span>' :
                                j.status === 'CANCELLED' ? '<span class="badge bg-secondary">CANCELLED</span>' :
                                    '<span class="badge bg-primary">PENDING</span>';

                let actionBtns = `<button class="btn btn-xs btn-outline-info btn-job-detail" data-id="${j.id}" title="${escapeHtml(t('btnDetail'))}" aria-label="${escapeHtml(t('btnDetail'))}"><i class="bi bi-info-circle" aria-hidden="true"></i></button>`;
                if (j.status === 'RETRYABLE' || j.status === 'FAILED') {
                    actionBtns += `<button class="btn btn-xs btn-outline-warning btn-job-retry" data-id="${j.id}" title="${escapeHtml(t('btnRetry'))}" aria-label="${escapeHtml(t('btnRetry'))}"><i class="bi bi-arrow-repeat" aria-hidden="true"></i></button>`;
                }
                if (j.status === 'PENDING' || j.status === 'RETRYABLE' || j.status === 'RUNNING') {
                    actionBtns += `<button class="btn btn-xs btn-outline-danger btn-job-cancel" data-id="${j.id}" title="${escapeHtml(t('btnCancel'))}" aria-label="${escapeHtml(t('btnCancel'))}"><i class="bi bi-x-circle" aria-hidden="true"></i></button>`;
                }

                let tr = `
                <tr>
                    <td class="font-monospace text-muted">#${j.id}</td>
                    <td><span class="badge bg-light text-dark border">${escapeHtml(j.jobType)}</span></td>
                    <td>${escapeHtml(j.targetType)} #${escapeHtml(String(j.targetId))}</td>
                    <td>${statusBadge}</td>
                    <td>${j.attemptCount || 0}</td>
                    <td class="font-monospace small">${escapeHtml(j.externalId || '-')}</td>
                    <td class="small text-muted">${escapeHtml(j.createdAt ? j.createdAt.replace('T', ' ') : '-')}</td>
                    <td><div class="d-flex flex-wrap justify-content-end align-items-center gap-1">${actionBtns}</div></td>
                </tr>`;
                tbody.append(tr);
            });

            renderJobsPagination(pageData);

            $('.btn-job-detail').on('click', function () {
                let id = $(this).data('id');
                showJobDetail(id);
            });

            $('.btn-job-retry').on('click', function () {
                let id = $(this).data('id');
                $.ajax({
                    url: '/api/accounting/jobs/' + id + '/retry',
                    type: 'POST'
                }).done(function () {
                    SES.toast.success(t('retryRequested'));
                    loadJobs(page);
                });
            });

            $('.btn-job-cancel').on('click', function () {
                let id = $(this).data('id');
                $.ajax({
                    url: '/api/accounting/jobs/' + id + '/cancel?reason=REASON_CLIENT_CANCEL',
                    type: 'POST'
                }).done(function () {
                    SES.toast.success(t('cancelRequested'));
                    loadJobs(page);
                });
            });
        });
    }

    function renderJobsPagination(p) {
        if (!p || p.pages <= 1) {
            $('#jobsPagination').empty();
            return;
        }
        let total = p.total || 0;
        let current = p.current || 1;
        let pages = p.pages || 1;

        let html = `
            <span class="small text-muted">${current} / ${pages} (${total})</span>
            <div>
                <button class="btn btn-xs btn-outline-secondary me-1" ${current <= 1 ? 'disabled' : ''} id="btnPrevJobs">&laquo;</button>
                <button class="btn btn-xs btn-outline-secondary" ${current >= pages ? 'disabled' : ''} id="btnNextJobs">&raquo;</button>
            </div>
        `;
        $('#jobsPagination').html(html);

        $('#btnPrevJobs').on('click', function () { loadJobs(current - 1); });
        $('#btnNextJobs').on('click', function () { loadJobs(current + 1); });
    }

    function showJobDetail(jobId) {
        $.ajax({
            url: '/api/accounting/jobs/' + jobId,
            type: 'GET'
        }).done(function (res) {
            let data = res.data;
            if (!data) return;

            let j = data.job;
            let events = data.events || [];

            let html = `
                <div class="row g-2 small mb-3">
                    <div class="col-md-6"><strong>ID:</strong> #${j.id}</div>
                    <div class="col-md-6"><strong>${escapeHtml(t('status'))}:</strong> ${escapeHtml(j.status)}</div>
                    <div class="col-md-6"><strong>${escapeHtml(t('jobType'))}:</strong> ${escapeHtml(j.jobType)}</div>
                    <div class="col-md-6"><strong>${escapeHtml(t('target'))}:</strong> ${escapeHtml(j.targetType)} #${escapeHtml(String(j.targetId))}</div>
                    <div class="col-md-6"><strong>${escapeHtml(t('externalId'))}:</strong> ${escapeHtml(j.externalId || '-')}</div>
                    <div class="col-md-6"><strong>${escapeHtml(t('attemptCount'))}:</strong> ${j.attemptCount || 0}</div>
                    <div class="col-md-12"><strong>${escapeHtml(t('errorCode'))}:</strong> <span class="text-danger">${escapeHtml(j.errorCode || '-')}</span></div>
                    <div class="col-md-12"><strong>${escapeHtml(t('safeMessage'))}:</strong> <span class="text-danger">${escapeHtml(j.errorMessageSafe || '-')}</span></div>
                </div>
            `;
            $('#jobDetailContent').html(html);

            let eventTbody = $('#jobEventsTbody').empty();
            if (events.length === 0) {
                eventTbody.html('<tr><td colspan="3" class="text-center text-muted">' + escapeHtml(t('noJobEvents')) + '</td></tr>');
            } else {
                events.forEach(function (e) {
                    let tr = `
                    <tr>
                        <td class="small text-muted">${escapeHtml(e.createdAt ? e.createdAt.replace('T', ' ') : '-')}</td>
                        <td><span class="badge bg-light text-dark border">${escapeHtml(e.fromStatus || 'INIT')} &rarr; ${escapeHtml(e.toStatus)}</span></td>
                        <td class="small">${escapeHtml(e.message || '-')}</td>
                    </tr>`;
                    eventTbody.append(tr);
                });
            }

            let modal = getJobModal();
            if (modal) modal.show();
        });
    }

    function renderPreview(data) {
        let container = $('#previewResultContainer').empty();
        if (!data) return;

        let detailsHtml = '';
        if (data.details && data.details.length > 0) {
            data.details.forEach(function (d) {
                detailsHtml += `
                <tr>
                    <td>${escapeHtml(d.description)}</td>
                    <td class="text-end font-monospace">&yen;${escapeHtml(Number(d.amount || 0).toLocaleString())}</td>
                    <td class="font-monospace">${escapeHtml(d.accountCode || '-')}</td>
                    <td class="font-monospace">${escapeHtml(d.taxCode || '-')}</td>
                </tr>`;
            });
        }

        let html = `
            <div class="card shadow-sm border-0">
                <div class="card-header bg-white fw-bold">
                    <i class="bi bi-file-earmark-text me-1"></i>${escapeHtml(t('previewHeader'))} (ID: ${escapeHtml(String(data.invoiceId || ''))}, No: ${escapeHtml(data.invoiceNo || '')})
                </div>
                <div class="card-body">
                    <div class="row g-2 small mb-3">
                        <div class="col-md-4"><strong>${escapeHtml(t('customerCode'))}:</strong> ${escapeHtml(data.customerCode)}</div>
                        <div class="col-md-4"><strong>${escapeHtml(t('customerName'))}:</strong> ${escapeHtml(data.customerName)}</div>
                        <div class="col-md-4"><strong>${escapeHtml(t('issueDate'))}:</strong> ${escapeHtml(data.issueDate || '-')}</div>
                        <div class="col-md-4"><strong>${escapeHtml(t('dueDate'))}:</strong> ${escapeHtml(data.dueDate || '-')}</div>
                        <div class="col-md-4"><strong>${escapeHtml(t('subtotal'))}:</strong> &yen;${escapeHtml(Number(data.subtotal || 0).toLocaleString())}</div>
                        <div class="col-md-4"><strong>${escapeHtml(t('total'))}:</strong> <span class="fs-6 fw-bold text-primary">&yen;${escapeHtml(Number(data.total || 0).toLocaleString())}</span></div>
                    </div>
                    <table class="table table-sm table-bordered mb-0">
                        <thead class="table-light">
                            <tr>
                                <th>${escapeHtml(t('description'))}</th>
                                <th class="text-end">${escapeHtml(t('amount'))}</th>
                                <th>${escapeHtml(t('accountCode'))}</th>
                                <th>${escapeHtml(t('taxCode'))}</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${detailsHtml}
                        </tbody>
                    </table>
                </div>
            </div>
        `;
        container.html(html);
    }

    // === 月次照合 ===
    function loadReconciliation() {
        let month = $('#reconciliationMonth').val();
        if (!month) return;

        $.ajax({
            url: '/api/accounting/reconciliation?month=' + month,
            type: 'GET'
        }).done(function (res) {
            let data = res.data;
            if (!data) return;

            $('#reconcileSummaryCards').show();
            $('#reconcileTableCard').show();

            $('#summaryMatchedCount').text(data.matchedCount);
            $('#summaryInternalOnlyCount').text(data.internalOnlyCount);
            $('#summaryExternalOnlyCount').text(data.externalOnlyCount);
            $('#summaryMismatchCount').text(data.amountMismatchCount);
            $('#summaryIgnoredCount').text(data.ignoredCount);

            if (data.readyForClosing) {
                $('#summaryClosingBadge').html(`<span class="badge bg-success fs-6">${escapeHtml(t('ready'))}</span>`);
            } else {
                $('#summaryClosingBadge').html(`<span class="badge bg-danger fs-6">${escapeHtml(t('notReady'))}</span>`);
            }

            let tbody = $('#reconcileTbody').empty();
            let items = data.items || [];

            if (items.length === 0) {
                tbody.html(`<tr><td colspan="8" class="text-center text-muted py-4">${escapeHtml(t('noReconcile'))}</td></tr>`);
                return;
            }

            items.forEach(function (it) {
                let statusBadge = it.status === 'MATCHED' ? `<span class="badge bg-success">${escapeHtml(t('matched'))}</span>` :
                    it.status === 'INTERNAL_ONLY' ? `<span class="badge bg-warning text-dark">${escapeHtml(t('internalOnly'))}</span>` :
                        it.status === 'EXTERNAL_ONLY' ? `<span class="badge bg-secondary">${escapeHtml(t('externalOnly'))}</span>` :
                            it.status === 'AMOUNT_MISMATCH' ? `<span class="badge bg-danger">${escapeHtml(t('mismatch'))}</span>` :
                                `<span class="badge bg-light text-dark border">${escapeHtml(t('ignored'))}</span>`;

                let actionBtn = '';
                if (it.status !== 'MATCHED' && it.status !== 'IGNORED') {
                    actionBtn = `<button class="btn btn-xs btn-outline-secondary btn-ignore-item"` +
                        ` data-month="${escapeHtml(month)}"` +
                        ` data-category="${escapeHtml(it.category)}"` +
                        ` data-ext-deal-id="${escapeHtml(it.externalDealId || '')}"` +
                        ` data-internal-id="${escapeHtml(String(it.internalId || ''))}"` +
                        ` data-label="${escapeHtml(it.internalNo || it.partnerName || it.externalDealId || '')}">` +
                        `${escapeHtml(t('btnIgnore'))}</button>`;
                }

                let tr = `
                <tr>
                    <td><span class="badge bg-light text-dark border">${escapeHtml(it.category)}</span></td>
                    <td><strong>${escapeHtml(it.internalNo || '-')}</strong><br><span class="small text-muted">${escapeHtml(it.partnerName || '-')}</span></td>
                    <td class="text-end">${it.internalAmount != null ? '&yen;' + escapeHtml(Number(it.internalAmount).toLocaleString()) : '-'}</td>
                    <td><code>${escapeHtml(it.externalDealId || '-')}</code><br><span class="small text-muted">${escapeHtml(it.externalRefNo || '-')}</span></td>
                    <td class="text-end">${it.externalAmount != null ? '&yen;' + escapeHtml(Number(it.externalAmount).toLocaleString()) : '-'}</td>
                    <td>${statusBadge}</td>
                    <td class="small text-danger">${escapeHtml(it.discrepancyReason || it.ignoreReason || '-')}</td>
                    <td>${actionBtn}</td>
                </tr>`;
                tbody.append(tr);
            });

            $('.btn-ignore-item').on('click', function () {
                let month = $(this).data('month');
                let cat = $(this).data('category');
                let extId = $(this).data('ext-deal-id');
                let intId = $(this).data('internal-id');
                let label = $(this).data('label');

                $('#ignoreMonth').val(month);
                $('#ignoreCategory').val(cat);
                $('#ignoreExternalDealId').val(extId);
                $('#ignoreInternalId').val(intId);
                $('#ignoreTargetLabel').val(label);
                $('#ignoreReasonInput').val('');

                let modal = getIgnoreModal();
                if (modal) modal.show();
            });
        });
    }

    function init() {
        // JST (Asia/Tokyo) 基準の年月初期設定
        let nowJst = new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Tokyo' }));
        let y = nowJst.getFullYear();
        let m = String(nowJst.getMonth() + 1).padStart(2, '0');
        $('#reconciliationMonth').val(`${y}-${m}`);

        // 初期ロード
        loadConnections();
        loadJobs(1);
        loadPreviewInvoiceOptions();

        // タブ切り替え時の自動ロード
        $('button[data-bs-toggle="tab"]').on('shown.bs.tab', function (e) {
            let target = $(e.target).attr('data-bs-target');
            if (target === '#mappings-panel') {
                let connId = currentConnId || $('#mappingConnSelect').val();
                if (connId) loadMappings(connId);
            } else if (target === '#connections-panel') {
                loadConnections();
            } else if (target === '#preview-panel') {
                loadPreviewInvoiceOptions();
            } else if (target === '#reconciliation-panel') {
                loadReconciliation();
            }
        });

        // イベントバインド
        $('#btnRefreshJobs').on('click', function () { loadJobs(1); });
        $('#jobStatusFilter, #jobTypeFilter').on('change', function () { loadJobs(1); });

        $('#mappingConnSelect, #mappingTypeFilter').on('change', function () {
            currentConnId = $('#mappingConnSelect').val();
            loadMappings(currentConnId);
        });

        $('#btnNewMapping').on('click', function () {
            if (!currentConnId) {
                SES.toast.warning(t('selectConn'));
                return;
            }
            $('#mappingForm')[0].reset();
            $('#modalConnId').val(currentConnId);
            let modal = getMapModal();
            if (modal) modal.show();
        });

        $('#btnSaveMapping').on('click', function () {
            let payload = {
                connectionId: parseInt($('#modalConnId').val()),
                objectType: $('#modalObjectType').val(),
                internalCode: $('#modalInternalCode').val().trim(),
                externalId: $('#modalExternalId').val().trim(),
                externalCode: $('#modalExternalCode').val().trim()
            };
            if (!payload.internalCode || !payload.externalId) {
                SES.toast.warning(t('keyAndIdRequired'));
                return;
            }
            $.ajax({
                url: '/api/accounting/mappings',
                type: 'POST',
                contentType: 'application/json',
                data: JSON.stringify(payload)
            }).done(function () {
                SES.toast.success(t('mappingSaved'));
                let modal = getMapModal();
                if (modal) modal.hide();
                loadMappings(currentConnId);
            });
        });

        $('#btnRunPreview, #btnPreviewInvoice').on('click', function () {
            let invoiceId = $('#previewInvoiceId').val();
            if (!invoiceId) {
                SES.toast.warning(t('inputInvoiceId'));
                return;
            }
            $.ajax({
                url: '/api/accounting/preview/sales-invoice/' + invoiceId,
                type: 'GET'
            }).done(function (res) {
                renderPreview(res.data);
            }).fail(function (err) {
                SES.toast.error(err.responseJSON ? err.responseJSON.message : t('loadError'));
            });
        });

        $('#btnRunReconciliation').on('click', function () {
            loadReconciliation();
        });

        $('#btnSaveIgnore').on('click', function () {
            let reason = $('#ignoreReasonInput').val().trim();
            if (!reason) {
                SES.toast.warning(t('inputIgnoreReason'));
                return;
            }

            let payload = {
                month: $('#ignoreMonth').val(),
                category: $('#ignoreCategory').val(),
                externalDealId: $('#ignoreExternalDealId').val(),
                internalId: $('#ignoreInternalId').val() ? parseInt($('#ignoreInternalId').val()) : null,
                reason: reason
            };

            $.ajax({
                url: '/api/accounting/reconciliation/ignore',
                type: 'POST',
                contentType: 'application/json',
                data: JSON.stringify(payload)
            }).done(function () {
                SES.toast.success(t('ignoreSaved'));
                let modal = getIgnoreModal();
                if (modal) modal.hide();
                loadReconciliation();
            });
        });
    }

    return {
        t: t,
        escapeHtml: escapeHtml,
        loadJobs: loadJobs,
        loadMappings: loadMappings,
        loadConnections: loadConnections,
        loadReconciliation: loadReconciliation,
        showJobDetail: showJobDetail,
        renderPreview: renderPreview,
        init: init
    };
})();

$(document).ready(function () {
    if (window.AccountingIntegration && window.AccountingIntegration.init) {
        window.AccountingIntegration.init();
    }
});
