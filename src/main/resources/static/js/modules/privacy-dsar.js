/**
 * NF07 DSAR 画面（/privacy/dsar 一覧・受付、/privacy/dsar/{id} 詳細・本人確認・名寄せ・範囲確定・終了）。
 * 表示は GET /api/privacy/dsar(/{id})、状態の変更は POST・PUT /api/privacy/dsar/**。
 *
 * - subject 参照は要求本文だけで送る（URL に入れない）。
 * - 状態を変える要求は表示時の rowVersion を送る（別の操作が先に進めていれば 409 になり、再読み込みを促す）。
 * - 候補は subject 参照・裁定・記録の件数だけを表示する（API も記録の内容を返さない）。
 * - 依頼者の連絡先・本人確認書類の本体は入力させない（外部の記録の参照 ID だけ）。
 */
(function () {
    'use strict';

    var REFERENCE = /^[A-Za-z0-9][A-Za-z0-9._:#\/-]{0,127}$/;
    var POSITIVE_ID = /^[1-9][0-9]{0,17}$/;
    var list = {page: 1, size: 20, total: 0};
    var detail = null;

    function t(key) {
        return SES.i18n.t.apply(SES.i18n, arguments);
    }

    function esc(value) {
        return SES.escapeHtml(value === null || value === undefined ? '' : String(value));
    }

    /** 列挙値の表示名。辞書に無い値は値そのものを出す。 */
    function label(prefix, value) {
        if (value === null || value === undefined || value === '') {
            return '-';
        }
        return t(prefix + value, String(value));
    }

    function errorMessage(xhr) {
        return (xhr && xhr.responseJSON && xhr.responseJSON.message) || t('privacyDsar.error.load');
    }

    function fetchJson(url, data) {
        return new Promise(function (resolve, reject) {
            $.ajax({
                url: url,
                method: 'GET',
                data: data,
                success: function (res) {
                    if (res.code === 200) {
                        resolve(res.data);
                    } else {
                        reject(res.message || t('privacyDsar.error.load'));
                    }
                },
                error: function (xhr) {
                    reject(errorMessage(xhr));
                }
            });
        });
    }

    function send(method, url, payload) {
        return new Promise(function (resolve, reject) {
            $.ajax({
                url: url,
                method: method,
                contentType: 'application/json',
                data: JSON.stringify(payload),
                headers: SES.csrf.header(),
                success: function (res) {
                    if (res.code === 200) {
                        resolve(res.data);
                    } else {
                        reject(res.message || t('privacyDsar.error.load'));
                    }
                },
                error: function (xhr) {
                    reject(errorMessage(xhr));
                }
            });
        });
    }

    function confirmThen(text, action) {
        Swal.fire({
            title: t('privacyDsar.confirm.title'),
            text: text,
            icon: 'warning',
            showCancelButton: true,
            confirmButtonText: t('privacyDsar.confirm.ok'),
            cancelButtonText: t('common.cancel')
        }).then(function (result) {
            if (result.isConfirmed) {
                action();
            }
        });
    }

    function renderReadiness(readiness, identityTarget, dueTarget) {
        if (!readiness) {
            return;
        }
        if (readiness.identityReady) {
            $(identityTarget).text(t('privacyDsar.readiness.ready'));
        } else {
            var missing = (readiness.missing || []).map(function (code) {
                return t('privacyDsar.readiness.' + code, code);
            });
            $(identityTarget).text(t('privacyDsar.readiness.missing', missing.join(', ')));
        }
        if (dueTarget) {
            $(dueTarget).text(readiness.dueRuleConfigured
                ? t('privacyDsar.readiness.dueDays', String(readiness.dueDays))
                : t('privacyDsar.readiness.dueNotConfigured'));
        }
    }

    function dueBadge(item) {
        if (!item.dueDate) {
            return '<span class="text-secondary">' + esc(t('privacyDsar.due.none')) + '</span>';
        }
        var badge = '';
        if (item.overdue) {
            badge = ' <span class="badge bg-danger">' + esc(t('privacyDsar.due.overdue')) + '</span>';
        } else if (item.dueSoon) {
            badge = ' <span class="badge bg-warning text-dark">' + esc(t('privacyDsar.due.soon')) + '</span>';
        }
        return '<span class="text-nowrap">' + esc(item.dueDate) + '</span>' + badge;
    }

    // ------------------------------------------------------------ 一覧・受付

    function loadList() {
        fetchJson('/api/privacy/dsar', {status: $('#dsar-filter-status').val() || '', page: list.page, size: list.size})
            .then(function (data) {
                list.total = data.total || 0;
                renderReadiness(data.readiness, '#privacy-dsar-readiness-identity', '#privacy-dsar-readiness-due');
                renderList(data.requests || []);
            })
            .catch(function (message) {
                SES.toast.error(message);
            });
    }

    function renderList(requests) {
        if (requests.length === 0) {
            $('#privacy-dsar-body').html('<tr><td colspan="6" class="text-center text-secondary">'
                + esc(t('privacyDsar.empty')) + '</td></tr>');
        } else {
            $('#privacy-dsar-body').html(requests.map(function (item) {
                return '<tr>'
                    + '<td class="text-nowrap"><a class="link-light" href="/privacy/dsar/' + encodeURIComponent(item.id) + '">'
                    + esc(item.requestNo) + '</a></td>'
                    + '<td>' + esc(label('privacyDsar.type.', item.requestType)) + '</td>'
                    + '<td>' + esc(label('privacyDsar.status.', item.status)) + '</td>'
                    + '<td>' + esc(label('privacyDsar.channel.', item.channel)) + '</td>'
                    + '<td class="text-nowrap">' + esc(item.receivedAt) + '</td>'
                    + '<td>' + dueBadge(item) + '</td>'
                    + '</tr>';
            }).join(''));
        }
        var first = list.total === 0 ? 0 : (list.page - 1) * list.size + 1;
        var last = Math.min(list.total, list.page * list.size);
        $('#privacy-dsar-pager-summary').text(t('privacyDsar.pager.summary', String(list.total), String(first), String(last)));
        $('#privacy-dsar-prev').prop('disabled', list.page <= 1);
        $('#privacy-dsar-next').prop('disabled', last >= list.total);
    }

    function submitIntake(event) {
        event.preventDefault();
        var ticketRef = $.trim($('#dsar-ticket-ref').val());
        var reopenOf = $.trim($('#dsar-reopen-of').val());
        if (!ticketRef) {
            SES.toast.error(t('privacyDsar.validation.required'));
            return;
        }
        if (!REFERENCE.test(ticketRef)) {
            SES.toast.error(t('privacyDsar.validation.reference'));
            return;
        }
        if (reopenOf && !POSITIVE_ID.test(reopenOf)) {
            SES.toast.error(t('privacyDsar.validation.positiveId'));
            return;
        }
        var payload = {
            requestType: $('#dsar-request-type').val(),
            channel: $('#dsar-channel').val(),
            receivedAt: $('#dsar-received-at').val() || null,
            intakeTicketRef: ticketRef,
            reopenOfRequestId: reopenOf ? Number(reopenOf) : null
        };
        confirmThen(t('privacyDsar.confirm.intake'), function () {
            $('#dsar-intake-submit').prop('disabled', true);
            send('POST', '/api/privacy/dsar', payload).then(function (result) {
                SES.toast.success(t('privacyDsar.toast.saved'));
                window.location.href = '/privacy/dsar/' + encodeURIComponent(result.requestId);
            }).catch(function (message) {
                SES.toast.error(message);
            }).then(function () {
                $('#dsar-intake-submit').prop('disabled', false);
            });
        });
    }

    function initList() {
        $('#privacy-dsar-reload').on('click', loadList);
        $('#dsar-filter-status').on('change', function () {
            list.page = 1;
            loadList();
        });
        $('#searchForm').on('submit', function (event) {
            event.preventDefault();
            list.page = 1;
            loadList();
        });
        $('#privacy-dsar-prev').on('click', function () {
            if (list.page > 1) {
                list.page--;
                loadList();
            }
        });
        $('#privacy-dsar-next').on('click', function () {
            if (list.page * list.size < list.total) {
                list.page++;
                loadList();
            }
        });
        $('#privacy-dsar-intake-form').on('submit', submitIntake);
        loadList();
    }

    // ------------------------------------------------------------ 詳細

    function requestId() {
        return $('#privacy-dsar-detail').attr('data-request-id');
    }

    function loadDetail() {
        fetchJson('/api/privacy/dsar/' + encodeURIComponent(requestId()))
            .then(function (data) {
                detail = data;
                renderDetail();
            })
            .catch(function (message) {
                SES.toast.error(message);
            });
    }

    function can(action) {
        return detail && (detail.nextActions || []).indexOf(action) >= 0;
    }

    function renderDetail() {
        $('#dsar-detail-request-no').text(detail.requestNo);
        $('#dsar-detail-status').text(label('privacyDsar.status.', detail.status));
        $('#dsar-detail-type').text(label('privacyDsar.type.', detail.requestType));
        $('#dsar-detail-channel').text(label('privacyDsar.channel.', detail.channel));
        $('#dsar-detail-received').text(detail.receivedAt || '-');
        $('#dsar-detail-due').html(dueBadge(detail));
        $('#dsar-detail-ticket').text(detail.intakeTicketRef || '-');
        $('#dsar-detail-reopen').text(detail.reopenOfRequestId ? String(detail.reopenOfRequestId) : '-');
        if (detail.closure) {
            $('#dsar-detail-closure').removeClass('d-none').text(t('privacyDsar.detail.closure',
                label('privacyDsar.status.', detail.closure.state), label('privacyDsar.reason.', detail.closure.reasonCode),
                detail.closure.closedAt || '-'));
        } else {
            $('#dsar-detail-closure').addClass('d-none').text('');
        }

        $('#dsar-identity-state').text(label('privacyDsar.identity.', detail.identityState));
        $('#dsar-identity-evidence').text(detail.identityEvidenceRef || '-');
        $('#dsar-identity-rule').text(detail.identityRuleRef || '-');
        $('#dsar-identity-verified-at').text(detail.identityVerifiedAt || '-');
        var needsDecision = (can('VERIFY_IDENTITY') || can('SCOPE')) && detail.readiness && !detail.readiness.identityReady;
        if (needsDecision) {
            var missing = (detail.readiness.missing || []).map(function (code) {
                return t('privacyDsar.readiness.' + code, code);
            });
            $('#dsar-decision-missing').removeClass('d-none').text(t('privacyDsar.readiness.missing', missing.join(', ')));
        } else {
            $('#dsar-decision-missing').addClass('d-none').text('');
        }
        $('#dsar-identity-form').toggleClass('d-none', !can('VERIFY_IDENTITY'));
        $('#dsar-candidate-form').toggleClass('d-none', !can('ADD_CANDIDATE'));
        renderCandidates();

        if (detail.scope) {
            $('#dsar-scope-record').text(t('privacyDsar.detail.scope.done', detail.scope.asOf || '-',
                String(detail.scope.elementCount === null || detail.scope.elementCount === undefined
                    ? '-' : detail.scope.elementCount)));
        } else {
            $('#dsar-scope-record').text('');
        }
        $('#dsar-scope-submit').toggleClass('d-none', !can('SCOPE')).prop('disabled', detail.selectedCount !== 1);
        $('#dsar-close-card').toggleClass('d-none', !(can('BLOCK') || can('CANCEL')));
        $('#dsar-no-actions').toggleClass('d-none', (detail.nextActions || []).length > 0);
    }

    function renderCandidates() {
        var candidates = detail.candidates || [];
        var resolvable = can('RESOLVE_CANDIDATE');
        if (candidates.length === 0) {
            $('#dsar-candidate-body').html('<tr><td colspan="4" class="text-center text-secondary">'
                + esc(t('privacyDsar.candidates.empty')) + '</td></tr>');
        } else {
            $('#dsar-candidate-body').html(candidates.map(function (candidate) {
                var footprint;
                if (!candidate.footprintAvailable) {
                    footprint = '<span class="text-warning">' + esc(t('privacyDsar.candidates.footprintUnavailable')) + '</span>';
                } else {
                    footprint = '<ul class="list-unstyled mb-0">' + (candidate.footprint || []).map(function (entry) {
                        return '<li class="text-break"><code>' + esc(entry.dataElementId) + '</code> '
                            + esc(t('privacyDsar.candidates.rowCount', String(entry.rowCount)))
                            + ' <span class="text-secondary">' + esc(label('privacyDsar.delivery.', entry.deliveryClass)) + '</span></li>';
                    }).join('') + '</ul>';
                }
                var operations = '';
                if (resolvable) {
                    operations = '<div class="d-grid gap-1">'
                        + '<button type="button" class="btn btn-sm btn-outline-success dsar-resolve" data-link="' + esc(candidate.linkId)
                        + '" data-state="SELECTED"' + (candidate.linkState === 'SELECTED' ? ' disabled' : '') + '>'
                        + esc(t('privacyDsar.candidates.select')) + '</button>'
                        + '<button type="button" class="btn btn-sm btn-outline-secondary dsar-resolve" data-link="' + esc(candidate.linkId)
                        + '" data-state="REJECTED"' + (candidate.linkState === 'REJECTED' ? ' disabled' : '') + '>'
                        + esc(t('privacyDsar.candidates.reject')) + '</button>'
                        + '</div>';
                }
                return '<tr>'
                    + '<td class="text-break"><code>' + esc(candidate.subjectRef) + '</code><br><span class="text-secondary">'
                    + esc(label('privacyHold.subjectType.', candidate.subjectType)) + '</span></td>'
                    + '<td>' + esc(label('privacyDsar.link.', candidate.linkState)) + '</td>'
                    + '<td>' + footprint + '</td>'
                    + '<td>' + operations + '</td>'
                    + '</tr>';
            }).join(''));
        }
        $('#dsar-selected-count').text(t('privacyDsar.candidates.selectedCount', String(detail.selectedCount || 0)));
    }

    function mutate(method, path, payload, confirmText) {
        var run = function () {
            send(method, '/api/privacy/dsar/' + encodeURIComponent(requestId()) + path, payload).then(function () {
                SES.toast.success(t('privacyDsar.toast.saved'));
            }).catch(function (message) {
                SES.toast.error(message);
            }).then(function () {
                loadDetail();
            });
        };
        if (confirmText) {
            confirmThen(confirmText, run);
        } else {
            run();
        }
    }

    function submitIdentity(event) {
        event.preventDefault();
        var evidence = $.trim($('#dsar-identity-evidence-ref').val());
        if (!REFERENCE.test(evidence)) {
            SES.toast.error(evidence ? t('privacyDsar.validation.reference') : t('privacyDsar.validation.required'));
            return;
        }
        mutate('POST', '/identity', {rowVersion: detail.rowVersion, identityEvidenceRef: evidence},
            t('privacyDsar.confirm.identity'));
    }

    function submitCandidate(event) {
        event.preventDefault();
        var id = $.trim($('#dsar-candidate-id').val());
        if (!POSITIVE_ID.test(id)) {
            SES.toast.error(t('privacyDsar.validation.positiveId'));
            return;
        }
        mutate('POST', '/candidates', {rowVersion: detail.rowVersion, subjectRef: $('#dsar-candidate-type').val() + ':' + id});
        $('#dsar-candidate-id').val('');
    }

    function resolveCandidate() {
        var button = $(this);
        mutate('PUT', '/candidates/' + encodeURIComponent(button.attr('data-link')),
            {rowVersion: detail.rowVersion, linkState: button.attr('data-state')});
    }

    function submitScope() {
        mutate('POST', '/scope', {rowVersion: detail.rowVersion}, t('privacyDsar.confirm.scope'));
    }

    function submitClose(event) {
        event.preventDefault();
        var option = $('#dsar-close-reason option:selected');
        var evidence = $.trim($('#dsar-close-evidence').val());
        if (evidence && !REFERENCE.test(evidence)) {
            SES.toast.error(t('privacyDsar.validation.reference'));
            return;
        }
        var path = option.attr('data-target') === 'CANCELLED' ? '/cancel' : '/block';
        mutate('POST', path, {rowVersion: detail.rowVersion, reasonCode: option.val(), evidenceRef: evidence || null},
            t('privacyDsar.confirm.close'));
    }

    function initDetail() {
        $('#privacy-dsar-reload').on('click', loadDetail);
        $('#dsar-identity-form').on('submit', submitIdentity);
        $('#dsar-candidate-form').on('submit', submitCandidate);
        $('#dsar-candidate-body').on('click', '.dsar-resolve', resolveCandidate);
        $('#dsar-scope-submit').on('click', submitScope);
        $('#dsar-close-form').on('submit', submitClose);
        loadDetail();
    }

    $(function () {
        if ($('#privacy-dsar-detail').length) {
            initDetail();
        } else if ($('#privacy-dsar-list').length) {
            initList();
        }
    });
})();
