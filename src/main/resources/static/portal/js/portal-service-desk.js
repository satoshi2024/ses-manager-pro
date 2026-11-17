/**
 * 顧客ポータル サービスデスク・問い合わせ管理 JS
 *
 * 文言は portal/customer/index.html が server 側の locale で解決して渡す
 * window.PORTAL_SERVICE_DESK_MESSAGES から取る（portal画面は /js/i18n.js を読み込まない。RB-I18N P3-N2-8）。
 * 詳細・返信は専用のpage routeを持たず、一覧と同じ画面のモーダルで詳細APIから描画する（P3-N2-9）。
 */
(function (window, $) {
    'use strict';

    const PORTAL_CSRF_COOKIE = 'XSRF-TOKEN-PORTAL';
    const PORTAL_CSRF_HEADER = 'X-XSRF-TOKEN-PORTAL';

    const STATUS_BADGES = {
        RECEIVED: ['bg-primary', 'portal.serviceDesk.status.received'],
        IN_PROGRESS: ['bg-info text-dark', 'portal.serviceDesk.status.inProgress'],
        WAITING_CUSTOMER: ['bg-warning text-dark', 'portal.serviceDesk.status.waitingCustomer'],
        RESOLVED: ['bg-success', 'portal.serviceDesk.status.resolved'],
        CLOSED: ['bg-secondary', 'portal.serviceDesk.status.closed']
    };

    const CATEGORY_KEYS = {
        CONTRACT: 'servicedesk.category.contract',
        BILLING: 'servicedesk.category.billing',
        ATTENDANCE: 'servicedesk.category.attendance',
        QUALITY: 'servicedesk.category.quality',
        SYSTEM: 'servicedesk.category.system',
        OTHER: 'servicedesk.category.other'
    };

    /** 文言を引く。未登録keyはkey名をそのまま返す（{0}等は引数で置換する）。 */
    function t(key, ...args) {
        const messages = window.PORTAL_SERVICE_DESK_MESSAGES || {};
        const template = typeof messages[key] === 'string' && messages[key] ? messages[key] : key;
        return template.replace(/\{(\d+)\}/g, function (match, index) {
            return args[index] === undefined ? match : String(args[index]);
        });
    }

    function readCookie(name) {
        const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
        return match ? decodeURIComponent(match[1]) : null;
    }

    function csrfHeader() {
        const token = readCookie(PORTAL_CSRF_COOKIE);
        const headers = {};
        if (token) {
            headers[PORTAL_CSRF_HEADER] = token;
        }
        return headers;
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

    function formatDateTime(dtStr) {
        if (!dtStr) return '-';
        return dtStr.replace('T', ' ').substring(0, 16);
    }

    function categoryLabel(category) {
        if (!category) return '-';
        return CATEGORY_KEYS[category] ? t(CATEGORY_KEYS[category]) : String(category);
    }

    function getStatusBadge(status) {
        const badge = STATUS_BADGES[status];
        if (!badge) {
            return `<span class="badge bg-secondary">${escapeHtml(status || '-')}</span>`;
        }
        return `<span class="badge ${badge[0]}">${escapeHtml(t(badge[1]))}</span>`;
    }

    function init() {
        // タブ切り替えイベント
        $('#tabBtnServiceDesk').on('click', function () {
            loadRequests();
        });

        if ($('#tab-service-desk').hasClass('active') || !$('#tab-service-desk').hasClass('d-none')) {
            loadRequests();
        }

        // 新規起票フォーム submit
        $('#portalNewRequestForm').on('submit', function (e) {
            e.preventDefault();
            submitNewRequest();
        });

        // CSATフォーム submit
        $('#portalCsatForm').on('submit', function (e) {
            e.preventDefault();
            submitCsat();
        });

        // 返信フォーム submit（詳細モーダル）
        $('#portalReplyForm').on('submit', function (e) {
            e.preventDefault();
            submitReply();
        });
    }

    function loadRequests() {
        const $list = $('#serviceDeskList');
        $list.html(`<div class="text-muted small py-3">${escapeHtml(t('common.loading'))}</div>`);

        $.ajax({
            url: '/api/portal/customer/service-desk/requests',
            type: 'GET',
            headers: csrfHeader(),
            success: function (res) {
                if (res.code === 200) {
                    renderRequests(res.data.records || []);
                } else {
                    $list.html(`<div class="alert alert-danger">${escapeHtml(res.message || t('portal.serviceDesk.loadFailed'))}</div>`);
                }
            },
            error: function () {
                $list.html(`<div class="alert alert-danger">${escapeHtml(t('portal.serviceDesk.networkError'))}</div>`);
            }
        });
    }

    function renderRequests(records) {
        const $list = $('#serviceDeskList').empty();
        if (records.length === 0) {
            $list.html(`<div class="text-muted small py-4 text-center">${escapeHtml(t('portal.serviceDesk.empty'))}</div>`);
            return;
        }

        records.forEach(function (r) {
            let csatAction = '';
            if (r.csatAnswerable) {
                csatAction = `
                    <button type="button" class="btn btn-sm btn-outline-warning btn-open-csat ms-2" data-id="${escapeHtml(r.id)}" data-no="${escapeHtml(r.requestNo)}">
                        ${escapeHtml(t('portal.serviceDesk.csat.answer'))}
                    </button>
                `;
            } else if (r.csatScore) {
                csatAction = `
                    <span class="badge bg-light text-dark ms-2">${escapeHtml(t('portal.serviceDesk.csat.rated', '★'.repeat(r.csatScore)))}</span>
                `;
            }

            const item = `
                <div class="portal-list-item card mb-3 p-3 border shadow-sm">
                    <div class="d-flex justify-content-between align-items-start flex-wrap gap-2">
                        <div>
                            <div class="d-flex align-items-center gap-2 mb-1">
                                <strong class="text-primary">${escapeHtml(r.requestNo)}</strong>
                                ${getStatusBadge(r.status)}
                                <span class="badge bg-secondary">${escapeHtml(categoryLabel(r.category))}</span>
                            </div>
                            <h5 class="mb-1 fw-bold">
                                <button type="button" class="btn btn-link p-0 text-dark text-decoration-none text-start fw-bold btn-open-detail" data-id="${escapeHtml(r.id)}">${escapeHtml(r.subject)}</button>
                            </h5>
                            <p class="text-muted small mb-0 text-truncate" style="max-width: 600px;">${escapeHtml(r.description)}</p>
                        </div>
                        <div class="text-end">
                            <div class="text-muted small mb-2">${escapeHtml(t('portal.serviceDesk.createdAt', formatDateTime(r.createdAt)))}</div>
                            <button type="button" class="btn btn-sm btn-outline-primary btn-open-detail" data-id="${escapeHtml(r.id)}">
                                ${escapeHtml(t('portal.serviceDesk.detailReply'))}
                            </button>
                            ${csatAction}
                        </div>
                    </div>
                </div>
            `;
            $list.append(item);
        });

        $list.find('.btn-open-detail').on('click', function () {
            openDetail($(this).data('id'));
        });

        $list.find('.btn-open-csat').on('click', function () {
            const reqId = $(this).data('id');
            $('#csatRequestId').val(reqId);
            $('#portalCsatModalError').addClass('d-none').text('');
            $('#portalCsatForm')[0].reset();
            new bootstrap.Modal($('#portalCsatModal')[0]).show();
        });
    }

    /** 詳細モーダルを開く。表示中の内容は毎回消してからAPIの応答で描き直す（前の問い合わせの内容を残さない）。 */
    function openDetail(requestId) {
        clearDetail();
        $('#portalReplyRequestId').val(requestId);
        bootstrap.Modal.getOrCreateInstance($('#portalRequestDetailModal')[0]).show();
        loadDetail(requestId);
    }

    function clearDetail() {
        $('#portalRequestDetailError').addClass('d-none').text('');
        $('#portalReplyError').addClass('d-none').text('');
        $('#portalReplyText').val('');
        $('#portalRequestDetailNo, #portalRequestDetailSubject, #portalRequestDetailCategory, #portalRequestDetailCreatedAt, #portalRequestDetailCsatStars, #portalRequestDetailCsatComment').text('');
        $('#portalRequestDetailStatus').empty();
        $('#portalRequestDetailDescription').text(t('common.loading'));
        $('#portalRequestDetailComments').empty();
        $('#portalRequestDetailCsat').addClass('d-none');
    }

    function loadDetail(requestId) {
        $.ajax({
            url: `/api/portal/customer/service-desk/requests/${encodeURIComponent(requestId)}`,
            type: 'GET',
            headers: csrfHeader(),
            success: function (res) {
                if (String($('#portalReplyRequestId').val()) !== String(requestId)) return;
                if (res.code === 200 && res.data) {
                    renderDetail(res.data);
                } else {
                    showDetailError(res.message || t('portal.serviceDesk.detail.loadFailed'));
                }
            },
            error: function (xhr) {
                if (String($('#portalReplyRequestId').val()) !== String(requestId)) return;
                showDetailError(xhr.responseJSON && xhr.responseJSON.message
                    ? xhr.responseJSON.message : t('portal.serviceDesk.networkError'));
            }
        });
    }

    function showDetailError(message) {
        $('#portalRequestDetailDescription').text('');
        $('#portalRequestDetailError').removeClass('d-none').text(message);
    }

    function renderDetail(data) {
        $('#portalRequestDetailNo').text(data.requestNo || '');
        $('#portalRequestDetailStatus').html(getStatusBadge(data.status));
        $('#portalRequestDetailSubject').text(data.subject || '');
        $('#portalRequestDetailCategory').text(t('portal.serviceDesk.detail.category', categoryLabel(data.category)));
        $('#portalRequestDetailCreatedAt').text(t('portal.serviceDesk.createdAt', formatDateTime(data.createdAt)));
        $('#portalRequestDetailDescription').text(data.description || '');

        const $comments = $('#portalRequestDetailComments').empty();
        const comments = data.comments || [];
        if (comments.length === 0) {
            $comments.html(`<div class="text-muted small py-2">${escapeHtml(t('portal.serviceDesk.detail.noComments'))}</div>`);
        } else {
            comments.forEach(function (c) {
                const isCustomer = c.authorType === 'PORTAL_USER';
                const label = isCustomer ? t('portal.serviceDesk.detail.authorCustomer') : t('portal.serviceDesk.detail.authorSupport');
                $comments.append(`
                    <div class="card mb-2 p-3 ${isCustomer ? 'bg-light' : 'bg-primary-subtle'} border">
                        <div class="d-flex justify-content-between align-items-center mb-1 flex-wrap gap-1">
                            <strong class="text-dark">${escapeHtml(c.authorName)} ${escapeHtml(label)}</strong>
                            <span class="text-muted small">${escapeHtml(formatDateTime(c.createdAt))}</span>
                        </div>
                        <div style="white-space: pre-wrap;">${escapeHtml(c.commentText)}</div>
                    </div>
                `);
            });
        }

        if (data.csatScore) {
            const score = Math.max(0, Math.min(5, Number(data.csatScore) || 0));
            $('#portalRequestDetailCsatStars').text('★'.repeat(score) + '☆'.repeat(5 - score));
            $('#portalRequestDetailCsatComment').text(data.csatComment || t('portal.serviceDesk.detail.noCsatComment'));
            $('#portalRequestDetailCsat').removeClass('d-none');
        }
    }

    function submitReply() {
        const requestId = $('#portalReplyRequestId').val();
        const text = $('#portalReplyText').val();
        if (!requestId || !text || !text.trim()) return;

        $('#btnSubmitPortalReply').prop('disabled', true);
        $('#portalReplyError').addClass('d-none').text('');

        $.ajax({
            url: `/api/portal/customer/service-desk/requests/${encodeURIComponent(requestId)}/comments`,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ commentText: text }),
            headers: csrfHeader(),
            success: function (res) {
                $('#btnSubmitPortalReply').prop('disabled', false);
                if (res.code === 200) {
                    $('#portalReplyText').val('');
                    loadDetail(requestId);
                    loadRequests();
                } else {
                    $('#portalReplyError').removeClass('d-none').text(res.message || t('portal.serviceDesk.detail.replyFailed'));
                }
            },
            error: function (xhr) {
                $('#btnSubmitPortalReply').prop('disabled', false);
                const msg = xhr.responseJSON && xhr.responseJSON.message
                    ? xhr.responseJSON.message : t('portal.serviceDesk.detail.replyFailed');
                $('#portalReplyError').removeClass('d-none').text(msg);
            }
        });
    }

    function submitNewRequest() {
        const payload = {
            category: $('#portalNewCategory').val(),
            priority: $('#portalNewPriority').val(),
            subject: $('#portalNewSubject').val(),
            description: $('#portalNewDescription').val()
        };

        $('#btnSubmitPortalReq').prop('disabled', true);
        $('#portalReqModalError').addClass('d-none').text('');

        $.ajax({
            url: '/api/portal/customer/service-desk/requests',
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify(payload),
            headers: csrfHeader(),
            success: function (res) {
                $('#btnSubmitPortalReq').prop('disabled', false);
                if (res.code === 200) {
                    bootstrap.Modal.getInstance($('#portalNewRequestModal')[0]).hide();
                    $('#portalNewRequestForm')[0].reset();
                    loadRequests();
                } else {
                    $('#portalReqModalError').removeClass('d-none').text(res.message || t('portal.serviceDesk.createFailed'));
                }
            },
            error: function (xhr) {
                $('#btnSubmitPortalReq').prop('disabled', false);
                const msg = xhr.responseJSON ? xhr.responseJSON.message : t('portal.serviceDesk.createError');
                $('#portalReqModalError').removeClass('d-none').text(msg);
            }
        });
    }

    function submitCsat() {
        const reqId = $('#csatRequestId').val();
        const score = $('input[name="csatScore"]:checked').val();
        const feedback = $('#csatCommentInput').val();

        $('#btnSubmitCsat').prop('disabled', true);
        $('#portalCsatModalError').addClass('d-none').text('');

        $.ajax({
            url: `/api/portal/customer/service-desk/requests/${encodeURIComponent(reqId)}/csat`,
            type: 'POST',
            contentType: 'application/json',
            data: JSON.stringify({ score: parseInt(score, 10), feedbackComment: feedback }),
            headers: csrfHeader(),
            success: function (res) {
                $('#btnSubmitCsat').prop('disabled', false);
                if (res.code === 200) {
                    bootstrap.Modal.getInstance($('#portalCsatModal')[0]).hide();
                    loadRequests();
                } else {
                    $('#portalCsatModalError').removeClass('d-none').text(res.message || t('portal.serviceDesk.csat.sendFailed'));
                }
            },
            error: function (xhr) {
                $('#btnSubmitCsat').prop('disabled', false);
                const msg = xhr.responseJSON ? xhr.responseJSON.message : t('portal.serviceDesk.csat.sendFailed');
                $('#portalCsatModalError').removeClass('d-none').text(msg);
            }
        });
    }

    window.PortalServiceDesk = {
        init: init,
        loadRequests: loadRequests,
        openDetail: openDetail
    };
})(window, jQuery);
