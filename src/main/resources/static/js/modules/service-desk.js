/**
 * サービスデスク・問い合わせ管理フロントエンドモジュール
 */
$(function () {
    const $container = $('[data-request-id]');
    if ($container.length > 0) {
        initDetailPage($container.data('request-id'));
    } else if ($('#requestsTable').length > 0) {
        initListPage();
    }
});

/**
 * 一覧画面の初期化
 */
function initListPage() {
    let currentPage = 1;
    const pageSize = 10;

    loadCustomersForSelect();
    loadRequests(currentPage);

    $('#searchForm').on('submit', function (e) {
        e.preventDefault();
        currentPage = 1;
        loadRequests(currentPage);
    });

    $('#resetBtn').on('click', function () {
        $('#searchForm')[0].reset();
        currentPage = 1;
        loadRequests(currentPage);
    });

    $('#newRequestForm').on('submit', function (e) {
        e.preventDefault();
        saveNewRequest();
    });

    $('#btnExportCsv').on('click', function () {
        const params = new URLSearchParams({
            keyword: $('#keyword').val() || '',
            status: $('#statusFilter').val() || '',
            priority: $('#priorityFilter').val() || '',
            category: $('#categoryFilter').val() || ''
        });
        window.location.href = '/api/service-desk/requests/export?' + params.toString();
    });

    function loadRequests(page) {
        const params = {
            current: page,
            size: pageSize,
            keyword: $('#keyword').val(),
            status: $('#statusFilter').val(),
            priority: $('#priorityFilter').val(),
            category: $('#categoryFilter').val()
        };

        $('#requestsTableBody').html('<tr><td colspan="10" class="text-center py-4 text-secondary"><div class="spinner-border spinner-border-sm me-2"></div>読み込み中...</td></tr>');

        $.ajax({
            url: '/api/service-desk/requests',
            type: 'GET',
            data: params,
            headers: window.SES && window.SES.csrf ? { [window.SES.csrf.header()]: window.SES.csrf.token() } : {},
            success: function (res) {
                if (res.code === 200) {
                    renderTable(res.data);
                } else {
                    if (window.Toast) window.Toast.error(res.message || 'データ取得に失敗しました');
                }
            },
            error: function () {
                if (window.Toast) window.Toast.error('通信エラーが発生しました');
            }
        });
    }

    function renderTable(pageData) {
        const records = pageData.records || [];
        const $tbody = $('#requestsTableBody').empty();

        if (records.length === 0) {
            $tbody.html('<tr><td colspan="10" class="text-center py-4 text-secondary">問い合わせデータがありません</td></tr>');
            $('#pageInfo').text('0 件');
            $('#pagination').empty();
            return;
        }

        records.forEach(function (r) {
            const statusBadge = getStatusBadge(r.status);
            const priorityBadge = getPriorityBadge(r.priority);
            const resolveDeadline = r.currentSlaClock ? formatDateTime(r.currentSlaClock.resolveDeadline) : '-';
            const isBreached = r.currentSlaClock && r.currentSlaClock.resolveBreached;
            const breachAlert = isBreached ? '<span class="badge bg-danger ms-1">超過</span>' : '';

            const row = `
                <tr>
                    <td><a href="/service-desk/requests/${r.id}" class="text-accent-blue fw-bold text-decoration-none">${escapeHtml(r.requestNo)}</a></td>
                    <td>${escapeHtml(r.customerName || '-')}</td>
                    <td><span class="badge bg-dark">${escapeHtml(r.category || '-')}</span></td>
                    <td>${priorityBadge}</td>
                    <td class="text-truncate" style="max-width: 250px;">
                        <a href="/service-desk/requests/${r.id}" class="text-white text-decoration-none">${escapeHtml(r.subject)}</a>
                    </td>
                    <td>${escapeHtml(r.ownerUserName || '未割当')}</td>
                    <td>${statusBadge}</td>
                    <td>${resolveDeadline} ${breachAlert}</td>
                    <td>${formatDateTime(r.createdAt)}</td>
                    <td class="text-end">
                        <a href="/service-desk/requests/${r.id}" class="btn btn-sm btn-outline-primary">
                            <i class="bi bi-eye me-1"></i>詳細
                        </a>
                    </td>
                </tr>
            `;
            $tbody.append(row);
        });

        renderPagination(pageData);
    }

    function renderPagination(pageData) {
        const total = pageData.total || 0;
        const current = pageData.current || 1;
        const pages = pageData.pages || 1;
        const size = pageData.size || 10;
        const start = total === 0 ? 0 : (current - 1) * size + 1;
        const end = Math.min(current * size, total);

        $('#pageInfo').text(`${total} 件中 ${start} - ${end} 件を表示`);

        const $ul = $('#pagination').empty();
        if (pages <= 1) return;

        $ul.append(`
            <li class="page-item ${current === 1 ? 'disabled' : ''}">
                <a class="page-link" href="#" data-page="${current - 1}">前へ</a>
            </li>
        `);

        for (let i = 1; i <= pages; i++) {
            if (i === 1 || i === pages || (i >= current - 2 && i <= current + 2)) {
                $ul.append(`
                    <li class="page-item ${i === current ? 'active' : ''}">
                        <a class="page-link" href="#" data-page="${i}">${i}</a>
                    </li>
                `);
            } else if (i === current - 3 || i === current + 3) {
                $ul.append('<li class="page-item disabled"><span class="page-link">...</span></li>');
            }
        }

        $ul.append(`
            <li class="page-item ${current === pages ? 'disabled' : ''}">
                <a class="page-link" href="#" data-page="${current + 1}">次へ</a>
            </li>
        `);

        $ul.find('a.page-link').on('click', function (e) {
            e.preventDefault();
            const p = parseInt($(this).data('page'));
            if (!isNaN(p) && p > 0 && p <= pages && p !== current) {
                currentPage = p;
                loadRequests(currentPage);
            }
        });
    }

    function loadCustomersForSelect() {
        $.ajax({
            url: '/api/customers',
            type: 'GET',
            data: { current: 1, size: 500 },
            headers: window.SES && window.SES.csrf ? { [window.SES.csrf.header()]: window.SES.csrf.token() } : {},
            success: function (res) {
                if (res.code === 200 && res.data && res.data.records) {
                    const $sel = $('#newCustomerId').empty();
                    $sel.append('<option value="">顧客を選択してください</option>');
                    res.data.records.forEach(function (c) {
                        $sel.append(`<option value="${c.id}">${escapeHtml(c.companyName)}</option>`);
                    });
                }
            }
        });
    }

    function saveNewRequest() {
        const payload = {
            customerId: $('#newCustomerId').val(),
            channel: $('#newChannel').val(),
            category: $('#newCategory').val(),
            priority: $('#newPriority').val(),
            subject: $('#newSubject').val(),
            description: $('#newDescription').val()
        };

        $('#saveRequestBtn').prop('disabled', true);

        $.ajax({
            url: '/api/service-desk/requests',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            headers: window.SES && window.SES.csrf ? { [window.SES.csrf.header()]: window.SES.csrf.token() } : {},
            success: function (res) {
                $('#saveRequestBtn').prop('disabled', false);
                if (res.code === 200) {
                    if (window.Toast) window.Toast.success('問い合わせを起票しました');
                    bootstrap.Modal.getInstance($('#newRequestModal')[0]).hide();
                    $('#newRequestForm')[0].reset();
                    loadRequests(currentPage);
                } else {
                    if (window.Toast) window.Toast.error(res.message || '起票に失敗しました');
                }
            },
            error: function (xhr) {
                $('#saveRequestBtn').prop('disabled', false);
                const msg = xhr.responseJSON ? xhr.responseJSON.message : '起票処理中にエラーが発生しました';
                if (window.Toast) window.Toast.error(msg);
            }
        });
    }
}

