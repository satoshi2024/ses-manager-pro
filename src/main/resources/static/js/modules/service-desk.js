/**
 * サービスデスク・問い合わせ管理フロントエンドモジュール
 * 画面文言は SES.i18n.t（messages の servicedesk.* 等）から取る（RB-I18N P3-N2-8）。
 */
$(function () {
    const $container = $('[data-request-id]');
    if ($container.length > 0) {
        initDetailPage($container.data('request-id'));
    } else if ($('#requestsTable').length > 0) {
        initListPage();
    }
});

function sdT(key, ...args) {
    return SES.i18n.t(key, ...args);
}

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

        $('#requestsTableBody').html('<tr><td colspan="10" class="text-center py-4 text-secondary"><div class="spinner-border spinner-border-sm me-2"></div>'
            + escapeHtml(sdT('common.loading')) + '</td></tr>');

        $.ajax({
            url: '/api/service-desk/requests',
            type: 'GET',
            data: params,
            headers: window.SES && window.SES.csrf ? window.SES.csrf.header() : {},
            success: function (res) {
                if (res.code === 200) {
                    renderTable(res.data);
                } else {
                    if (window.Toast) window.Toast.error(res.message || sdT('servicedesk.msg.loadFailed'));
                }
            },
            error: function () {
                if (window.Toast) window.Toast.error(sdT('error.networkError'));
            }
        });
    }

    function renderTable(pageData) {
        const records = pageData.records || [];
        const $tbody = $('#requestsTableBody').empty();

        if (records.length === 0) {
            $tbody.html('<tr><td colspan="10" class="text-center py-4 text-secondary">' + escapeHtml(sdT('servicedesk.list.empty')) + '</td></tr>');
            $('#pageInfo').text(sdT('servicedesk.list.count', 0));
            $('#pagination').empty();
            return;
        }

        records.forEach(function (r) {
            const statusBadge = getStatusBadge(r.status);
            const priorityBadge = getPriorityBadge(r.priority);
            const resolveDeadline = r.currentSlaClock ? formatDateTime(r.currentSlaClock.resolveDeadline) : '-';
            const isBreached = r.currentSlaClock && r.currentSlaClock.resolveBreached;
            const breachAlert = isBreached ? '<span class="badge bg-danger ms-1">' + escapeHtml(sdT('servicedesk.sla.breached')) + '</span>' : '';

            const row = `
                <tr>
                    <td>
                        <a href="/service-desk/requests/${r.id}" class="text-accent-blue fw-bold text-decoration-none">${escapeHtml(r.requestNo)}</a>
                        <div class="mt-1">${getPortalVisibilityBadge(r.portalVisible)}</div>
                    </td>
                    <td>${escapeHtml(r.customerName || '-')}</td>
                    <td><span class="badge bg-dark">${escapeHtml(categoryLabel(r.category))}</span></td>
                    <td>${priorityBadge}</td>
                    <td class="text-truncate" style="max-width: 250px;">
                        <a href="/service-desk/requests/${r.id}" class="text-white text-decoration-none">${escapeHtml(r.subject)}</a>
                    </td>
                    <td>${escapeHtml(r.ownerUserName || sdT('servicedesk.detail.unassigned'))}</td>
                    <td>${statusBadge}</td>
                    <td>${resolveDeadline} ${breachAlert}</td>
                    <td>${formatDateTime(r.createdAt)}</td>
                    <td class="text-end">
                        <a href="/service-desk/requests/${r.id}" class="btn btn-sm btn-outline-primary">
                            <i class="bi bi-eye me-1"></i>${escapeHtml(sdT('common.detail'))}
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

        $('#pageInfo').text(sdT('servicedesk.list.pageInfo', total, start, end));

        const $ul = $('#pagination').empty();
        if (pages <= 1) return;

        $ul.append(`
            <li class="page-item ${current === 1 ? 'disabled' : ''}">
                <a class="page-link" href="#" data-page="${current - 1}">${escapeHtml(sdT('servicedesk.list.prev'))}</a>
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
                <a class="page-link" href="#" data-page="${current + 1}">${escapeHtml(sdT('servicedesk.list.next'))}</a>
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
            headers: window.SES && window.SES.csrf ? window.SES.csrf.header() : {},
            success: function (res) {
                if (res.code === 200 && res.data && res.data.records) {
                    const $sel = $('#newCustomerId').empty();
                    $sel.append('<option value="">' + escapeHtml(sdT('servicedesk.field.selectCustomer')) + '</option>');
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
            headers: window.SES && window.SES.csrf ? window.SES.csrf.header() : {},
            success: function (res) {
                $('#saveRequestBtn').prop('disabled', false);
                if (res.code === 200) {
                    if (window.Toast) window.Toast.success(sdT('servicedesk.msg.created'));
                    bootstrap.Modal.getInstance($('#newRequestModal')[0]).hide();
                    $('#newRequestForm')[0].reset();
                    loadRequests(currentPage);
                } else {
                    if (window.Toast) window.Toast.error(res.message || sdT('servicedesk.msg.createFailed'));
                }
            },
            error: function (xhr) {
                $('#saveRequestBtn').prop('disabled', false);
                const msg = xhr.responseJSON ? xhr.responseJSON.message : sdT('servicedesk.msg.createError');
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
            headers: window.SES && window.SES.csrf ? window.SES.csrf.header() : {},
            success: function (res) {
                if (res.code === 200) {
                    renderDetail(res.data);
                } else {
                    if (window.Toast) window.Toast.error(res.message || sdT('servicedesk.msg.detailLoadFailed'));
                }
            },
            error: function () {
                if (window.Toast) window.Toast.error(sdT('servicedesk.msg.detailLoadFailed'));
            }
        });
    }

    function renderDetail(data) {
        $('#breadcrumbReqNo').text(data.requestNo);
        $('#requestNoBadge').text(data.requestNo);
        $('#statusBadge').replaceWith(getStatusBadge(data.status));
        $('#priorityBadge').replaceWith(getPriorityBadge(data.priority));
        $('#portalVisibilityBadge').html(getPortalVisibilityBadge(data.portalVisible));
        $('#categoryBadge').text(categoryLabel(data.category));
        $('#subjectHeader').text(data.subject);
        $('#customerName').text(data.customerName || '-');
        $('#createdAt').text(formatDateTime(data.createdAt));
        $('#ownerUserName').text(data.ownerUserName || sdT('servicedesk.detail.unassigned'));
        $('#descriptionText').text(data.description);

        // メタデータ
        $('#metaContactName').text(data.contactName || '-');
        $('#metaContractCode').text(data.contractCode || '-');
        $('#metaProjectName').text(data.projectName || '-');
        $('#metaEngineerName').text(data.engineerName || '-');
        $('#metaChannel').text(channelLabel(data.channel));
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
            $('#csatCommentText').text(data.csatComment || sdT('servicedesk.detail.noCsatComment'));
        } else {
            $('#csatCard').addClass('d-none');
        }
    }

    function statusButton(cls, status, icon, labelKey) {
        return `<button class="btn ${cls} btn-sm btn-status" data-status="${status}"><i class="bi ${icon} me-1"></i>${escapeHtml(sdT(labelKey))}</button>`;
    }

    function renderActionButtons(data) {
        const $btns = $('#statusActionButtons').empty();
        const s = data.status;

        if (s === 'RECEIVED') {
            $btns.append(statusButton('btn-primary', 'IN_PROGRESS', 'bi-play-fill', 'servicedesk.action.start'));
            $btns.append(statusButton('btn-outline-warning', 'WAITING_CUSTOMER', 'bi-pause-fill', 'servicedesk.status.waitingCustomer'));
            $btns.append(statusButton('btn-outline-secondary', 'CLOSED', 'bi-x-circle', 'servicedesk.status.closed'));
        } else if (s === 'IN_PROGRESS') {
            $btns.append(statusButton('btn-warning', 'WAITING_CUSTOMER', 'bi-pause-fill', 'servicedesk.status.waitingCustomer'));
            $btns.append(statusButton('btn-success', 'RESOLVED', 'bi-check-circle', 'servicedesk.action.resolve'));
            $btns.append(statusButton('btn-outline-secondary', 'CLOSED', 'bi-x-circle', 'servicedesk.status.closed'));
        } else if (s === 'WAITING_CUSTOMER') {
            $btns.append(statusButton('btn-primary', 'IN_PROGRESS', 'bi-play-fill', 'servicedesk.action.resume'));
            $btns.append(statusButton('btn-success', 'RESOLVED', 'bi-check-circle', 'servicedesk.action.resolve'));
            $btns.append(statusButton('btn-outline-secondary', 'CLOSED', 'bi-x-circle', 'servicedesk.status.closed'));
        } else if (s === 'RESOLVED') {
            $btns.append(statusButton('btn-outline-secondary', 'CLOSED', 'bi-check-all', 'servicedesk.action.closeComplete'));
            $btns.append(statusButton('btn-outline-danger', 'REOPENED', 'bi-arrow-repeat', 'servicedesk.status.reopened'));
        } else if (s === 'CLOSED') {
            $btns.append(statusButton('btn-outline-danger', 'REOPENED', 'bi-arrow-repeat', 'servicedesk.status.reopened'));
        }

        $btns.find('.btn-status').on('click', function () {
            const nextStatus = $(this).data('status');
            changeStatus(data.id, nextStatus, data.version);
        });
    }

    function changeStatus(id, targetStatus, version) {
        Swal.fire({
            title: sdT('servicedesk.statusChange.title', statusLabel(targetStatus)),
            input: 'textarea',
            inputLabel: sdT('servicedesk.statusChange.inputLabel'),
            inputPlaceholder: sdT('servicedesk.statusChange.placeholder'),
            showCancelButton: true,
            confirmButtonText: sdT('servicedesk.statusChange.confirm'),
            cancelButtonText: sdT('common.cancel')
        }).then(function (result) {
            if (result.isConfirmed) {
                const payload = {
                    targetStatus: targetStatus,
                    reason: result.value || sdT('servicedesk.statusChange.defaultReason'),
                    version: version
                };
                $.ajax({
                    url: `/api/service-desk/requests/${id}/status`,
                    type: 'POST',
                    contentType: 'application/json',
                    data: JSON.stringify(payload),
                    headers: window.SES && window.SES.csrf ? window.SES.csrf.header() : {},
                    success: function (res) {
                        if (res.code === 200) {
                            if (window.Toast) window.Toast.success(sdT('servicedesk.msg.statusUpdated'));
                            loadDetail(id);
                        } else {
                            if (window.Toast) window.Toast.error(res.message || sdT('error.updateFailed'));
                        }
                    },
                    error: function (xhr) {
                        const msg = xhr.responseJSON ? xhr.responseJSON.message : sdT('error.updateFailed');
                        if (window.Toast) window.Toast.error(msg);
                    }
                });
            }
        });
    }

    function renderSlaCard(clock) {
        if (!clock) {
            $('#slaCardBody').html('<div class="text-secondary small">' + escapeHtml(sdT('servicedesk.sla.notApplied')) + '</div>');
            return;
        }

        const isBreached = clock.resolveBreached;
        const isPaused = clock.status === 'PAUSED';
        let cardClass = isBreached ? 'sla-card-breached' : (isPaused ? 'sla-card-paused' : 'sla-card-running');
        $('#slaCard').removeClass('sla-card-running sla-card-paused sla-card-breached').addClass(cardClass);

        const breachedBadge = '<span class="badge bg-danger">' + escapeHtml(sdT('servicedesk.sla.breached')) + '</span>';
        const achievedBadge = '<span class="badge bg-success">' + escapeHtml(sdT('servicedesk.sla.completed')) + '</span>';

        const respDeadline = formatDateTime(clock.responseDeadline);
        const respActual = clock.firstRespondedAt ? formatDateTime(clock.firstRespondedAt) : sdT('servicedesk.sla.notResponded');
        const respBreachedBadge = clock.responseBreached ? breachedBadge : achievedBadge;

        const resolveDeadline = formatDateTime(clock.resolveDeadline);
        const resolveActual = clock.resolvedAt ? formatDateTime(clock.resolvedAt)
            : (clock.status === 'COMPLETED' ? sdT('servicedesk.sla.done') : sdT('servicedesk.status.inProgress'));
        const resolveBreachedBadge = clock.resolveBreached ? breachedBadge : (clock.status === 'COMPLETED' ? achievedBadge : '');

        const pauseInfo = clock.totalPauseMinutes > 0
            ? `<div class="text-secondary small mt-1"><i class="bi bi-pause-circle me-1"></i>${escapeHtml(sdT('servicedesk.sla.totalPause', clock.totalPauseMinutes))}</div>` : '';

        const html = `
            <div class="mb-3">
                <div class="d-flex justify-content-between align-items-center mb-1">
                    <span class="text-secondary small">${escapeHtml(sdT('servicedesk.sla.responseTarget'))}</span>
                    ${clock.firstRespondedAt ? respBreachedBadge : ''}
                </div>
                <div class="text-white small">${escapeHtml(sdT('servicedesk.sla.deadline', respDeadline))}</div>
                <div class="text-secondary small">${escapeHtml(sdT('servicedesk.sla.actual', respActual))}</div>
            </div>
            <div class="border-top border-dark pt-2">
                <div class="d-flex justify-content-between align-items-center mb-1">
                    <span class="text-secondary small">${escapeHtml(sdT('servicedesk.sla.resolveTarget'))}</span>
                    ${resolveBreachedBadge}
                </div>
                <div class="text-white small fw-bold">${escapeHtml(sdT('servicedesk.sla.deadline', resolveDeadline))}</div>
                <div class="text-secondary small">${escapeHtml(sdT('servicedesk.sla.actual', resolveActual))}</div>
                ${pauseInfo}
            </div>
        `;
        $('#slaCardBody').html(html);
    }

    function renderComments(comments) {
        const $container = $('#commentsContainer').empty();
        if (comments.length === 0) {
            $container.html('<div class="text-center py-3 text-secondary">' + escapeHtml(sdT('servicedesk.comment.empty')) + '</div>');
            return;
        }

        comments.forEach(function (c) {
            const isInternal = c.visibility === 'INTERNAL';
            const boxClass = isInternal ? 'comment-internal' : 'comment-portal';
            const badge = isInternal
                ? '<span class="badge bg-warning text-dark me-2"><i class="bi bi-lock-fill me-1"></i>' + escapeHtml(sdT('servicedesk.comment.internalBadge')) + '</span>'
                : '<span class="badge bg-primary me-2"><i class="bi bi-globe me-1"></i>' + escapeHtml(sdT('servicedesk.comment.portalBadge')) + '</span>';
            const author = c.authorType === 'INTERNAL_USER'
                ? sdT('servicedesk.comment.authorInternal') : sdT('servicedesk.comment.authorCustomer');

            const html = `
                <div class="card border-0 mb-3 ${boxClass} p-3 rounded shadow-sm">
                    <div class="d-flex justify-content-between align-items-center mb-2">
                        <div>
                            ${badge}
                            <strong class="text-white">${escapeHtml(c.authorName)}</strong>
                            <span class="text-secondary small ms-2">${escapeHtml(author)}</span>
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
            if (window.Toast) window.Toast.error(sdT('servicedesk.comment.required'));
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
            headers: window.SES && window.SES.csrf ? window.SES.csrf.header() : {},
            success: function (res) {
                $('#submitCommentBtn').prop('disabled', false);
                if (res.code === 200) {
                    if (window.Toast) window.Toast.success(sdT('servicedesk.comment.posted'));
                    $('#commentTextInput').val('');
                    loadDetail(requestId);
                } else {
                    if (window.Toast) window.Toast.error(res.message || sdT('servicedesk.comment.postFailed'));
                }
            },
            error: function () {
                $('#submitCommentBtn').prop('disabled', false);
                if (window.Toast) window.Toast.error(sdT('servicedesk.comment.postError'));
            }
        });
    }
}

// ユーティリティ
const SD_STATUS_LABEL_KEYS = {
    RECEIVED: 'servicedesk.status.received',
    IN_PROGRESS: 'servicedesk.status.inProgress',
    WAITING_CUSTOMER: 'servicedesk.status.waitingCustomer',
    RESOLVED: 'servicedesk.status.resolved',
    CLOSED: 'servicedesk.status.closed',
    REOPENED: 'servicedesk.status.reopened'
};

const SD_CATEGORY_LABEL_KEYS = {
    CONTRACT: 'servicedesk.category.contract',
    BILLING: 'servicedesk.category.billing',
    ATTENDANCE: 'servicedesk.category.attendance',
    QUALITY: 'servicedesk.category.quality',
    SYSTEM: 'servicedesk.category.system',
    OTHER: 'servicedesk.category.other'
};

const SD_CHANNEL_LABEL_KEYS = {
    PORTAL: 'servicedesk.channel.portal',
    EMAIL: 'servicedesk.channel.email',
    PHONE: 'servicedesk.channel.phone',
    MEETING: 'servicedesk.channel.meeting',
    INTERNAL: 'servicedesk.channel.internal'
};

/** 既知のコードは表示名にし、未知のコードはそのまま出す（未登録keyを表示しない）。 */
function labelFor(keys, code) {
    if (!code) return '-';
    return keys[code] ? sdT(keys[code]) : String(code);
}

function statusLabel(status) {
    return labelFor(SD_STATUS_LABEL_KEYS, status);
}

function categoryLabel(category) {
    return labelFor(SD_CATEGORY_LABEL_KEYS, category);
}

function channelLabel(channel) {
    return labelFor(SD_CHANNEL_LABEL_KEYS, channel);
}

function getStatusBadge(status) {
    switch (status) {
        case 'RECEIVED':
            return '<span class="badge bg-primary">' + escapeHtml(statusLabel(status)) + ' (RECEIVED)</span>';
        case 'IN_PROGRESS':
            return '<span class="badge bg-info text-dark">' + escapeHtml(statusLabel(status)) + ' (IN_PROGRESS)</span>';
        case 'WAITING_CUSTOMER':
            return '<span class="badge bg-warning text-dark">' + escapeHtml(statusLabel(status)) + ' (WAITING)</span>';
        case 'RESOLVED':
            return '<span class="badge bg-success">' + escapeHtml(statusLabel(status)) + ' (RESOLVED)</span>';
        case 'CLOSED':
            return '<span class="badge bg-secondary">' + escapeHtml(statusLabel(status)) + ' (CLOSED)</span>';
        default:
            return `<span class="badge bg-dark">${escapeHtml(status || '-')}</span>`;
    }
}

function getPriorityBadge(priority) {
    switch (priority) {
        case 'P0':
            return '<span class="badge bg-danger fw-bold">' + escapeHtml(sdT('servicedesk.priority.p0')) + '</span>';
        case 'P1':
            return '<span class="badge bg-warning text-dark fw-bold">' + escapeHtml(sdT('servicedesk.priority.p1')) + '</span>';
        case 'P2':
            return '<span class="badge bg-info text-dark">' + escapeHtml(sdT('servicedesk.priority.p2')) + '</span>';
        case 'P3':
            return '<span class="badge bg-secondary">' + escapeHtml(sdT('servicedesk.priority.p3')) + '</span>';
        default:
            return `<span class="badge bg-dark">${escapeHtml(priority || '-')}</span>`;
    }
}

/**
 * 顧客ポータルへ出す範囲の問い合わせかを示すバッジ（W2 r2 P3-5）。portalVisible はサーバー側の公開範囲policyの判定
 * （portal起票、または公開開始時刻以降の作成）。顧客ポータルの子スイッチがOFFの間は「顧客公開対象」でも顧客には
 * 表示されないため、title の補足文で示す。値が無い場合は社内限定として扱う（公開側へ倒さない）。
 */
function getPortalVisibilityBadge(portalVisible) {
    const visible = portalVisible === true;
    const label = SES.i18n.t(visible ? 'servicedesk.portalVisibility.visible' : 'servicedesk.portalVisibility.internal');
    const hint = SES.i18n.t(visible ? 'servicedesk.portalVisibility.visibleHint'
        : 'servicedesk.portalVisibility.internalHint');
    const cls = visible ? 'bg-warning text-dark' : 'bg-secondary';
    const icon = visible ? 'bi-eye' : 'bi-eye-slash';
    return `<span class="badge ${cls}" data-portal-visible="${visible}" title="${escapeHtml(hint)}">`
        + `<i class="bi ${icon} me-1" aria-hidden="true"></i>${escapeHtml(label)}</span>`;
}

function formatDateTime(dtStr) {
    if (!dtStr) return '-';
    return dtStr.replace('T', ' ').substring(0, 16);
}

function escapeHtml(str) {
    if (str === null || str === undefined || str === '') return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}
