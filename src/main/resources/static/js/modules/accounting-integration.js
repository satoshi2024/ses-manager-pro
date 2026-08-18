/**
 * 会計・支払連携モジュール (accounting-integration.js / A1)
 * 4言語 i18n 対応 (ja, en, zh_CN, ko) & DOM XSS 完全サニタイズ
 */
$(function () {
    // === 4言語 i18n 辞書 ===
    const I18N = {
        ja: {
            selectConn: '接続を選択してください',
            keyAndIdRequired: '内部キーと外部システムIDは必須です',
            mappingSaved: 'マッピングを保存しました',
            mappingVerified: '外部マスタとの照合・検証に成功しました',
            verifyFailed: 'マスタ検証に失敗しました',
            inputInvoiceId: '請求書IDを入力してください',
            retryRequested: 'ジョブの再実行を要求しました',
            cancelRequested: 'ジョブをキャンセルしました',
            inputIgnoreReason: '除外・承認理由を入力してください',
            ignoreSaved: '照合除外設定を保存しました',
            connected: '接続中',
            reauth: '要再認証',
            disconnected: '未接続',
            verified: '検証済',
            unverified: '未検証',
            matched: '完全一致',
            internalOnly: '内部のみ (未送信)',
            externalOnly: '外部のみ',
            mismatch: '金額不一致',
            ignored: '除外済',
            btnVerify: '照合・検証',
            btnEdit: '編集',
            btnIgnore: '除外設定',
            btnDetail: '詳細',
            btnRetry: '再試行',
            btnCancel: '取消',
            ready: '締可',
            notReady: '要確認 (締不可)',
            noJobs: 'ジョブ履歴が存在しません',
            noMappings: 'マッピングが登録されていません',
            noConnections: '接続設定が存在しません',
            loadError: 'データの読み込みに失敗しました'
        },
        en: {
            selectConn: 'Please select a connection',
            keyAndIdRequired: 'Internal key and External ID are required',
            mappingSaved: 'Mapping saved successfully',
            mappingVerified: 'External master verified successfully',
            verifyFailed: 'Master verification failed',
            inputInvoiceId: 'Please enter Invoice ID',
            retryRequested: 'Job retry requested',
            cancelRequested: 'Job cancelled',
            inputIgnoreReason: 'Please enter ignore / approval reason',
            ignoreSaved: 'Ignore setting saved',
            connected: 'Connected',
            reauth: 'Re-auth Required',
            disconnected: 'Disconnected',
            verified: 'Verified',
            unverified: 'Unverified',
            matched: 'Matched',
            internalOnly: 'Internal Only (Unsent)',
            externalOnly: 'External Only',
            mismatch: 'Amount Mismatch',
            ignored: 'Ignored',
            btnVerify: 'Verify',
            btnEdit: 'Edit',
            btnIgnore: 'Ignore',
            btnDetail: 'Details',
            btnRetry: 'Retry',
            btnCancel: 'Cancel',
            ready: 'Ready',
            notReady: 'Action Required (Not Ready)',
            noJobs: 'No jobs found',
            noMappings: 'No mappings registered',
            noConnections: 'No connections found',
            loadError: 'Failed to load data'
        },
        zh: {
            selectConn: '请选择连接',
            keyAndIdRequired: '内部键和外部系统ID必填',
            mappingSaved: '映射保存成功',
            mappingVerified: '外部主数据验证成功',
            verifyFailed: '主数据验证失败',
            inputInvoiceId: '请输入发票ID',
            retryRequested: '已请求重试作业',
            cancelRequested: '已取消作业',
            inputIgnoreReason: '请输入忽略 / 审批理由',
            ignoreSaved: '已保存忽略设置',
            connected: '已连接',
            reauth: '需重新认证',
            disconnected: '未连接',
            verified: '已验证',
            unverified: '未验证',
            matched: '完全匹配',
            internalOnly: '仅内部 (未发送)',
            externalOnly: '仅外部',
            mismatch: '金额不符',
            ignored: '已忽略',
            btnVerify: '验证',
            btnEdit: '编辑',
            btnIgnore: '忽略设置',
            btnDetail: '详情',
            btnRetry: '重试',
            btnCancel: '取消',
            ready: '可月结',
            notReady: '需确认 (不可月结)',
            noJobs: '无作业历史',
            noMappings: '未配置映射',
            noConnections: '无连接配置',
            loadError: '加载数据失败'
        },
        ko: {
            selectConn: '연결을 선택해 주세요',
            keyAndIdRequired: '내부 키와 외부 시스템 ID는 필수입니다',
            mappingSaved: '매핑이 저장되었습니다',
            mappingVerified: '외부 마스터 검증에 성공했습니다',
            verifyFailed: '마스터 검증 실패',
            inputInvoiceId: '청구서 ID를 입력해 주세요',
            retryRequested: '작업 재시도를 요청했습니다',
            cancelRequested: '작업이 취소되었습니다',
            inputIgnoreReason: '제외 / 승인 사유를 입력해 주세요',
            ignoreSaved: '제외 설정이 저장되었습니다',
            connected: '연결됨',
            reauth: '재인증 필요',
            disconnected: '연결 안 됨',
            verified: '검증됨',
            unverified: '미검증',
            matched: '완전 일치',
            internalOnly: '내부 전용 (미전송)',
            externalOnly: '외부 전용',
            mismatch: '금액 불일치',
            ignored: '제외됨',
            btnVerify: '검증',
            btnEdit: '편집',
            btnIgnore: '제외 설정',
            btnDetail: '상세',
            btnRetry: '재시도',
            btnCancel: '취소',
            ready: '마감 가능',
            notReady: '확인 필요 (마감 불가)',
            noJobs: '작업 이력이 없습니다',
            noMappings: '등록된 매핑이 없습니다',
            noConnections: '연결 설정이 없습니다',
            loadError: '데이터 불러오기 실패'
        }
    };

    function getLang() {
        let lang = document.documentElement.lang || 'ja';
        if (lang.startsWith('zh')) return 'zh';
        if (lang.startsWith('ko')) return 'ko';
        if (lang.startsWith('en')) return 'en';
        return 'ja';
    }

    function t(key) {
        let lang = getLang();
        return (I18N[lang] && I18N[lang][key]) || (I18N.ja && I18N.ja[key]) || key;
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
    let jobModal = new bootstrap.Modal(document.getElementById('jobDetailModal'));
    let mapModal = new bootstrap.Modal(document.getElementById('mappingModal'));
    let ignoreModal = new bootstrap.Modal(document.getElementById('ignoreModal'));

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
            SES.toast.warning(t('selectConn'));
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
            SES.toast.warning(t('keyAndIdRequired'));
            return;
        }
        SES.api.post('/api/accounting/mappings', JSON.stringify(payload)).done(function () {
            SES.toast.success(t('mappingSaved'));
            mapModal.hide();
            loadMappings(currentConnId);
        });
    });

    $('#btnRunPreview').on('click', function () {
        let invoiceId = $('#previewInvoiceId').val();
        if (!invoiceId) {
            SES.toast.warning(t('inputInvoiceId'));
            return;
        }
        SES.api.get('/api/accounting/preview/sales-invoice/' + invoiceId).done(function (res) {
            renderPreviewResult(res.data);
        }).fail(function (err) {
            SES.toast.error(err.responseJSON ? err.responseJSON.message : t('loadError'));
        });
    });

    // === 接続設定 ===
    function loadConnections() {
        SES.api.get('/api/accounting/connections').done(function (res) {
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
                                <div>事業所: <strong>${escapeHtml(c.companyName || '-')}</strong> (ID: ${escapeHtml(String(c.externalCompanyId || '-'))})</div>
                                <div>有効期限: ${escapeHtml(c.expiresAt ? c.expiresAt.replace('T', ' ') : '-')}</div>
                                <div>最終更新: ${escapeHtml(c.lastRefreshedAt ? c.lastRefreshedAt.replace('T', ' ') : '-')}</div>
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

        SES.api.get(url).done(function (res) {
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
                    <td class="fw-bold">${escapeHtml(m.internalCode)}</td>
                    <td><code>${escapeHtml(m.externalId)}</code></td>
                    <td>${escapeHtml(m.externalCode || '-')}</td>
                    <td>${verifiedBadge}</td>
                    <td>
                        <button class="btn btn-xs btn-outline-primary btn-edit-mapping me-1" data-id="${escapeHtml(String(m.id))}">
                            ${escapeHtml(t('btnEdit'))}
                        </button>
                        <button class="btn btn-xs btn-outline-success btn-verify-mapping" data-id="${escapeHtml(String(m.id))}">
                            ${escapeHtml(t('btnVerify'))}
                        </button>
                    </td>
                </tr>`;
                tbody.append(tr);
            });

            $('.btn-edit-mapping').on('click', function () {
                let id = $(this).data('id');
                let target = list.find(m => m.id === id);
                if (target) {
                    $('#modalConnId').val(target.connectionId);
                    $('#modalObjectType').val(target.objectType);
                    $('#modalInternalCode').val(target.internalCode);
                    $('#modalExternalId').val(target.externalId);
                    $('#modalExternalCode').val(target.externalCode || '');
                    mapModal.show();
                }
            });

            $('.btn-verify-mapping').on('click', function () {
                let id = $(this).data('id');
                SES.api.post('/api/accounting/mappings/' + id + '/verify').done(function () {
                    SES.toast.success(t('mappingVerified'));
                    loadMappings(currentConnId);
                }).fail(function (err) {
                    SES.toast.error(err.responseJSON ? err.responseJSON.message : t('verifyFailed'));
                });
            });
        });
    }

    // === ジョブ一覧 ===
    function loadJobs(page) {
        let status = $('#jobStatusFilter').val();
        let jobType = $('#jobTypeFilter').val();
        let url = `/api/accounting/jobs?current=${page}&size=10`
            + (status ? `&status=${status}` : '')
            + (jobType ? `&jobType=${jobType}` : '');

        SES.api.get(url).done(function (res) {
            let pageData = res.data;
            let list = (pageData && pageData.records) || [];
            let tbody = $('#jobsTbody').empty();

            if (list.length === 0) {
                tbody.html(`<tr><td colspan="8" class="text-center text-muted py-4">${escapeHtml(t('noJobs'))}</td></tr>`);
                $('#jobsPagination').empty();
                return;
            }

            list.forEach(function (j) {
                let statusBadge = j.status === 'SUCCEEDED' ? '<span class="badge bg-success">SUCCEEDED</span>' :
                    j.status === 'RUNNING' ? '<span class="badge bg-primary">RUNNING</span>' :
                        j.status === 'RETRYABLE' ? '<span class="badge bg-warning text-dark">RETRYABLE</span>' :
                            j.status === 'FAILED' ? '<span class="badge bg-danger">FAILED</span>' :
                                j.status === 'CANCELLED' ? '<span class="badge bg-secondary">CANCELLED</span>' :
                                    '<span class="badge bg-light text-dark border">PENDING</span>';

                let actionBtns = `
                    <button class="btn btn-xs btn-outline-info btn-job-detail me-1" data-id="${escapeHtml(String(j.id))}">${escapeHtml(t('btnDetail'))}</button>
                `;
                if (j.status === 'RETRYABLE' || j.status === 'FAILED') {
                    actionBtns += `<button class="btn btn-xs btn-outline-warning btn-job-retry me-1" data-id="${escapeHtml(String(j.id))}">${escapeHtml(t('btnRetry'))}</button>`;
                }
                if (j.status === 'PENDING' || j.status === 'RETRYABLE') {
                    actionBtns += `<button class="btn btn-xs btn-outline-danger btn-job-cancel" data-id="${escapeHtml(String(j.id))}">${escapeHtml(t('btnCancel'))}</button>`;
                }

                let tr = `
                <tr>
                    <td>#${escapeHtml(String(j.id))}</td>
                    <td><span class="badge bg-light text-dark border">${escapeHtml(j.jobType)}</span></td>
                    <td>${escapeHtml(j.targetType)} #${escapeHtml(String(j.targetId))}</td>
                    <td>${statusBadge}</td>
                    <td>${escapeHtml(String(j.attemptCount))} / ${escapeHtml(String(j.maxAttempts))}</td>
                    <td><code>${escapeHtml(j.externalId || '-')}</code></td>
                    <td class="small">${escapeHtml(j.createdAt ? j.createdAt.replace('T', ' ') : '-')}</td>
                    <td>${actionBtns}</td>
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
                SES.api.post('/api/accounting/jobs/' + id + '/retry').done(function () {
                    SES.toast.success(t('retryRequested'));
                    loadJobs(page);
                });
            });

            $('.btn-job-cancel').on('click', function () {
                let id = $(this).data('id');
                SES.api.post('/api/accounting/jobs/' + id + '/cancel?reason=手動キャンセル').done(function () {
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
        SES.api.get('/api/accounting/jobs/' + jobId).done(function (res) {
            let dto = res.data;
            let j = dto.job;
            let events = dto.events || [];

            let html = `
                <div class="row g-2 small mb-3">
                    <div class="col-6"><strong>ID:</strong> #${escapeHtml(String(j.id))}</div>
                    <div class="col-6"><strong>Status:</strong> ${escapeHtml(j.status)}</div>
                    <div class="col-6"><strong>Job Type:</strong> ${escapeHtml(j.jobType)}</div>
                    <div class="col-6"><strong>Target:</strong> ${escapeHtml(j.targetType)} #${escapeHtml(String(j.targetId))}</div>
                    <div class="col-6"><strong>External ID:</strong> ${escapeHtml(j.externalId || '-')}</div>
                    <div class="col-6"><strong>Provider Request ID:</strong> ${escapeHtml(j.providerRequestId || '-')}</div>
                    <div class="col-12"><strong>Error:</strong> <span class="text-danger">${escapeHtml(j.errorCode || '-')} - ${escapeHtml(j.errorMessageSafe || '-')}</span></div>
                    <div class="col-12"><strong>Idempotency Key:</strong> <code>${escapeHtml(j.idempotencyKey || '-')}</code></div>
                </div>
            `;
            $('#jobDetailInfo').html(html);

            let eventTbody = $('#jobEventsTbody').empty();
            events.forEach(function (ev) {
                let tr = `
                    <tr>
                        <td class="small text-nowrap">${escapeHtml(ev.occurredAt ? ev.occurredAt.replace('T', ' ') : '-')}</td>
                        <td><span class="badge bg-light text-dark border">${escapeHtml(ev.fromStatus || 'START')}</span> &rarr; <span class="badge bg-light text-dark border">${escapeHtml(ev.toStatus)}</span></td>
                        <td class="small">${escapeHtml(ev.safeDetail || '-')}</td>
                    </tr>
                `;
                eventTbody.append(tr);
            });

            // モーダル内ボタン制御
            if (j.status === 'RETRYABLE' || j.status === 'FAILED') {
                $('#btnModalRetry').show().off('click').on('click', function () {
                    SES.api.post('/api/accounting/jobs/' + j.id + '/retry').done(function () {
                        SES.toast.success(t('retryRequested'));
                        jobModal.hide();
                        loadJobs(1);
                    });
                });
            } else {
                $('#btnModalRetry').hide();
            }

            if (j.status === 'PENDING' || j.status === 'RETRYABLE') {
                $('#btnModalCancel').show().off('click').on('click', function () {
                    SES.api.post('/api/accounting/jobs/' + j.id + '/cancel?reason=手動キャンセル').done(function () {
                        SES.toast.success(t('cancelRequested'));
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

    // === 送信プレビュー ===
    function renderPreviewResult(data) {
        if (!data) return;
        let container = $('#previewResultContainer').show();
        let detailsHtml = '';
        if (data.details && data.details.length > 0) {
            data.details.forEach(function (d) {
                detailsHtml += `
                    <tr>
                        <td>${escapeHtml(d.description || '-')}</td>
                        <td class="text-end">&yen;${escapeHtml(Number(d.amount || 0).toLocaleString())}</td>
                        <td><code>${escapeHtml(d.accountItemCode || '2101')}</code></td>
                        <td><code>${escapeHtml(d.taxCode || '21')}</code></td>
                    </tr>
                `;
            });
        }

        let html = `
            <div class="card shadow-sm border-0">
                <div class="card-header bg-white fw-bold">
                    <i class="bi bi-file-earmark-text me-1"></i>送信ペイロード プレビュー (請求書: ${escapeHtml(data.invoiceNo)})
                </div>
                <div class="card-body">
                    <div class="row g-2 small mb-3">
                        <div class="col-md-4"><strong>顧客コード:</strong> ${escapeHtml(data.customerCode)}</div>
                        <div class="col-md-4"><strong>顧客名:</strong> ${escapeHtml(data.customerName)}</div>
                        <div class="col-md-4"><strong>発行日:</strong> ${escapeHtml(data.issueDate || '-')}</div>
                        <div class="col-md-4"><strong>支払期日:</strong> ${escapeHtml(data.dueDate || '-')}</div>
                        <div class="col-md-4"><strong>税抜金額:</strong> &yen;${escapeHtml(Number(data.subtotal || 0).toLocaleString())}</div>
                        <div class="col-md-4"><strong>税込合計:</strong> <span class="fs-6 fw-bold text-primary">&yen;${escapeHtml(Number(data.total || 0).toLocaleString())}</span></div>
                    </div>
                    <table class="table table-sm table-bordered mb-0">
                        <thead class="table-light">
                            <tr>
                                <th>品名 / 摘要</th>
                                <th class="text-end">金額</th>
                                <th>勘定科目コード</th>
                                <th>税区分コード</th>
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
    // JST (Asia/Tokyo) 基準の年月初期設定
    let nowJst = new Date(new Date().toLocaleString('en-US', { timeZone: 'Asia/Tokyo' }));
    let y = nowJst.getFullYear();
    let m = String(nowJst.getMonth() + 1).padStart(2, '0');
    $('#reconciliationMonth').val(`${y}-${m}`);

    $('#btnRunReconciliation').on('click', function () {
        loadReconciliation();
    });

    function loadReconciliation() {
        let month = $('#reconciliationMonth').val();
        if (!month) return;

        SES.api.get('/api/accounting/reconciliation?month=' + month).done(function (res) {
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
                tbody.html('<tr><td colspan="8" class="text-center text-muted py-4">照合対象データが存在しません</td></tr>');
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

                ignoreModal.show();
            });
        });
    }

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

        SES.api.post('/api/accounting/reconciliation/ignore', JSON.stringify(payload)).done(function () {
            SES.toast.success(t('ignoreSaved'));
            ignoreModal.hide();
            loadReconciliation();
        });
    });
});