/**
 * 詳細画面の初期化
 */
function initDetailPage(requestId) {
    loadDetail(requestId);

    $('#commentForm').on('submit', function (e) {
        e.preventDefault();
        submitComment(requestId);
    });

    function loadDetail(id) {
        $.ajax({
            url: `/api/service-desk/requests/${id}`,
            type: 'GET',
            headers: window.SES && window.SES.csrf ? { [window.SES.csrf.header()]: window.SES.csrf.token() } : {},
            success: function (res) {
                if (res.code === 200) {
                    renderDetail(res.data);
                } else {
                    if (window.Toast) window.Toast.error(res.message || '詳細データの取得に失敗しました');
                }
            },
            error: function () {
                if (window.Toast) window.Toast.error('詳細データの取得に失敗しました');
            }
        });
    }

    function renderDetail(data) {
        $('#breadcrumbReqNo').text(data.requestNo);
        $('#requestNoBadge').text(data.requestNo);
        $('#statusBadge').replaceWith(getStatusBadge(data.status));
        $('#priorityBadge').replaceWith(getPriorityBadge(data.priority));
        $('#categoryBadge').text(data.category);
        $('#subjectHeader').text(data.subject);
        $('#customerName').text(data.customerName || '-');
        $('#createdAt').text(formatDateTime(data.createdAt));
        $('#ownerUserName').text(data.ownerUserName || '未割当');
        $('#descriptionText').text(data.description);

        // メタデータ
        $('#metaContactName').text(data.contactName || '-');
        $('#metaContractCode').text(data.contractCode || '-');
        $('#metaProjectName').text(data.projectName || '-');
        $('#metaEngineerName').text(data.engineerName || '-');
        $('#metaChannel').text(data.channel || '-');
        $('#metaReopenCount').text(data.reopenCount || 0);

        // アクションボタン
        renderActionButtons(data);

        // SLA カード
        renderSlaCard(data.currentSlaClock);

        // タイムライン・コメント
        renderComments(data.comments || []);

        // CSAT カード
        if (data.csatScore) {
            $('#csatCard').removeClass('d-none');
            $('#csatStars').text('★'.repeat(data.csatScore) + '☆'.repeat(5 - data.csatScore));
            $('#csatCommentText').text(data.csatComment || '(コメントなし)');
        } else {
            $('#csatCard').addClass('d-none');
        }
    }

    function renderActionButtons(data) {
        const $btns = $('#statusActionButtons').empty();
        const s = data.status;

        if (s === 'RECEIVED') {
            $btns.append('<button class="btn btn-primary btn-sm btn-status" data-status="IN_PROGRESS"><i class="bi bi-play-fill me-1"></i>対応着手</button>');
            $btns.append('<button class="btn btn-outline-warning btn-sm btn-status" data-status="WAITING_CUSTOMER"><i class="bi bi-pause-fill me-1"></i>顧客確認待ち</button>');
            $btns.append('<button class="btn btn-outline-secondary btn-sm btn-status" data-status="CLOSED"><i class="bi bi-x-circle me-1"></i>終了</button>');
        } else if (s === 'IN_PROGRESS') {
            $btns.append('<button class="btn btn-warning btn-sm btn-status" data-status="WAITING_CUSTOMER"><i class="bi bi-pause-fill me-1"></i>顧客確認待ち</button>');
            $btns.append('<button class="btn btn-success btn-sm btn-status" data-status="RESOLVED"><i class="bi bi-check-circle me-1"></i>解決済みにする</button>');
            $btns.append('<button class="btn btn-outline-secondary btn-sm btn-status" data-status="CLOSED"><i class="bi bi-x-circle me-1"></i>終了</button>');
        } else if (s === 'WAITING_CUSTOMER') {
            $btns.append('<button class="btn btn-primary btn-sm btn-status" data-status="IN_PROGRESS"><i class="bi bi-play-fill me-1"></i>対応再開</button>');
            $btns.append('<button class="btn btn-success btn-sm btn-status" data-status="RESOLVED"><i class="bi bi-check-circle me-1"></i>解決済みにする</button>');
            $btns.append('<button class="btn btn-outline-secondary btn-sm btn-status" data-status="CLOSED"><i class="bi bi-x-circle me-1"></i>終了</button>');
        } else if (s === 'RESOLVED') {
            $btns.append('<button class="btn btn-outline-secondary btn-sm btn-status" data-status="CLOSED"><i class="bi bi-check-all me-1"></i>クローズ完了</button>');
            $btns.append('<button class="btn btn-outline-danger btn-sm btn-status" data-status="REOPENED"><i class="bi bi-arrow-repeat me-1"></i>再オープン</button>');
        } else if (s === 'CLOSED') {
            $btns.append('<button class="btn btn-outline-danger btn-sm btn-status" data-status="REOPENED"><i class="bi bi-arrow-repeat me-1"></i>再オープン</button>');
        }

        $btns.find('.btn-status').on('click', function () {
            const nextStatus = $(this).data('status');
            changeStatus(data.id, nextStatus, data.version);
        });
    }

    function changeStatus(id, targetStatus, version) {
        Swal.fire({
            title: `ステータス変更: ${targetStatus}`,
            input: 'textarea',
            inputLabel: '変更理由・対応内容メモ (任意)',
            inputPlaceholder: 'ステータスを変更する理由や要約を入力...',
            showCancelButton: true,
            confirmButtonText: '変更する',
            cancelButtonText: 'キャンセル'
        }).then(function (result) {
            if (result.isConfirmed) {
                const payload = {
                    targetStatus: targetStatus,
                    reason: result.value || 'ステータス更新',
                    version: version
                };
                $.ajax({
                    url: `/api/service-desk/requests/${id}/status`,
                    type: 'POST',
                    contentType: 'application/json',
                    data: JSON.stringify(payload),
                    headers: window.SES && window.SES.csrf ? { [window.SES.csrf.header()]: window.SES.csrf.token() } : {},
                    success: function (res) {
                        if (res.code === 200) {
                            if (window.Toast) window.Toast.success('ステータスを更新しました');
                            loadDetail(id);
                        } else {
                            if (window.Toast) window.Toast.error(res.message || '更新に失敗しました');
                        }
                    },
                    error: function (xhr) {
                        const msg = xhr.responseJSON ? xhr.responseJSON.message : '更新に失敗しました';
                        if (window.Toast) window.Toast.error(msg);
                    }
                });
            }
        });
    }

    function renderSlaCard(clock) {
        if (!clock) {
            $('#slaCardBody').html('<div class="text-secondary small">SLAポリシー未適用</div>');
            return;
        }

        const isBreached = clock.resolveBreached;
        const isPaused = clock.status === 'PAUSED';
        let cardClass = isBreached ? 'sla-card-breached' : (isPaused ? 'sla-card-paused' : 'sla-card-running');
        $('#slaCard').removeClass('sla-card-running sla-card-paused sla-card-breached').addClass(cardClass);

        const respDeadline = formatDateTime(clock.responseDeadline);
        const respActual = clock.firstRespondedAt ? formatDateTime(clock.firstRespondedAt) : '未応答';
        const respBreachedBadge = clock.responseBreached ? '<span class="badge bg-danger">超過</span>' : '<span class="badge bg-success">達成</span>';

        const resolveDeadline = formatDateTime(clock.resolveDeadline);
        const resolveActual = clock.resolvedAt ? formatDateTime(clock.resolvedAt) : (clock.status === 'COMPLETED' ? '完了' : '対応中');
        const resolveBreachedBadge = clock.resolveBreached ? '<span class="badge bg-danger">超過</span>' : (clock.status === 'COMPLETED' ? '<span class="badge bg-success">達成</span>' : '');

        const pauseInfo = clock.totalPauseMinutes > 0 ? `<div class="text-secondary small mt-1"><i class="bi bi-pause-circle me-1"></i>累計停止: ${clock.totalPauseMinutes} 分</div>` : '';

        const html = `
            <div class="mb-3">
                <div class="d-flex justify-content-between align-items-center mb-1">
                    <span class="text-secondary small">初回応答目標:</span>
                    ${clock.firstRespondedAt ? respBreachedBadge : ''}
                </div>
                <div class="text-white small">期限: ${respDeadline}</div>
                <div class="text-secondary small">実績: ${respActual}</div>
            </div>
            <div class="border-top border-dark pt-2">
                <div class="d-flex justify-content-between align-items-center mb-1">
                    <span class="text-secondary small">解決目標期限:</span>
                    ${resolveBreachedBadge}
                </div>
                <div class="text-white small fw-bold">期限: ${resolveDeadline}</div>
                <div class="text-secondary small">実績: ${resolveActual}</div>
                ${pauseInfo}
            </div>
        `;
        $('#slaCardBody').html(html);
    }

    function renderComments(comments) {
        const $container = $('#commentsContainer').empty();
        if (comments.length === 0) {
            $container.html('<div class="text-center py-3 text-secondary">コメントはまだありません</div>');
            return;
        }

        comments.forEach(function (c) {
            const isInternal = c.visibility === 'INTERNAL';
            const boxClass = isInternal ? 'comment-internal' : 'comment-portal';
            const badge = isInternal
                ? '<span class="badge bg-warning text-dark me-2"><i class="bi bi-lock-fill me-1"></i>社内限定メモ</span>'
                : '<span class="badge bg-primary me-2"><i class="bi bi-globe me-1"></i>ポータル公開</span>';

            const html = `
                <div class="card border-0 mb-3 ${boxClass} p-3 rounded shadow-sm">
                    <div class="d-flex justify-content-between align-items-center mb-2">
                        <div>
                            ${badge}
                            <strong class="text-white">${escapeHtml(c.authorName)}</strong>
                            <span class="text-secondary small ms-2">${c.authorType === 'INTERNAL_USER' ? '(社内担当)' : '(顧客)'}</span>
                        </div>
                        <span class="text-secondary small">${formatDateTime(c.createdAt)}</span>
                    </div>
                    <div class="text-white" style="white-space: pre-wrap;">${escapeHtml(c.commentText)}</div>
                </div>
            `;
            $container.append(html);
        });
    }

    function submitComment(requestId) {
        const text = $('#commentTextInput').val();
        const vis = $('input[name="visibility"]:checked').val();

        if (!text.trim()) {
            if (window.Toast) window.Toast.error('コメントを入力してください');
            return;
        }

        $('#submitCommentBtn').prop('disabled', true);

        const payload = {
            commentText: text,
            visibility: vis
        };

        $.ajax({
            url: `/api/service-desk/requests/${requestId}/comments`,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            headers: window.SES && window.SES.csrf ? { [window.SES.csrf.header()]: window.SES.csrf.token() } : {},
            success: function (res) {
                $('#submitCommentBtn').prop('disabled', false);
                if (res.code === 200) {
                    if (window.Toast) window.Toast.success('コメントを投稿しました');
                    $('#commentTextInput').val('');
                    loadDetail(requestId);
                } else {
                    if (window.Toast) window.Toast.error(res.message || '投稿に失敗しました');
                }
            },
            error: function () {
                $('#submitCommentBtn').prop('disabled', false);
                if (window.Toast) window.Toast.error('投稿中にエラーが発生しました');
            }
        });
    }
}

