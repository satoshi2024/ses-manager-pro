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

    /** 送信種別ごとのプレビューAPI・送信APIのパス片。 */
    const SEND_KINDS = {
        sales: { preview: 'sales-invoice', sync: 'sales-invoice' },
        purchase: { preview: 'bp-purchase', sync: 'bp-purchase' },
        expense: { preview: 'expense', sync: 'expense' }
    };

    /**
     * 連携ジョブの安全メッセージはmessage key（SafeErrorPolicyのallow-list）で保存される。
     * keyなら翻訳し、固定文言ならそのまま表示する。
     */
    function localizeJobMessage(message) {
        if (!message) return '-';
        return (window.SES && SES.i18n) ? SES.i18n.t(message, message) : message;
    }

    function previewKind() {
        let kind = $('#previewKind').val();
        return SEND_KINDS[kind] ? kind : 'sales';
    }

    function formatYen(value) {
        return '¥' + Number(value || 0).toLocaleString();
    }

    /** 送信プレビューの対象候補。売上は請求書一覧API、BP仕入・要員経費は送信候補API（管理者のみ）。 */
    function loadPreviewTargets() {
        let sel = $('#previewTargetId');
        if (!sel.length) return;
        let kind = previewKind();
        let current = sel.val();
        let placeholder = `<option value="">${escapeHtml(t('selectTarget'))}</option>`;
        let request = kind === 'sales'
            ? { url: '/api/invoices', data: { current: 1, size: 1000 } }
            : { url: '/api/accounting/send-candidates', data: { kind: kind } };
        $.ajax({ url: request.url, type: 'GET', data: request.data }).done(function (res) {
            if (!res || res.code !== 200 || kind !== previewKind()) return;
            let rows = kind === 'sales' ? ((res.data && res.data.records) || []) : (res.data || []);
            let options = rows.map(function (row) {
                let label;
                if (kind === 'sales') {
                    label = (row.invoiceNo || ('#' + row.id)) + (row.billingMonth ? ' / ' + row.billingMonth : '')
                        + (row.status ? ' / ' + row.status : '');
                } else {
                    label = [row.no, row.name, row.date, row.amount != null ? formatYen(row.amount) : null]
                        .filter(function (v) { return v; }).join(' / ');
                }
                return `<option value="${escapeHtml(String(row.id))}">${escapeHtml(label)}</option>`;
            }).join('');
            sel.html(placeholder + options);
            if (current && sel.find(`option[value="${current}"]`).length) sel.val(current);
        });
        // 失敗時の文言はcommon.jsの共通ハンドラが表示する
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
                            ${connectionActions(c)}
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

    /** 管理者だけが接続・再接続できる（/integrations/freee は管理者限定）。 */
    function canManage() {
        return $('#accountingCanManage').length > 0;
    }

    /** 接続カードの操作ボタン。freee接続の認可は給与と同じOAuth callbackを target=accounting で使う。 */
    function connectionActions(c) {
        let html = '';
        let isFreee = String(c.provider || '').toLowerCase() === 'freee';
        if (isFreee && canManage()) {
            let hasAuthorized = c.status === 'CONNECTED' || c.status === 'REAUTH_REQUIRED';
            let label = hasAuthorized ? t('btnFreeeReconnect') : t('btnFreeeConnect');
            let btnClass = c.status === 'CONNECTED' ? 'btn-outline-primary' : 'btn-primary';
            html += `<a class="btn btn-sm ${btnClass} me-2" href="/integrations/freee/authorize?target=accounting&connectionId=${encodeURIComponent(String(c.id))}">`
                + `<i class="bi bi-box-arrow-up-right me-1"></i>${escapeHtml(label)}</a>`;
        }
        if (c.status === 'CONNECTED') {
            html += `<button type="button" class="btn btn-sm btn-outline-secondary" data-health-conn-id="${escapeHtml(String(c.id))}">`
                + `<i class="bi bi-activity me-1"></i>${escapeHtml(t('btnConnectionTest'))}</button>`;
        }
        return html ? `<div class="d-flex flex-wrap gap-1">${html}</div>` : '';
    }

    function testConnection(connectionId, $btn) {
        $btn.prop('disabled', true);
        $.ajax({
            url: '/api/accounting/connections/' + encodeURIComponent(connectionId) + '/health',
            type: 'GET'
        }).done(function (res) {
            if (res && res.code === 200 && res.data === true) {
                SES.toast.success(t('connectionHealthy'));
            } else {
                SES.toast.error(t('connectionUnhealthy'));
            }
        }).fail(function () {
            SES.toast.error(t('connectionUnhealthy'));
        }).always(function () {
            $btn.prop('disabled', false);
        });
    }

    /** OAuth callbackのredirect（?freee_connected=1 / ?freee_error=<kind>&reason=<message key>）を表示する。 */
    function showOAuthResult() {
        let params = new URLSearchParams(window.location.search);
        if (!params.has('freee_connected') && !params.has('freee_error')) {
            return;
        }
        let tabEl = document.getElementById('connections-tab');
        if (tabEl && window.bootstrap) {
            bootstrap.Tab.getOrCreateInstance(tabEl).show();
        }
        if (params.get('freee_connected') === '1') {
            SES.toast.success(t('freeeConnected'));
        } else {
            let kind = params.get('freee_error');
            let reason = params.get('reason');
            let text;
            if (reason && /^error\.(payroll|accounting)\.[A-Za-z]{1,60}$/.test(reason)) {
                text = SES.i18n.t(reason);
            } else if (kind === 'denied') {
                text = t('freeeDenied');
            } else if (kind === 'state') {
                text = t('freeeState');
            } else {
                text = SES.i18n.t('error.payroll.oauthFailed');
            }
            Swal.fire({ icon: 'error', title: t('freeeConnectFailed'), text: text });
        }
        window.history.replaceState(null, '', window.location.pathname);
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
                    // 失敗理由はcommon.jsの共通ハンドラがサーバー文言で表示する。
                    // 照合失敗時はサーバー側で未検証へ戻しているため、一覧を再取得して表示を合わせる。
                    loadMappings(connectionId);
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
                // 送信済みfreee取引への操作（管理者のみ）。取消対象の妥当性（最新の送信か）はサーバーで確認する
                if (canManage() && j.status === 'SUCCEEDED' && j.externalId) {
                    if (j.jobType === 'SALES_INVOICE_SYNC') {
                        actionBtns += `<button class="btn btn-xs btn-outline-danger btn-cancel-deal" data-id="${j.id}" data-deal-id="${escapeHtml(j.externalId)}" title="${escapeHtml(t('btnCancelDeal'))}" aria-label="${escapeHtml(t('btnCancelDeal'))}"><i class="bi bi-trash3" aria-hidden="true"></i></button>`;
                    } else if (j.jobType === 'BP_PURCHASE_SYNC' || j.jobType === 'PURCHASE_DEAL_SYNC') {
                        actionBtns += `<button class="btn btn-xs btn-outline-success btn-payment-sync" data-target-id="${j.targetId}" title="${escapeHtml(t('btnPaymentSync'))}" aria-label="${escapeHtml(t('btnPaymentSync'))}"><i class="bi bi-cash-coin" aria-hidden="true"></i></button>`;
                    }
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

            $('.btn-cancel-deal').on('click', function () {
                cancelSyncedDeal($(this).data('id'), String($(this).attr('data-deal-id')), page);
            });

            $('.btn-payment-sync').on('click', function () {
                syncBpPayment($(this).data('target-id'), page);
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
                    <div class="col-md-12"><strong>${escapeHtml(t('safeMessage'))}:</strong> <span class="text-danger">${escapeHtml(localizeJobMessage(j.errorMessageSafe))}</span></div>
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
                        <td class="small">${escapeHtml(localizeJobMessage(e.message))}</td>
                    </tr>`;
                    eventTbody.append(tr);
                });
            }

            let modal = getJobModal();
            if (modal) modal.show();
        });
    }

    /** 種別ごとの標準DTOを、見出し・項目・明細行へ揃える。 */
    function previewSummary(kind, p) {
        if (kind === 'purchase') {
            return {
                title: 'BP-PAY-' + (p.bpPaymentId || ''),
                fields: [
                    [t('partnerName'), p.bpCompanyName],
                    [t('internalCode'), p.bpCompanyCode],
                    [t('partnerId'), p.partnerExternalId || '-'],
                    [t('issueDate'), p.issueDate || '-'],
                    [t('dueDate'), p.dueDate || '-'],
                    [t('total'), formatYen(p.amount), true]
                ],
                lines: [{ description: p.remarks, amount: p.amount, account: p.accountItemCode, tax: p.taxCode }]
            };
        }
        if (kind === 'expense') {
            return {
                title: p.expenseNo || ('EX-' + (p.expenseId || '')),
                fields: [
                    [t('category'), p.category || '-'],
                    [t('partnerId'), t('partnerNone')],
                    [t('issueDate'), p.expenseDate || '-'],
                    [t('total'), formatYen(p.amount), true]
                ],
                lines: [{ description: p.description || p.category, amount: p.amount, account: p.accountItemCode, tax: p.taxCode }]
            };
        }
        return {
            title: p.invoiceNo || ('#' + (p.invoiceId || '')),
            fields: [
                [t('customerName'), p.customerName],
                [t('customerCode'), p.customerCode],
                [t('partnerId'), p.partnerExternalId || '-'],
                [t('issueDate'), p.issueDate || '-'],
                [t('dueDate'), p.dueDate || '-'],
                [t('subtotal'), formatYen(p.subtotal)],
                [t('total'), formatYen(p.total), true]
            ],
            lines: (p.details || []).map(function (d) {
                return { description: d.description, amount: d.amount, account: d.accountItemCode, tax: d.taxCode };
            })
        };
    }

    function checkBadge(status) {
        if (status === 'OK') return `<span class="badge bg-success">${escapeHtml(t('checkOk'))}</span>`;
        if (status === 'UNVERIFIED') return `<span class="badge bg-warning text-dark">${escapeHtml(t('checkUnverified'))}</span>`;
        return `<span class="badge bg-danger">${escapeHtml(t('checkMissing'))}</span>`;
    }

    /**
     * 送信プレビューを描画する。dto は AccountingSendPreviewDto（payload / mappingChecks / connectionReady / sendable）。
     * 送信ボタンは管理者のみ。必要マッピングが揃わない・未接続のときは押せない（送信時のサーバーガードと同じ条件）。
     */
    function renderPreview(dto, kind, targetId) {
        let container = $('#previewResultContainer').empty();
        if (!dto || !dto.payload) {
            container.hide();
            return;
        }
        kind = SEND_KINDS[kind] ? kind : 'sales';
        let summary = previewSummary(kind, dto.payload);
        summary.fields.push([t('legalEntity'), dto.legalEntityId != null ? String(dto.legalEntityId) : t('legalEntityUnset')]);

        let fieldsHtml = summary.fields.map(function (f) {
            let value = f[2]
                ? `<span class="fs-6 fw-bold text-primary">${escapeHtml(f[1])}</span>`
                : escapeHtml(f[1] == null || f[1] === '' ? '-' : f[1]);
            return `<div class="col-md-4"><strong>${escapeHtml(f[0])}:</strong> ${value}</div>`;
        }).join('');

        let linesHtml = summary.lines.map(function (d) {
            return `<tr>
                <td>${escapeHtml(d.description || '-')}</td>
                <td class="text-end font-monospace">${escapeHtml(formatYen(d.amount))}</td>
                <td class="font-monospace">${escapeHtml(d.account || '-')}</td>
                <td class="font-monospace">${escapeHtml(d.tax || '-')}</td>
            </tr>`;
        }).join('');

        let checksHtml = (dto.mappingChecks || []).map(function (c) {
            return `<tr>
                <td class="font-monospace">${escapeHtml(c.objectType)}</td>
                <td class="font-monospace">${escapeHtml(c.internalCode || '-')}</td>
                <td class="font-monospace">${escapeHtml(c.externalId || '-')}</td>
                <td>${checkBadge(c.status)}</td>
            </tr>`;
        }).join('');

        // 送信できない理由はすべて並べる（1つ直しても次の理由で止まる、を避ける）
        let blockers = [];
        if (!dto.connectionReady) blockers.push(t('previewNotConnected'));
        if (dto.legalEntityStatus === 'MISSING') blockers.push(t('legalEntityMissing'));
        if (dto.legalEntityStatus === 'MISMATCH') blockers.push(t('legalEntityMismatch'));
        if ((dto.mappingChecks || []).some(function (c) { return c.status !== 'OK'; })) blockers.push(t('previewBlocked'));
        let alertHtml = blockers.map(function (msg) {
            return `<div class="alert alert-warning small py-2 mb-2">${escapeHtml(msg)}</div>`;
        }).join('');

        let sendHtml = canManage()
            ? `<button type="button" id="btnSendToFreee" class="btn btn-sm btn-success"
                       data-kind="${escapeHtml(kind)}" data-target-id="${escapeHtml(String(targetId))}"
                       ${dto.sendable ? '' : 'disabled'}>
                   <i class="bi bi-cloud-upload me-1"></i>${escapeHtml(t('btnSendToFreee'))}
               </button>`
            : '';

        container.html(`
            <div class="card shadow-sm border-0">
                <div class="card-header bg-white fw-bold d-flex flex-wrap justify-content-between align-items-center gap-2">
                    <span><i class="bi bi-file-earmark-text me-1"></i>${escapeHtml(t('previewHeader'))}: ${escapeHtml(summary.title)}</span>
                    ${sendHtml}
                </div>
                <div class="card-body">
                    ${alertHtml}
                    <div class="row g-2 small mb-3">${fieldsHtml}</div>
                    <div class="table-responsive mb-3">
                        <table class="table table-sm table-bordered mb-0">
                            <thead class="table-light">
                                <tr>
                                    <th>${escapeHtml(t('description'))}</th>
                                    <th class="text-end">${escapeHtml(t('amount'))}</th>
                                    <th>${escapeHtml(t('accountCode'))}</th>
                                    <th>${escapeHtml(t('taxCode'))}</th>
                                </tr>
                            </thead>
                            <tbody>${linesHtml}</tbody>
                        </table>
                    </div>
                    <div class="small fw-bold mb-1">${escapeHtml(t('mappingChecks'))}</div>
                    <div class="table-responsive">
                        <table class="table table-sm table-bordered mb-0">
                            <thead class="table-light">
                                <tr>
                                    <th>${escapeHtml(t('objectType'))}</th>
                                    <th>${escapeHtml(t('internalCode'))}</th>
                                    <th>${escapeHtml(t('externalIdLabel'))}</th>
                                    <th>${escapeHtml(t('status'))}</th>
                                </tr>
                            </thead>
                            <tbody>${checksHtml}</tbody>
                        </table>
                    </div>
                </div>
            </div>
        `).show();
    }

    /** 取消理由（機械可読コード。サーバー側の allow-list と同じ）と表示用i18nキー。 */
    const CANCEL_REASONS = {
        REASON_AMOUNT_CORRECTION: 'cancelReasonAmountCorrection',
        REASON_CLIENT_CANCEL: 'cancelReasonClientCancel',
        REASON_DUPLICATE: 'cancelReasonDuplicate',
        REASON_DISPUTE: 'cancelReasonDispute',
        REASON_OTHER: 'cancelReasonOther'
    };

    /** freee売上取引の取消（理由を選んで取消ジョブを登録する。freeeからの削除はWorkerが行う）。 */
    function cancelSyncedDeal(jobId, dealId, page) {
        let options = {};
        Object.keys(CANCEL_REASONS).forEach(function (code) { options[code] = t(CANCEL_REASONS[code]); });
        Swal.fire({
            title: t('cancelDealConfirmTitle'),
            text: t('cancelDealConfirmText') + ' (dealId: ' + dealId + ')',
            icon: 'warning',
            input: 'select',
            inputOptions: options,
            inputValue: 'REASON_AMOUNT_CORRECTION',
            showCancelButton: true,
            confirmButtonText: t('btnCancelDeal'),
            cancelButtonText: t('btnCancelLabel')
        }).then(function (result) {
            if (!result.isConfirmed) return;
            $.ajax({
                url: '/api/accounting/jobs/' + encodeURIComponent(jobId) + '/cancel-deal?reason=' + encodeURIComponent(result.value),
                type: 'POST'
            }).done(function (res) {
                if (res && res.code === 200) {
                    SES.toast.success(t('cancelDealQueued'));
                    loadJobs(page);
                } else if (res && res.message) {
                    SES.toast.error(res.message);
                }
            });
            // HTTPエラー時の文言はcommon.jsの共通ハンドラが表示する
        });
    }

    /** BP支払の支払実績同期（freeeで決済済みの仕入取引の支払額・支払日をSESへ反映するジョブを登録する）。 */
    function syncBpPayment(bpPaymentId, page) {
        Swal.fire({
            title: t('paymentSyncConfirmTitle'),
            text: t('paymentSyncConfirmText'),
            icon: 'question',
            showCancelButton: true,
            confirmButtonText: t('btnPaymentSync'),
            cancelButtonText: t('btnCancelLabel')
        }).then(function (result) {
            if (!result.isConfirmed) return;
            $.ajax({
                url: '/api/accounting/sync/payment/' + encodeURIComponent(bpPaymentId),
                type: 'POST'
            }).done(function (res) {
                if (res && res.code === 200) {
                    SES.toast.success(t('paymentSyncQueued'));
                    loadJobs(page);
                } else if (res && res.message) {
                    SES.toast.error(res.message);
                }
            });
        });
    }

    /** 送信ジョブを登録する（実際のfreee送信はWorkerが非同期に行う）。 */
    function sendToFreee(kind, targetId, $btn) {
        if (!SEND_KINDS[kind] || !targetId) return;
        Swal.fire({
            title: t('sendConfirmTitle'),
            text: t('sendConfirmText'),
            icon: 'question',
            showCancelButton: true,
            confirmButtonText: t('btnSendToFreee'),
            cancelButtonText: t('btnCancelLabel')
        }).then(function (result) {
            if (!result.isConfirmed) return;
            $btn.prop('disabled', true);
            $.ajax({
                url: '/api/accounting/sync/' + SEND_KINDS[kind].sync + '/' + encodeURIComponent(targetId),
                type: 'POST'
            }).done(function (res) {
                if (res && res.code === 200) {
                    SES.toast.success(t('sendQueued'));
                    loadJobs(1);
                } else {
                    $btn.prop('disabled', false);
                }
            }).fail(function () {
                // 失敗理由（ステータス不適格・月次締め・マッピング未照合など）は共通ハンドラが表示する
                $btn.prop('disabled', false);
            });
        });
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
        loadPreviewTargets();
        showOAuthResult();

        $('#previewKind').on('change', function () {
            $('#previewResultContainer').empty().hide();
            $('#previewTargetId').val('');
            loadPreviewTargets();
        });

        $('#previewResultContainer').on('click', '#btnSendToFreee', function () {
            let $btn = $(this);
            sendToFreee($btn.attr('data-kind'), $btn.attr('data-target-id'), $btn);
        });

        $('#connectionsContainer').on('click', 'button[data-health-conn-id]', function () {
            testConnection($(this).attr('data-health-conn-id'), $(this));
        });

        // タブ切り替え時の自動ロード
        $('button[data-bs-toggle="tab"]').on('shown.bs.tab', function (e) {
            let target = $(e.target).attr('data-bs-target');
            if (target === '#mappings-panel') {
                let connId = currentConnId || $('#mappingConnSelect').val();
                if (connId) loadMappings(connId);
            } else if (target === '#connections-panel') {
                loadConnections();
            } else if (target === '#preview-panel') {
                loadPreviewTargets();
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
            $('#modalObjectType').trigger('change');
            let modal = getMapModal();
            if (modal) modal.show();
        });

        // 送信ガードが参照する内部キーは種別ごとに固定。空欄または既定値のときだけ補完する。
        const FIXED_INTERNAL_CODES = {
            CUSTOMER_PARTNER: 'CUST-', BP_PARTNER: 'BP-',
            ACCOUNT_SALES: 'SALES_DEFAULT', ACCOUNT_PURCHASE: 'PURCHASE_DEFAULT', ACCOUNT_EXPENSE: 'EXPENSE_DEFAULT',
            TAX_SALES_10: 'TAX_10', TAX_SALES_8: 'TAX_8', TAX_SALES_0: 'TAX_0', TAX_PURCHASE_10: 'TAX_PURCHASE_10'
        };
        $('#modalObjectType').on('change', function () {
            let $code = $('#modalInternalCode');
            let current = $code.val().trim();
            let isDefault = !current || Object.values(FIXED_INTERNAL_CODES).indexOf(current) !== -1;
            let next = FIXED_INTERNAL_CODES[$(this).val()];
            if (isDefault && next !== undefined) $code.val(next);
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
            }).done(function (res) {
                // 一部のエラーはHTTP 200 + code!=200 で返るため、codeで判定する
                if (!res || res.code !== 200) {
                    SES.toast.error((res && res.message) || t('loadError'));
                    return;
                }
                SES.toast.success(t('mappingSaved'));
                let modal = getMapModal();
                if (modal) modal.hide();
                loadMappings(currentConnId);
            });
        });

        $('#btnRunPreview').on('click', function () {
            let kind = previewKind();
            let targetId = $('#previewTargetId').val();
            if (!targetId) {
                SES.toast.warning(t('selectTarget'));
                return;
            }
            $.ajax({
                url: '/api/accounting/preview/' + SEND_KINDS[kind].preview + '/' + encodeURIComponent(targetId),
                type: 'GET'
            }).done(function (res) {
                if (!res || res.code !== 200) {
                    $('#previewResultContainer').empty().hide();
                    SES.toast.error((res && res.message) || t('loadError'));
                    return;
                }
                renderPreview(res.data, kind, targetId);
            });
            // HTTPエラー時の文言はcommon.jsの共通ハンドラが表示する
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
