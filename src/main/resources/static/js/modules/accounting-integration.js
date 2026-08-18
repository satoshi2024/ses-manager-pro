/**
 * 会計・支払連携モジュール (accounting-integration.js / A1)
 */
$(function () {
    let currentConnId = null;
    let jobModal = new bootstrap.Modal(document.getElementById('jobDetailModal'));
    let mapModal = new bootstrap.Modal(document.getElementById('mappingModal'));

    // 初期ロード
    loadConnections();
    loadJobs(1);

    // イベントバインド
    $('#btnRefreshJobs').on('click', function () { loadJobs(1); });
    $('#jobStatusFilter, #jobTypeFilter').on('change', function () { loadJobs(1); });

    $('#mappingConnSelect, #mappingTypeFilter').on('change', function () {
        currentConnId = $('#mappingConnSelect').val();
        loadMappings(currentConnId);
    });

    $('#btnNewMapping').on('click', function () {
        if (!currentConnId) {
            SES.toast.warning('接続を選択してください');
            return;
        }
        $('#mappingForm')[0].reset();
        $('#modalConnId').val(currentConnId);
        mapModal.show();
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
            SES.toast.warning('内部キーと外部システムIDは必須です');
            return;
        }
        SES.api.post('/api/accounting/mappings', JSON.stringify(payload)).done(function () {
            SES.toast.success('マッピングを保存しました');
            mapModal.hide();
            loadMappings(currentConnId);
        });
    });

    $('#btnRunPreview').on('click', function () {
        let invoiceId = $('#previewInvoiceId').val();
        if (!invoiceId) {
            SES.toast.warning('請求書IDを入力してください');
            return;
        }
        SES.api.get('/api/accounting/preview/sales/' + invoiceId).done(function (res) {
            renderPreviewResult(res.data);
        });
    });

    // === 接続設定 ===
    function loadConnections() {
        SES.api.get('/api/accounting/connections').done(function (res) {
            let list = res.data || [];
            let container = $('#connectionsContainer').empty();
            let select = $('#mappingConnSelect').empty();

            if (list.length === 0) {
                container.html('<div class="col-12 text-center text-muted py-4">接続設定が存在しません</div>');
                return;
            }

            list.forEach(function (c, idx) {
                select.append(`<option value="${c.id}">${c.provider} (${c.product}) - ${c.companyName || '未接続'}</option>`);
                if (idx === 0) currentConnId = c.id;

                let statusBadge = c.status === 'CONNECTED' ? '<span class="badge bg-success">接続中</span>' :
                    c.status === 'REAUTH_REQUIRED' ? '<span class="badge bg-warning text-dark">要再認証</span>' :
                        '<span class="badge bg-secondary">未接続</span>';

                let cardHtml = `
                <div class="col-md-6">
                    <div class="card shadow-sm border-0 h-100">
                        <div class="card-body">
                            <div class="d-flex justify-content-between align-items-center mb-2">
                                <h6 class="card-title fw-bold mb-0 text-primary">
                                    <i class="bi bi-cloud-check me-1"></i>${c.provider.toUpperCase()} (${c.product})
                                </h6>
                                ${statusBadge}
                            </div>
                            <div class="small text-muted mb-2">事業所ID: ${c.externalCompanyId || '-'}</div>
                            <div class="small text-muted mb-3">事業所名: ${c.companyName || '-'}</div>
                            <div class="d-flex gap-2">
                                <button class="btn btn-sm btn-outline-primary btn-check-health" data-id="${c.id}">
                                    <i class="bi bi-activity me-1"></i>疎通確認
                                </button>
                            </div>
                        </div>
                    </div>
                </div>`;
                container.append(cardHtml);
            });

            $('.btn-check-health').on('click', function () {
                let id = $(this).data('id');
                SES.api.get(`/api/accounting/connections/${id}/health`).done(function (res) {
                    if (res.data) {
                        SES.toast.success('疎通確認に成功しました (CONNECTED)');
                    } else {
                        SES.toast.warning('接続が無効または未認可です');
                    }
                    loadConnections();
                });
            });

            if (currentConnId) {
                loadMappings(currentConnId);
            }
        });
    }

    // === マッピング一覧 ===
    function loadMappings(connId) {
        if (!connId) return;
        let objectType = $('#mappingTypeFilter').val();
        let url = `/api/accounting/mappings?connectionId=${connId}` + (objectType ? `&objectType=${objectType}` : '');

        SES.api.get(url).done(function (res) {
            let list = res.data || [];
            let tbody = $('#mappingsTbody').empty();
            if (list.length === 0) {
                tbody.html('<tr><td colspan="6" class="text-center text-muted py-4">マッピングが登録されていません</td></tr>');
                return;
            }
            list.forEach(function (m) {
                let verifyBadge = m.verifiedAt ?
                    `<span class="badge bg-success-subtle text-success border border-success"><i class="bi bi-check-circle me-1"></i>検証済 (${m.verifiedAt.substring(0, 10)})</span>` :
                    `<span class="badge bg-danger-subtle text-danger border border-danger"><i class="bi bi-exclamation-triangle me-1"></i>未検証</span>`;

                let tr = `
                <tr>
                    <td><span class="badge bg-light text-dark border">${m.objectType}</span></td>
                    <td class="fw-bold">${m.internalCode}</td>
                    <td><code>${m.externalId}</code></td>
                    <td>${m.externalCode || '-'}</td>
                    <td>${verifyBadge}</td>
                    <td>
                        <button class="btn btn-sm btn-outline-success btn-verify-map" data-id="${m.id}">
                            <i class="bi bi-shield-check me-1"></i>照合検証
                        </button>
                    </td>
                </tr>`;
                tbody.append(tr);
            });

            $('.btn-verify-map').on('click', function () {
                let id = $(this).data('id');
                SES.api.post(`/api/accounting/mappings/${id}/verify`, JSON.stringify({ verified: true })).done(function () {
                    SES.toast.success('マッピングを検証済みに更新しました');
                    loadMappings(connId);
                });
            });
        });
    }

    // === ジョブ一覧 ===
    function loadJobs(page) {
        let status = $('#jobStatusFilter').val();
        let jobType = $('#jobTypeFilter').val();
        let url = `/api/accounting/jobs?current=${page}&size=15` +
            (status ? `&status=${status}` : '') +
            (jobType ? `&jobType=${jobType}` : '');

        SES.api.get(url).done(function (res) {
            let data = res.data;
            let records = data.records || [];
            let tbody = $('#jobsTbody').empty();

            if (records.length === 0) {
                tbody.html('<tr><td colspan="8" class="text-center text-muted py-4">対象ジョブが存在しません</td></tr>');
                $('#jobsPagination').empty();
                return;
            }

            records.forEach(function (j) {
                let statusBadge =
                    j.status === 'SUCCEEDED' ? '<span class="badge bg-success">SUCCEEDED</span>' :
                    j.status === 'RUNNING' ? '<span class="badge bg-primary">RUNNING</span>' :
                    j.status === 'PENDING' ? '<span class="badge bg-secondary">PENDING</span>' :
                    j.status === 'RETRYABLE' ? '<span class="badge bg-warning text-dark">RETRYABLE</span>' :
                    j.status === 'FAILED' ? '<span class="badge bg-danger">FAILED</span>' :
                    '<span class="badge bg-dark">CANCELLED</span>';

                let tr = `
                <tr>
                    <td>#${j.id}</td>
                    <td class="small fw-bold">${j.jobType}</td>
                    <td><span class="badge bg-light text-dark border">${j.targetType} #${j.targetId}</span></td>
                    <td>${statusBadge}</td>
                    <td>${j.attemptCount} / ${j.maxAttempts}</td>
                    <td><code>${j.externalId || '-'}</code></td>
                    <td class="small text-muted">${j.createdAt ? j.createdAt.substring(0, 16) : '-'}</td>
                    <td>
                        <button class="btn btn-sm btn-outline-secondary btn-job-detail" data-id="${j.id}">
                            <i class="bi bi-info-circle me-1"></i>詳細
                        </button>
                    </td>
                </tr>`;
                tbody.append(tr);
            });

            $('.btn-job-detail').on('click', function () {
                let id = $(this).data('id');
                showJobDetail(id);
            });

            renderPagination(data);
        });
    }

    function showJobDetail(jobId) {
        SES.api.get('/api/accounting/jobs/' + jobId).done(function (res) {
            let j = res.data.job;
            let events = res.data.events || [];

            let html = `
            <div class="row g-2 mb-3">
                <div class="col-sm-6"><strong>ジョブID:</strong> #${j.id}</div>
                <div class="col-sm-6"><strong>種別:</strong> ${j.jobType}</div>
                <div class="col-sm-6"><strong>対象:</strong> ${j.targetType} #${j.targetId}</div>
                <div class="col-sm-6"><strong>ステータス:</strong> <span class="badge bg-primary">${j.status}</span></div>
                <div class="col-sm-6"><strong>冪等キー:</strong> <code>${j.idempotencyKey}</code></div>
                <div class="col-sm-6"><strong>外部取引ID:</strong> <code>${j.externalId || '-'}</code></div>
                <div class="col-sm-6"><strong>Request ID:</strong> <code>${j.providerRequestId || '-'}</code></div>
                <div class="col-sm-6"><strong>試行回数:</strong> ${j.attemptCount} / ${j.maxAttempts}</div>
                <div class="col-12"><strong>エラー分類:</strong> <span class="text-danger">${j.errorCode ? '[' + j.errorCode + '] ' + (j.errorMessageSafe || '') : '-'}</span></div>
            </div>`;
            $('#jobDetailInfo').html(html);

            let eventRows = '';
            events.forEach(function (e) {
                eventRows += `
                <tr>
                    <td class="small text-muted">${e.occurredAt ? e.occurredAt.substring(0, 19) : '-'}</td>
                    <td><span class="badge bg-light text-dark border">${e.fromStatus || 'INIT'}</span> &rarr; <span class="badge bg-secondary">${e.toStatus}</span></td>
                    <td class="small">${e.safeDetail || '-'}</td>
                </tr>`;
            });
            $('#jobEventsTbody').html(eventRows || '<tr><td colspan="3" class="text-center text-muted">イベントなし</td></tr>');

            if (j.status === 'FAILED' || j.status === 'RETRYABLE') {
                $('#btnModalRetry').show().off('click').on('click', function () {
                    SES.api.post(`/api/accounting/jobs/${j.id}/retry`).done(function () {
                        SES.toast.success('ジョブを再試行待ちに戻しました');
                        jobModal.hide();
                        loadJobs(1);
                    });
                });
            } else {
                $('#btnModalRetry').hide();
            }

            if (j.status === 'PENDING' || j.status === 'RETRYABLE') {
                $('#btnModalCancel').show().off('click').on('click', function () {
                    SES.api.post(`/api/accounting/jobs/${j.id}/cancel`).done(function () {
                        SES.toast.success('ジョブをキャンセルしました');
                        jobModal.hide();
                        loadJobs(1);
                    });
                });
            } else {
                $('#btnModalCancel').hide();
            }

            jobModal.show();
        });
    }

    function renderPreviewResult(p) {
        let container = $('#previewResultContainer').show();
        let inv = p.canonicalInvoice;

        let errorAlert = '';
        if (!p.readyToSend) {
            let errorList = (p.validationErrors || []).map(e => `<li>${e}</li>`).join('');
            errorAlert = `
            <div class="alert alert-danger shadow-sm">
                <h6 class="alert-heading fw-bold mb-1"><i class="bi bi-x-circle me-1"></i>送信バリデーションエラー (送信不可)</h6>
                <ul class="mb-0 ps-3 small">${errorList}</ul>
            </div>`;
        } else {
            errorAlert = `
            <div class="alert alert-success shadow-sm">
                <i class="bi bi-check-circle me-1"></i>マッピング検証OK: freee API または CSV への送信準備が完了しています。
            </div>`;
        }

        let html = `
        ${errorAlert}
        <div class="card shadow-sm border-0">
            <div class="card-header bg-white fw-bold">Canonical 請求書データ</div>
            <div class="card-body">
                <div class="row g-2 small">
                    <div class="col-sm-4"><strong>請求書番号:</strong> ${inv.invoiceNo}</div>
                    <div class="col-sm-4"><strong>顧客名 (コード):</strong> ${inv.customerName} (${inv.customerCode})</div>
                    <div class="col-sm-4"><strong>発行日 / 期日:</strong> ${inv.issueDate} / ${inv.dueDate || '-'}</div>
                    <div class="col-sm-4"><strong>小計:</strong> ¥${inv.subtotal ? inv.subtotal.toLocaleString() : 0}</div>
                    <div class="col-sm-4"><strong>消費税 (10%):</strong> ¥${inv.tax ? inv.tax.toLocaleString() : 0}</div>
                    <div class="col-sm-4"><strong class="text-primary">合計金額:</strong> ¥${inv.total ? inv.total.toLocaleString() : 0}</div>
                </div>
            </div>
        </div>`;
        container.html(html);
    }

    // === 5. 月次照合 (Reconciliation) ===
    let ignoreModal = new bootstrap.Modal(document.getElementById('ignoreModal'));

    // 今月を初期値にセット
    let now = new Date();
    let currentYm = now.toISOString().substring(0, 7);
    $('#reconciliationMonth').val(currentYm);

    $('#btnRunReconciliation').on('click', function () {
        let month = $('#reconciliationMonth').val();
        if (!month) {
            SES.toast.warning('対象年月を選択してください');
            return;
        }
        loadReconciliation(month);
    });

    function loadReconciliation(month) {
        SES.api.get('/api/accounting/reconciliation?month=' + month).done(function (res) {
            renderReconciliation(res.data);
        });
    }

    function renderReconciliation(data) {
        if (!data) return;

        $('#reconcileSummaryCards').show();
        $('#reconcileTableCard').show();

        $('#summaryMatchedCount').text(data.matchedCount || 0);
        $('#summaryInternalOnlyCount').text(data.internalOnlyCount || 0);
        $('#summaryExternalOnlyCount').text(data.externalOnlyCount || 0);
        $('#summaryMismatchCount').text(data.amountMismatchCount || 0);
        $('#summaryIgnoredCount').text(data.ignoredCount || 0);

        if (data.readyForClosing) {
            $('#summaryClosingBadge').html('<span class="badge bg-success py-1 px-2"><i class="bi bi-check-circle me-1"></i>締め可能</span>');
        } else {
            $('#summaryClosingBadge').html('<span class="badge bg-danger py-1 px-2"><i class="bi bi-x-octagon me-1"></i>差異あり (要解消)</span>');
        }

        let tbody = $('#reconcileTbody').empty();
        let items = data.items || [];

        if (items.length === 0) {
            tbody.html('<tr><td colspan="8" class="text-center text-muted py-4">対象月の取引データがありません</td></tr>');
            return;
        }

        items.forEach(function (it) {
            let statusBadge =
                it.status === 'MATCHED' ? '<span class="badge bg-success-subtle text-success border border-success">完全一致</span>' :
                it.status === 'INTERNAL_ONLY' ? '<span class="badge bg-warning-subtle text-warning border border-warning">内部のみ (未送信)</span>' :
                it.status === 'EXTERNAL_ONLY' ? '<span class="badge bg-secondary-subtle text-secondary border border-secondary">外部のみ</span>' :
                it.status === 'AMOUNT_MISMATCH' ? '<span class="badge bg-danger-subtle text-danger border border-danger">金額不一致</span>' :
                '<span class="badge bg-light text-muted border">除外済</span>';

            let intAmt = it.internalAmount != null ? '¥' + it.internalAmount.toLocaleString() : '-';
            let extAmt = it.externalAmount != null ? '¥' + it.externalAmount.toLocaleString() : '-';

            let reasonText = it.status === 'IGNORED' ?
                `<span class="text-muted"><i class="bi bi-info-circle me-1"></i>除外: ${it.ignoreReason || '-'}</span>` :
                (it.discrepancyReason ? `<span class="text-danger small">${it.discrepancyReason}</span>` : '-');

            let actionBtn = '';
            if (it.status !== 'MATCHED' && it.status !== 'IGNORED') {
                actionBtn = `
                <button class="btn btn-sm btn-outline-secondary btn-open-ignore"
                        data-category="${it.category || ''}"
                        data-internal-id="${it.internalId || ''}"
                        data-external-id="${it.externalDealId || ''}"
                        data-label="${it.internalNo || it.externalDealId || '取引'}">
                    <i class="bi bi-slash-circle me-1"></i>除外設定
                </button>`;
            }

            let tr = `
            <tr>
                <td><span class="badge bg-light text-dark border">${it.category || '-'}</span></td>
                <td>
                    <div class="fw-bold">${it.internalNo || '-'}</div>
                    <div class="small text-muted">${it.partnerName || '-'}</div>
                </td>
                <td class="fw-bold text-end">${intAmt}</td>
                <td>
                    <div><code>${it.externalDealId || '-'}</code></div>
                    <div class="small text-muted">${it.externalRefNo || '-'}</div>
                </td>
                <td class="fw-bold text-end">${extAmt}</td>
                <td>${statusBadge}</td>
                <td>${reasonText}</td>
                <td>${actionBtn}</td>
            </tr>`;
            tbody.append(tr);
        });

        $('.btn-open-ignore').on('click', function () {
            let cat = $(this).data('category');
            let intId = $(this).data('internal-id');
            let extId = $(this).data('external-id');
            let label = $(this).data('label');

            $('#ignoreMonth').val($('#reconciliationMonth').val());
            $('#ignoreCategory').val(cat);
            $('#ignoreInternalId').val(intId);
            $('#ignoreExternalDealId').val(extId);
            $('#ignoreTargetLabel').val(label);
            $('#ignoreReasonInput').val('');

            ignoreModal.show();
        });
    }

    $('#btnSaveIgnore').on('click', function () {
        let month = $('#ignoreMonth').val();
        let reason = $('#ignoreReasonInput').val().trim();
        if (!reason) {
            SES.toast.warning('除外理由を入力してください');
            return;
        }

        let payload = {
            month: month,
            category: $('#ignoreCategory').val(),
            internalId: $('#ignoreInternalId').val() ? parseInt($('#ignoreInternalId').val()) : null,
            externalDealId: $('#ignoreExternalDealId').val() || null,
            reason: reason
        };

        SES.api.post('/api/accounting/reconciliation/ignore', JSON.stringify(payload)).done(function () {
            SES.toast.success('除外設定を保存しました');
            ignoreModal.hide();
            loadReconciliation(month);
        });
    });

    function renderPagination(data) {
        let current = data.current || 1;
        let total = data.total || 0;
        let size = data.size || 15;
        let pages = Math.ceil(total / size) || 1;

        let html = `<div class="small text-muted">全 ${total} 件 (${current} / ${pages} ページ)</div>`;
        html += `<div class="btn-group btn-group-sm">`;
        if (current > 1) {
            html += `<button class="btn btn-outline-secondary btn-page" data-page="${current - 1}">&laquo; 前へ</button>`;
        }
        if (current < pages) {
            html += `<button class="btn btn-outline-secondary btn-page" data-page="${current + 1}">次へ &raquo;</button>`;
        }
        html += `</div>`;

        $('#jobsPagination').html(html);
        $('.btn-page').on('click', function () {
            loadJobs($(this).data('page'));
        });
    }
});