// ユーティリティ
function getStatusBadge(status) {
    switch (status) {
        case 'RECEIVED':
            return '<span class="badge bg-primary">受付 (RECEIVED)</span>';
        case 'IN_PROGRESS':
            return '<span class="badge bg-info text-dark">対応中 (IN_PROGRESS)</span>';
        case 'WAITING_CUSTOMER':
            return '<span class="badge bg-warning text-dark">顧客確認待ち (WAITING)</span>';
        case 'RESOLVED':
            return '<span class="badge bg-success">解決 (RESOLVED)</span>';
        case 'CLOSED':
            return '<span class="badge bg-secondary">終了 (CLOSED)</span>';
        default:
            return `<span class="badge bg-dark">${escapeHtml(status || '-')}</span>`;
    }
}

function getPriorityBadge(priority) {
    switch (priority) {
        case 'P0':
            return '<span class="badge bg-danger fw-bold">P0 (緊急)</span>';
        case 'P1':
            return '<span class="badge bg-warning text-dark fw-bold">P1 (高)</span>';
        case 'P2':
            return '<span class="badge bg-info text-dark">P2 (中)</span>';
        case 'P3':
            return '<span class="badge bg-secondary">P3 (低)</span>';
        default:
            return `<span class="badge bg-dark">${escapeHtml(priority || '-')}</span>`;
    }
}

function formatDateTime(dtStr) {
    if (!dtStr) return '-';
    return dtStr.replace('T', ' ').substring(0, 16);
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
