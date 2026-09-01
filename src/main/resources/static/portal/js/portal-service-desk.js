/**
 * 顧客ポータル サービスデスク・問い合わせ管理 JS
 */
(function (window, $) {
    'use strict';

    const PORTAL_CSRF_COOKIE = 'XSRF-TOKEN-PORTAL';
    const PORTAL_CSRF_HEADER = 'X-XSRF-TOKEN-PORTAL';

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
        if (!str) return '';
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

    function getStatusBadge(status) {
        switch (status) {
            case 'RECEIVED':
                return '<span class="badge bg-primary">受付</span>';
            case 'IN_PROGRESS':
                return '<span class="badge bg-info text-dark">対応中</span>';
            case 'WAITING_CUSTOMER':
                return '<span class="badge bg-warning text-dark">確認待ち</span>';
            case 'RESOLVED':
                return '<span class="badge bg-success">解決</span>';
            case 'CLOSED':
                return '<span class="badge bg-secondary">完了</span>';
            default:
                return `<span class="badge bg-secondary">${escapeHtml(status || '-')}</span>`;
        }
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
    }

    function loadRequests() {
        const $list = $('#serviceDeskList');
        $list.html('<div class="text-muted small py-3">読み込み中...</div>');

        $.ajax({
            url: '/api/portal/customer/service-desk/requests',
            type: 'GET',
            headers: csrfHeader(),
            success: function (res) {
                if (res.code === 200) {
                    renderRequests(res.data.records || []);
                } else {
                    $list.html(`<div class="alert alert-danger">${escapeHtml(res.message || 'データ取得に失敗しました')}</div>`);
                }
            },
            error: function () {
                $list.html('<div class="alert alert-danger">通信エラーが発生しました</div>');
            }
        });
    }

    function renderRequests(records) {
        const $list = $('#serviceDeskList').empty();
        if (records.length === 0) {
            $list.html('<div class="text-muted small py-4 text-center">現在、問い合わせ履歴はありません</div>');
            return;
        }

        records.forEach(function (r) {
            let csatAction = '';
            if (r.csatAnswerable) {
                csatAction = `
                    <button type="button" class="btn btn-sm btn-outline-warning btn-open-csat ms-2" data-id="${r.id}" data-no="${escapeHtml(r.requestNo)}">
                        ★ 評価アンケートに回答
                    </button>
                `;
            } else if (r.csatScore) {
                csatAction = `
                    <span class="badge bg-light text-dark ms-2">評価済: ${'★'.repeat(r.csatScore)}</span>
                `;
            }

            const item = `
                <div class="portal-list-item card mb-3 p-3 border shadow-sm">
                    <div class="d-flex justify-content-between align-items-start flex-wrap gap-2">
                        <div>
                            <div class="d-flex align-items-center gap-2 mb-1">
                                <strong class="text-primary">${escapeHtml(r.requestNo)}</strong>
                                ${getStatusBadge(r.status)}
                                <span class="badge bg-secondary">${escapeHtml(r.category || '-')}</span>
                            </div>
                            <h5 class="mb-1 fw-bold">
                                <a href="/portal/customer/service-desk/requests/${r.id}" class="text-dark text-decoration-none">${escapeHtml(r.subject)}</a>
                            </h5>
                            <p class="text-muted small mb-0 text-truncate" style="max-width: 600px;">${escapeHtml(r.description)}</p>
                        </div>
                        <div class="text-end">
                            <div class="text-muted small mb-2">起票日時: ${formatDateTime(r.createdAt)}</div>
                            <a href="/portal/customer/service-desk/requests/${r.id}" class="btn btn-sm btn-outline-primary">
                                詳細・返信
                            </a>
                            ${csatAction}
                        </div>
                    </div>
                </div>
            `;
            $list.append(item);
        });

        $list.find('.btn-open-csat').on('click', function () {
            const reqId = $(this).data('id');
            $('#csatRequestId').val(reqId);
            $('#portalCsatModalError').addClass('d-none').text('');
            $('#portalCsatForm')[0].reset();
            new bootstrap.Modal($('#portalCsatModal')[0]).show();
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
                    $('#portalReqModalError').removeClass('d-none').text(res.message || '起票に失敗しました');
                }
            },
            error: function (xhr) {
                $('#btnSubmitPortalReq').prop('disabled', false);
                const msg = xhr.responseJSON ? xhr.responseJSON.message : '起票処理中にエラーが発生しました';
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
            url: `/api/portal/customer/service-desk/requests/${reqId}/csat`,
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
                    $('#portalCsatModalError').removeClass('d-none').text(res.message || '送信に失敗しました');
                }
            },
            error: function (xhr) {
                $('#btnSubmitCsat').prop('disabled', false);
                const msg = xhr.responseJSON ? xhr.responseJSON.message : '送信に失敗しました';
                $('#portalCsatModalError').removeClass('d-none').text(msg);
            }
        });
    }

    window.PortalServiceDesk = {
        init: init,
        loadRequests: loadRequests
    };
})(window, jQuery);
