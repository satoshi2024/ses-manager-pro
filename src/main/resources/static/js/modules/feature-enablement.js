/**
 * 画面「機能の開放」（feature-enablement-console design §15.2・FE-RQ-011）。
 *
 * - 一覧は DB の状態と実効値（閉じている理由）を分けて出す。条件は最後の評価の結果で、「再評価」は1件だけをその場で評価する
 *   （状態は変えない。1分に1回まで）。
 * - 自分の申請に承認・却下の button を出さず（API も 403）、署名日が今日より後の署名に確認の button を出さない。
 * - 署名者の氏名は一覧に出さず、署名の詳細だけに出す。digest は先頭 16 文字だけ。
 * - 規約: SES.csrf.header()、res.code === 200、Toast、閉鎖・取消しは Swal.fire で確かめる。同梱の library だけを使う。
 */
const FeatureEnablement = (function () {
    'use strict';

    const API = '/api/feature-enablement';
    const state = {catalog: null, units: [], signOffs: [], bundles: [], requestsPage: 1, eventsPage: 1};
    const SIGN_OFF_REASONS = ['DOCUMENT_MISMATCH', 'ROLE_MISMATCH', 'DATE_MISMATCH', 'REFERENCE_MISMATCH', 'OTHER'];
    const REVOKE_REASONS = ['DECISION_WITHDRAWN', 'DOCUMENT_ERROR', 'SECURITY_CONCERN', 'OTHER'];

    function t(key) {
        const args = Array.prototype.slice.call(arguments, 1);
        return SES.i18n.t.apply(SES.i18n, [key].concat(args));
    }

    function esc(value) {
        return SES.escapeHtml(value === null || value === undefined ? '' : String(value));
    }

    function today() {
        const now = new Date();
        const pad = n => String(n).padStart(2, '0');
        return now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate());
    }

    function dateTime(value) {
        return value ? esc(String(value).replace('T', ' ').substring(0, 16)) : '-';
    }

    function label(prefix, code) {
        return t(prefix + code, code);
    }

    function unitName(key, fallback) {
        return t('featureEnablement.unitName.' + key, fallback || key);
    }

    // ---------------------------------------------------------------- 通信

    function request(method, url, body) {
        return new Promise(function (resolve, reject) {
            $.ajax({
                url: url,
                type: method,
                headers: SES.csrf.header(),
                contentType: body === undefined ? undefined : 'application/json',
                data: body === undefined ? undefined : JSON.stringify(body),
                dataType: 'json'
            }).done(function (res) {
                if (res && res.code === 200) {
                    resolve(res.data);
                } else {
                    Toast.error((res && res.message) || t('featureEnablement.error.failed'));
                    reject(res);
                }
            }).fail(function (xhr) {
                const res = xhr.responseJSON;
                Toast.error((res && res.message) || t('featureEnablement.error.failed'));
                reject(res);
            });
        });
    }

    function upload(url, formData) {
        return new Promise(function (resolve, reject) {
            $.ajax({
                url: url,
                type: 'POST',
                headers: SES.csrf.header(),
                data: formData,
                processData: false,
                contentType: false,
                dataType: 'json'
            }).done(function (res) {
                if (res && res.code === 200) {
                    resolve(res.data);
                } else {
                    Toast.error((res && res.message) || t('featureEnablement.error.failed'));
                    reject(res);
                }
            }).fail(function (xhr) {
                const res = xhr.responseJSON;
                Toast.error((res && res.message) || t('featureEnablement.error.failed'));
                reject(res);
            });
        });
    }

    // ---------------------------------------------------------------- 読込み

    function loadAll() {
        return request('GET', API + '/catalog').then(function (catalog) {
            state.catalog = catalog;
            return Promise.all([loadUnits(), loadRequests(state.requestsPage), loadSignOffs(), loadEvents(state.eventsPage)]);
        }).catch(function () { /* Toast で通知済み */ });
    }

    function loadUnits() {
        return request('GET', API + '/units').then(function (units) {
            state.units = units || [];
            renderUnits();
        });
    }

    function loadRequests(page) {
        state.requestsPage = page || 1;
        return request('GET', API + '/requests?page=' + state.requestsPage + '&size=20').then(function (data) {
            renderRequests(data);
        });
    }

    function loadSignOffs() {
        return Promise.all([request('GET', API + '/sign-offs'), request('GET', API + '/sign-off-bundles')])
            .then(function (results) {
                state.signOffs = results[0] || [];
                state.bundles = results[1] || [];
                renderSignOffs();
                renderBundles();
            });
    }

    function loadEvents(page) {
        state.eventsPage = page || 1;
        const params = new URLSearchParams({page: String(state.eventsPage), size: '20'});
        const subjectType = $('#feEventSubjectType').val();
        const subjectKey = ($('#feEventSubjectKey').val() || '').trim();
        const from = $('#feEventFrom').val();
        const to = $('#feEventTo').val();
        if (subjectType) params.set('subjectType', subjectType);
        if (subjectKey) params.set('subjectKey', subjectKey);
        if (from) params.set('from', from);
        if (to) params.set('to', to);
        return request('GET', API + '/events?' + params.toString()).then(renderEvents).catch(function () {});
    }

    // ---------------------------------------------------------------- 開放単位

    function stateBadge(unit) {
        const classes = {
            OPEN: 'bg-success', OPEN_PENDING_RESTART: 'bg-warning text-dark', AUTO_CLOSED: 'bg-danger',
            MANUALLY_CLOSED: 'bg-secondary', CLOSED: 'bg-dark border border-secondary'
        };
        let html = '<span class="badge ' + (classes[unit.state] || 'bg-secondary') + '">'
            + esc(label('featureEnablement.state.', unit.state)) + '</span>';
        if (unit.closedReason) {
            html += '<div class="small text-muted mt-1"><code>' + esc(unit.closedReason) + '</code></div>';
        }
        if (!unit.databaseManaged) {
            html += '<div class="small text-muted mt-1">' + esc(t('featureEnablement.unit.propertyManaged')) + '</div>';
        }
        return html;
    }

    function reasonLabel(code) {
        const prefixes = ['snapshot.stale', 'master.closed', 'signoff.window.closed', 'evaluation.unverifiable',
            'property.disabled', 'source.missing', 'runtime.unverifiable'];
        if (prefixes.indexOf(code) >= 0) {
            return t('featureEnablement.reason.' + code, code);
        }
        if (code.indexOf('overlay.') === 0) {
            return t('featureEnablement.reason.overlay', code) + ' (' + code.substring('overlay.'.length) + ')';
        }
        if (code.indexOf('state.') === 0) {
            return t('featureEnablement.reason.state', code) + ' (' + code.substring('state.'.length) + ')';
        }
        if (code.indexOf('parent.') === 0) {
            return t('featureEnablement.reason.parent', code) + ' (' + code.split('.')[1] + ')';
        }
        return code;
    }

    function effectiveBadge(unit) {
        if (unit.effectiveOpen) {
            return '<span class="badge bg-success">' + esc(t('featureEnablement.effective.open')) + '</span>';
        }
        const reasons = (unit.closedReasons || []).map(function (code) {
            return '<li>' + esc(reasonLabel(code)) + '</li>';
        }).join('');
        return '<span class="badge bg-secondary">' + esc(t('featureEnablement.effective.closed')) + '</span>'
            + (reasons ? '<ul class="small text-muted mb-0 ps-3 mt-1">' + reasons + '</ul>' : '');
    }

    function evaluationSummary(evaluation) {
        if (!evaluation) {
            return '<span class="text-muted small">' + esc(t('featureEnablement.notEvaluated')) + '</span>';
        }
        return '<span class="small">' + esc(t('featureEnablement.evaluation.summary', evaluation.satisfied,
                evaluation.failing, label('featureEnablement.purpose.', evaluation.purpose))) + '</span>'
            + '<div class="small text-muted">' + dateTime(evaluation.evaluatedAt) + '</div>';
    }

    function masterClosed() {
        return state.units.some(function (unit) {
            return (unit.closedReasons || []).indexOf('master.closed') >= 0;
        });
    }

    function renderUnits() {
        const body = $('#featureEnablementUnits');
        $('#featureEnablementMasterAlert').toggleClass('d-none', !masterClosed());
        $('#featureEnablementPropertyAlert').toggleClass('d-none', state.units.some(function (unit) {
            return unit.databaseManaged;
        }));
        if (!state.units.length) {
            body.html('<tr><td colspan="7" class="text-center text-muted py-4">' + esc(t('featureEnablement.empty')) + '</td></tr>');
            return;
        }
        body.html(state.units.map(function (unit) {
            const requestable = unit.databaseManaged && ['CLOSED', 'AUTO_CLOSED', 'MANUALLY_CLOSED'].indexOf(unit.state) >= 0
                && !unit.pendingRequest && !masterClosed();
            const closable = unit.databaseManaged && (unit.state === 'OPEN' || unit.state === 'OPEN_PENDING_RESTART');
            const pending = unit.pendingRequest
                ? '#' + esc(unit.pendingRequest.id) + (unit.pendingRequest.mine ? ' ' + esc(t('featureEnablement.request.mine')) : '')
                : '-';
            const openUntil = unit.openUntil
                ? esc(unit.openUntil) + '<div class="small text-muted">' + esc(t('featureEnablement.daysRemaining',
                unit.signOffDaysRemaining)) + '</div>' : '-';
            const buttons = ['<button type="button" class="btn btn-sm btn-outline-info" data-fe-action="detail" data-key="'
            + esc(unit.key) + '">' + esc(t('featureEnablement.btn.detail')) + '</button>'];
            if (unit.databaseManaged) {
                buttons.push('<button type="button" class="btn btn-sm btn-outline-light" data-fe-action="evaluate" data-key="'
                    + esc(unit.key) + '">' + esc(t('featureEnablement.btn.evaluate')) + '</button>');
            }
            if (requestable) {
                buttons.push('<button type="button" class="btn btn-sm btn-primary" data-fe-action="request" data-key="'
                    + esc(unit.key) + '">' + esc(t('featureEnablement.btn.request')) + '</button>');
            }
            if (closable) {
                buttons.push('<button type="button" class="btn btn-sm btn-outline-danger" data-fe-action="close" data-key="'
                    + esc(unit.key) + '">' + esc(t('featureEnablement.btn.close')) + '</button>');
            }
            return '<tr>'
                + '<td class="ps-3"><div>' + esc(unitName(unit.key, unit.displayName)) + '</div><div class="small text-muted"><code>'
                + esc(unit.key) + '</code>' + (unit.parentKey ? ' ← ' + esc(unit.parentKey) : '') + '</div></td>'
                + '<td>' + stateBadge(unit) + '</td>'
                + '<td>' + effectiveBadge(unit) + '</td>'
                + '<td>' + pending + '</td>'
                + '<td>' + openUntil + '</td>'
                + '<td>' + evaluationSummary(unit.lastEvaluation) + '</td>'
                + '<td class="text-end pe-3"><div class="d-inline-flex flex-wrap gap-1 justify-content-end">' + buttons.join('')
                + '</div></td></tr>';
        }).join(''));
    }

    function checksTable(evaluation) {
        if (!evaluation) {
            return '<p class="text-muted small">' + esc(t('featureEnablement.notEvaluated')) + '</p>';
        }
        const marks = {SATISFIED: '✓', NOT_APPLICABLE: '–', UNSATISFIED: '✗', UNVERIFIABLE: '?'};
        const rows = evaluation.checks.map(function (check) {
            return '<tr><td>' + esc(marks[check.status] || '?') + ' ' + esc(label('featureEnablement.checkStatus.', check.status))
                + '</td><td><code>' + esc(check.code) + '</code>' + (check.detailCodes && check.detailCodes.length
                    ? '<div class="small text-muted">' + esc(check.detailCodes.join(', ')) + '</div>' : '') + '</td><td>'
                + esc(label('featureEnablement.category.', check.category)) + '</td><td>'
                + esc(label('featureEnablement.scope.', check.scope)) + '</td><td>' + esc(check.validUntil || '-') + '</td></tr>';
        }).join('');
        return '<div class="small text-muted mb-2">' + dateTime(evaluation.evaluatedAt) + ' / '
            + esc(label('featureEnablement.purpose.', evaluation.purpose)) + '</div>'
            + '<div class="table-responsive"><table class="table table-dark table-sm align-middle"><thead><tr class="small text-muted">'
            + '<th>' + esc(t('featureEnablement.detail.status')) + '</th><th>' + esc(t('featureEnablement.detail.code')) + '</th><th>'
            + esc(t('featureEnablement.detail.category')) + '</th><th>' + esc(t('featureEnablement.detail.scope')) + '</th><th>'
            + esc(t('featureEnablement.detail.validUntil')) + '</th></tr></thead><tbody>' + rows + '</tbody></table></div>';
    }

    function showDetail(key) {
        request('GET', API + '/units/' + encodeURIComponent(key)).then(function (detail) {
            const unit = detail.unit;
            const signOffs = detail.signOffs.map(function (signOff) {
                return '<tr><td><code>' + esc(signOff.kindCode) + '</code></td><td>'
                    + esc(label('featureEnablement.kindClass.', signOff.kindClass)) + '</td><td>'
                    + esc(label('featureEnablement.requiredStatus.', signOff.status)) + '</td><td>'
                    + esc(signOff.signedOn || '-') + ' 〜 ' + esc(signOff.expiresOn || '-') + '</td><td>'
                    + (signOff.daysRemaining === null || signOff.daysRemaining === undefined ? '-'
                        : esc(t('featureEnablement.daysRemaining', signOff.daysRemaining))) + '</td><td>'
                    + esc(signOff.bundleKind || '') + (signOff.conditionSetting ? ' ' + esc(t('featureEnablement.detail.conditional',
                        signOff.conditionSetting)) : '') + '</td></tr>';
            }).join('');
            const keys = detail.keys.map(function (k) {
                const presence = k.present === true ? t('featureEnablement.key.present')
                    : k.present === false ? t('featureEnablement.key.missing') : t('featureEnablement.key.unknown');
                return '<li><code>' + esc(k.name) + '</code>: ' + esc(presence) + '</li>';
            }).join('');
            const requests = detail.requests.map(function (r) {
                return '<li>#' + esc(r.id) + ' ' + esc(label('featureEnablement.requestStatus.', r.status)) + ' '
                    + dateTime(r.requestedAt) + (r.mine ? ' ' + esc(t('featureEnablement.request.mine')) : '') + '</li>';
            }).join('');
            $('#featureEnablementDetailTitle').text(unitName(unit.key, unit.displayName) + ' (' + unit.key + ')');
            $('#featureEnablementDetailBody').html(
                '<div class="row g-3 mb-3"><div class="col-md-6">' + stateBadge(unit) + '</div><div class="col-md-6">'
                + effectiveBadge(unit) + '</div></div>'
                + '<h6>' + esc(t('featureEnablement.detail.conditions')) + '</h6>' + checksTable(unit.lastEvaluation)
                + '<h6 class="mt-3">' + esc(t('featureEnablement.detail.signOffs')) + '</h6>'
                + '<div class="table-responsive"><table class="table table-dark table-sm align-middle"><tbody>' + signOffs
                + '</tbody></table></div>'
                + (keys ? '<h6 class="mt-3">' + esc(t('featureEnablement.detail.keys')) + '</h6><ul class="small">' + keys + '</ul>' : '')
                + (detail.children.length ? '<h6 class="mt-3">' + esc(t('featureEnablement.detail.children')) + '</h6><p class="small">'
                    + esc(detail.children.join(', ')) + '</p>' : '')
                + (detail.subgates.length ? '<h6 class="mt-3">' + esc(t('featureEnablement.detail.subgates')) + '</h6><p class="small">'
                    + esc(detail.subgates.join(', ')) + '</p>' : '')
                + '<h6 class="mt-3">' + esc(t('featureEnablement.detail.requests')) + '</h6><ul class="small">'
                + (requests || '<li>' + esc(t('featureEnablement.empty')) + '</li>') + '</ul>');
            bootstrap.Modal.getOrCreateInstance(document.getElementById('featureEnablementDetailModal')).show();
        }).catch(function () {});
    }

    function evaluate(key) {
        request('POST', API + '/units/' + encodeURIComponent(key) + '/evaluate').then(function () {
            Toast.success(t('featureEnablement.toast.evaluated'));
            return loadUnits();
        }).catch(function () {});
    }

    function requestOpen(key) {
        Swal.fire({
            titleText: t('featureEnablement.confirm.request.title', unitName(key)),
            text: t('featureEnablement.confirm.request.text'),
            input: 'textarea',
            inputPlaceholder: t('featureEnablement.field.reasonOptional'),
            inputAttributes: {maxlength: '500'},
            showCancelButton: true,
            confirmButtonText: t('featureEnablement.btn.request'),
            cancelButtonText: t('featureEnablement.btn.cancel')
        }).then(function (result) {
            if (!result.isConfirmed) return;
            request('POST', API + '/requests', {type: 'OPEN', target: key, reason: result.value || null}).then(function () {
                Toast.success(t('featureEnablement.toast.requested'));
                return Promise.all([loadUnits(), loadRequests(1)]);
            }).catch(function () {});
        });
    }

    function closeUnit(key) {
        const options = {};
        (state.catalog ? state.catalog.manualCloseReasons : []).forEach(function (code) {
            options[code] = label('featureEnablement.closeReason.', code);
        });
        Swal.fire({
            titleText: t('featureEnablement.confirm.close.title', unitName(key)),
            text: t('featureEnablement.confirm.close.text'),
            icon: 'warning',
            input: 'select',
            inputOptions: options,
            inputPlaceholder: t('featureEnablement.field.reasonCode'),
            showCancelButton: true,
            confirmButtonColor: '#dc3545',
            confirmButtonText: t('featureEnablement.btn.close'),
            cancelButtonText: t('featureEnablement.btn.cancel'),
            inputValidator: function (value) {
                return value ? undefined : t('featureEnablement.field.reasonCode');
            }
        }).then(function (result) {
            if (!result.isConfirmed) return;
            request('POST', API + '/units/' + encodeURIComponent(key) + '/close', {reason: result.value}).then(function () {
                Toast.success(t('featureEnablement.toast.closed'));
                return Promise.all([loadUnits(), loadRequests(1)]);
            }).catch(function () {});
        });
    }

    // ---------------------------------------------------------------- 申請

    function renderRequests(data) {
        const body = $('#featureEnablementRequests');
        const records = (data && data.records) || [];
        if (!records.length) {
            body.html('<tr><td colspan="9" class="text-center text-muted py-4">' + esc(t('featureEnablement.empty')) + '</td></tr>');
        } else {
            body.html(records.map(function (r) {
                const buttons = [];
                if (r.status === 'PENDING') {
                    if (r.mine) {
                        buttons.push('<button type="button" class="btn btn-sm btn-outline-secondary" data-fe-action="withdraw" data-id="'
                            + esc(r.id) + '" data-version="' + esc(r.version) + '">' + esc(t('featureEnablement.btn.withdraw')) + '</button>');
                    } else {
                        buttons.push('<button type="button" class="btn btn-sm btn-success" data-fe-action="approve" data-id="'
                            + esc(r.id) + '" data-version="' + esc(r.version) + '">' + esc(t('featureEnablement.btn.approve')) + '</button>');
                        buttons.push('<button type="button" class="btn btn-sm btn-outline-danger" data-fe-action="reject" data-id="'
                            + esc(r.id) + '" data-version="' + esc(r.version) + '">' + esc(t('featureEnablement.btn.reject')) + '</button>');
                    }
                }
                return '<tr><td class="ps-3">#' + esc(r.id) + '</td><td>' + esc(label('featureEnablement.requestType.', r.type))
                    + '</td><td><code>' + esc(r.target) + '</code>' + (r.proposedValue ? '<div class="small text-muted">'
                        + esc(r.proposedValue) + '</div>' : '') + '</td><td>' + esc(label('featureEnablement.requestStatus.', r.status))
                    + '</td><td>#' + esc(r.requestedBy) + (r.mine ? ' ' + esc(t('featureEnablement.request.mine')) : '') + '</td><td>'
                    + dateTime(r.requestedAt) + '</td><td>' + dateTime(r.expiresAt) + '</td><td class="small text-muted text-break">'
                    + esc(r.conditionSummary || '') + '</td><td class="text-end pe-3"><div class="d-inline-flex gap-1">'
                    + buttons.join('') + '</div></td></tr>';
            }).join(''));
        }
        renderPager('#featureEnablementRequestsPager', data, 'requests');
    }

    function approve(id, version) {
        Swal.fire({
            titleText: t('featureEnablement.confirm.approve.title', id),
            text: t('featureEnablement.confirm.approve.text'),
            input: 'textarea',
            inputPlaceholder: t('featureEnablement.field.reasonOptional'),
            inputAttributes: {maxlength: '500'},
            showCancelButton: true,
            confirmButtonText: t('featureEnablement.btn.approve'),
            cancelButtonText: t('featureEnablement.btn.cancel')
        }).then(function (result) {
            if (!result.isConfirmed) return;
            request('POST', API + '/requests/' + id + '/approve', {expectedVersion: version, reason: result.value || null})
                .then(function () {
                    Toast.success(t('featureEnablement.toast.approved'));
                }).catch(function () {}).then(refreshAfterDecision);
        });
    }

    function reject(id, version) {
        Swal.fire({
            titleText: t('featureEnablement.confirm.reject.title', id),
            input: 'textarea',
            inputPlaceholder: t('featureEnablement.field.rejectReason'),
            inputAttributes: {maxlength: '500'},
            showCancelButton: true,
            confirmButtonColor: '#dc3545',
            confirmButtonText: t('featureEnablement.btn.reject'),
            cancelButtonText: t('featureEnablement.btn.cancel'),
            inputValidator: function (value) {
                return value && value.trim() ? undefined : t('featureEnablement.field.rejectReason');
            }
        }).then(function (result) {
            if (!result.isConfirmed) return;
            request('POST', API + '/requests/' + id + '/reject', {expectedVersion: version, reason: result.value}).then(function () {
                Toast.success(t('featureEnablement.toast.rejected'));
            }).catch(function () {}).then(refreshAfterDecision);
        });
    }

    function withdraw(id, version) {
        Swal.fire({
            titleText: t('featureEnablement.confirm.withdraw.title', id),
            showCancelButton: true,
            confirmButtonText: t('featureEnablement.btn.withdraw'),
            cancelButtonText: t('featureEnablement.btn.cancel')
        }).then(function (result) {
            if (!result.isConfirmed) return;
            request('POST', API + '/requests/' + id + '/withdraw', {expectedVersion: version}).then(function () {
                Toast.success(t('featureEnablement.toast.withdrawn'));
            }).catch(function () {}).then(refreshAfterDecision);
        });
    }

    function refreshAfterDecision() {
        return Promise.all([loadUnits(), loadRequests(state.requestsPage), loadSignOffs()]).catch(function () {});
    }

    // ---------------------------------------------------------------- 署名・組

    function kindOf(code) {
        return state.catalog ? state.catalog.kinds.find(function (kind) { return kind.code === code; }) : null;
    }

    function isMine(userId) {
        return state.catalog && userId !== null && userId !== undefined && Number(userId) === Number(state.catalog.actorUserId);
    }

    function signOffStatusBadge(status) {
        const classes = {CONFIRMED: 'bg-success', REGISTERED: 'bg-info text-dark', EXPIRED: 'bg-warning text-dark',
            REVOKED: 'bg-danger', REJECTED: 'bg-secondary', SUPERSEDED: 'bg-secondary', PURGED: 'bg-dark border border-secondary'};
        return '<span class="badge ' + (classes[status] || 'bg-secondary') + '">' + esc(label('featureEnablement.signOffStatus.', status))
            + '</span>';
    }

    function renderSignOffs() {
        const body = $('#featureEnablementSignOffs');
        if (!state.signOffs.length) {
            body.html('<tr><td colspan="10" class="text-center text-muted py-4">' + esc(t('featureEnablement.empty')) + '</td></tr>');
            return;
        }
        const now = today();
        body.html(state.signOffs.map(function (s) {
            const kind = kindOf(s.kindCode);
            const mine = isMine(s.registeredBy);
            const buttons = ['<button type="button" class="btn btn-sm btn-outline-info" data-fe-action="signOffDetail" data-id="'
            + esc(s.id) + '">' + esc(t('featureEnablement.btn.detail')) + '</button>'];
            if (s.status !== 'PURGED') {
                buttons.push('<button type="button" class="btn btn-sm btn-outline-light" data-fe-action="document" data-id="'
                    + esc(s.id) + '">' + esc(t('featureEnablement.btn.document')) + '</button>');
            }
            let note = '';
            if (s.status === 'REGISTERED') {
                if (mine) {
                    buttons.push(actionButton('signOffWithdraw', s, 'btn-outline-secondary', 'featureEnablement.btn.withdraw'));
                } else if (s.signedOn > now) {
                    note = '<div class="small text-warning">' + esc(t('featureEnablement.futureSigned', s.signedOn)) + '</div>';
                    buttons.push(actionButton('signOffReject', s, 'btn-outline-danger', 'featureEnablement.btn.reject'));
                } else if (kind && kind.bundleKind) {
                    note = '<div class="small text-muted">' + esc(t('featureEnablement.bundleOnly', kind.bundleKind)) + '</div>';
                    buttons.push(actionButton('signOffReject', s, 'btn-outline-danger', 'featureEnablement.btn.reject'));
                } else {
                    buttons.push(actionButton('signOffConfirm', s, 'btn-success', 'featureEnablement.btn.confirm'));
                    buttons.push(actionButton('signOffReject', s, 'btn-outline-danger', 'featureEnablement.btn.reject'));
                }
            }
            if (s.status === 'CONFIRMED') {
                buttons.push(actionButton('signOffRevoke', s, 'btn-outline-danger', 'featureEnablement.btn.revoke'));
                if (s.kindClass === 'EVIDENCE') {
                    buttons.push(actionButton('signOffExtend', s, 'btn-outline-warning', 'featureEnablement.btn.extend'));
                }
            }
            return '<tr><td class="ps-3">#' + esc(s.id) + '</td><td><code>' + esc(s.kindCode) + '</code></td><td>'
                + esc(label('featureEnablement.kindClass.', s.kindClass)) + '</td><td class="small text-break">' + esc(s.reference || '-')
                + '</td><td class="small">' + esc(s.signerRole) + '</td><td class="small">' + esc(s.signedOn) + ' 〜 ' + esc(s.expiresOn)
                + '</td><td>' + signOffStatusBadge(s.status) + (s.endReason ? '<div class="small text-muted"><code>' + esc(s.endReason)
                    + '</code></div>' : '') + note + '</td><td class="small">#' + esc(s.registeredBy) + (mine ? ' '
                    + esc(t('featureEnablement.request.mine')) : '') + (s.confirmedBy ? ' / #' + esc(s.confirmedBy) : '')
                + '</td><td class="small"><code>' + esc(s.contentSha256Prefix || '') + '</code></td>'
                + '<td class="text-end pe-3"><div class="d-inline-flex flex-wrap gap-1 justify-content-end">' + buttons.join('')
                + '</div></td></tr>';
        }).join(''));
    }

    function actionButton(action, row, style, key) {
        return '<button type="button" class="btn btn-sm ' + style + '" data-fe-action="' + action + '" data-id="' + esc(row.id)
            + '" data-version="' + esc(row.version) + '">' + esc(t(key)) + '</button>';
    }

    function renderBundles() {
        const body = $('#featureEnablementBundles');
        if (!state.bundles.length) {
            body.html('<tr><td colspan="6" class="text-center text-muted py-4">' + esc(t('featureEnablement.empty')) + '</td></tr>');
            return;
        }
        body.html(state.bundles.map(function (b) {
            const mine = isMine(b.registeredBy);
            const buttons = [];
            if (b.status === 'REGISTERED') {
                if (mine) {
                    buttons.push(actionButton('bundleWithdraw', b, 'btn-outline-secondary', 'featureEnablement.btn.withdraw'));
                } else {
                    buttons.push(actionButton('bundleConfirm', b, 'btn-success', 'featureEnablement.btn.confirm'));
                    buttons.push(actionButton('bundleReject', b, 'btn-outline-danger', 'featureEnablement.btn.reject'));
                }
            }
            if (b.status === 'CONFIRMED') {
                buttons.push(actionButton('bundleRevoke', b, 'btn-outline-danger', 'featureEnablement.btn.revoke'));
            }
            const members = Object.keys(b.members || {}).map(function (kind) {
                return '<code>' + esc(kind) + '</code>=#' + esc(b.members[kind])
                    + (b.carriedOverKinds.indexOf(kind) >= 0 ? ' (' + esc(t('featureEnablement.bundleModal.carry')) + ')' : '');
            }).join('<br>');
            return '<tr><td class="ps-3">#' + esc(b.id) + '</td><td><code>' + esc(b.bundleKind) + '</code></td><td class="small">'
                + members + '</td><td>' + signOffStatusBadge(b.status) + '</td><td class="small">#' + esc(b.registeredBy)
                + (mine ? ' ' + esc(t('featureEnablement.request.mine')) : '') + (b.confirmedBy ? ' / #' + esc(b.confirmedBy) : '')
                + '</td><td class="text-end pe-3"><div class="d-inline-flex gap-1">' + buttons.join('') + '</div></td></tr>';
        }).join(''));
    }

    function signOffDetail(id) {
        request('GET', API + '/sign-offs/' + id).then(function (s) {
            const values = Object.keys(s.structuredValues || {}).map(function (field) {
                return '<li><code>' + esc(field) + '</code>: ' + esc(s.structuredValues[field]) + '</li>';
            }).join('');
            $('#featureEnablementDetailTitle').text(t('featureEnablement.signOffDetail.title', s.id));
            $('#featureEnablementDetailBody').html('<dl class="row small mb-0">'
                + row('featureEnablement.signOff.kind', '<code>' + esc(s.kindCode) + '</code>')
                + row('featureEnablement.signOff.class', esc(label('featureEnablement.kindClass.', s.kindClass)))
                + row('featureEnablement.field.role', esc(s.signerRole))
                + row('featureEnablement.field.name', esc(s.signerName || '-'))
                + row('featureEnablement.field.reference', esc(s.reference || '-'))
                + row('featureEnablement.signOff.period', esc(s.signedOn) + ' 〜 ' + esc(s.expiresOn))
                + row('featureEnablement.signOff.status', signOffStatusBadge(s.status))
                + row('featureEnablement.field.structured', values ? '<ul class="mb-0 ps-3">' + values + '</ul>' : '-')
                + row('featureEnablement.signOff.digest', '<code>' + esc(s.contentSha256Prefix || '') + '</code>')
                + '</dl>');
            bootstrap.Modal.getOrCreateInstance(document.getElementById('featureEnablementDetailModal')).show();
        }).catch(function () {});
    }

    function row(key, html) {
        return '<dt class="col-sm-4 text-muted">' + esc(t(key)) + '</dt><dd class="col-sm-8">' + html + '</dd>';
    }

    function decideSignOff(kind, id, version) {
        const base = kind.indexOf('bundle') === 0 ? '/sign-off-bundles/' : '/sign-offs/';
        const action = kind.replace(/^signOff|^bundle/, '').toLowerCase();
        const titleKey = (kind.indexOf('bundle') === 0 ? 'featureEnablement.confirm.bundle.' : 'featureEnablement.confirm.signOff.')
            + action;
        const reasons = action === 'revoke' ? REVOKE_REASONS : action === 'reject' ? SIGN_OFF_REASONS : null;
        const options = {};
        (reasons || []).forEach(function (code) {
            options[code] = label('featureEnablement.signOffReason.', code);
        });
        const dialog = {
            titleText: t(titleKey, id),
            text: t(titleKey + '.text'),
            icon: action === 'revoke' ? 'warning' : 'question',
            showCancelButton: true,
            confirmButtonText: t('featureEnablement.btn.' + action),
            cancelButtonText: t('featureEnablement.btn.cancel')
        };
        if (reasons) {
            dialog.input = 'select';
            dialog.inputOptions = options;
            dialog.inputPlaceholder = t('featureEnablement.field.reasonCode');
            dialog.inputValidator = function (value) {
                return value ? undefined : t('featureEnablement.field.reasonCode');
            };
            dialog.confirmButtonColor = '#dc3545';
        }
        Swal.fire(dialog).then(function (result) {
            if (!result.isConfirmed) return;
            request('POST', API + base + id + '/' + action, {expectedVersion: version, reason: result.value || null})
                .then(function () {
                    Toast.success(t('featureEnablement.toast.' + action));
                }).catch(function () {}).then(refreshAfterDecision);
        });
    }

    function extend(id) {
        Swal.fire({
            titleText: t('featureEnablement.confirm.extend.title', id),
            text: t('featureEnablement.confirm.extend.text'),
            input: 'date',
            showCancelButton: true,
            confirmButtonText: t('featureEnablement.btn.extend'),
            cancelButtonText: t('featureEnablement.btn.cancel'),
            inputValidator: function (value) {
                return value ? undefined : t('featureEnablement.field.newExpiresOn');
            }
        }).then(function (result) {
            if (!result.isConfirmed) return;
            request('POST', API + '/sign-offs/' + id + '/extend', {newExpiresOn: result.value}).then(function () {
                Toast.success(t('featureEnablement.toast.extensionRequested'));
                return loadRequests(1);
            }).catch(function () {});
        });
    }

    // ---------------------------------------------------------------- 署名の登録

    function structuredInputs(container, kind, prefix) {
        const fields = kind ? kind.structuredFields : [];
        $(container).html(fields.length ? '<label class="form-label small">' + esc(t('featureEnablement.field.structured'))
            + '</label>' + fields.map(function (field) {
                return '<div class="input-group input-group-sm mb-1"><span class="input-group-text bg-dark text-light border-secondary">'
                    + esc(field) + '</span><input type="text" class="form-control bg-dark border-secondary text-light" data-fe-field="'
                    + esc(field) + '" data-fe-prefix="' + esc(prefix) + '" maxlength="500" autocomplete="off"></div>';
            }).join('') + (fields.some(function (f) { return /digest$/i.test(f); })
                ? '<div class="form-text text-muted">' + esc(t('featureEnablement.signOffModal.digestHint')) + '</div>' : '') : '');
    }

    function collectStructured(container) {
        const values = {};
        $(container).find('[data-fe-field]').each(function () {
            const value = ($(this).val() || '').trim();
            if (value) values[$(this).data('fe-field')] = value;
        });
        return values;
    }

    function fillRoles(select, kind) {
        $(select).html((kind ? kind.signerRoles : []).map(function (role) {
            return '<option value="' + esc(role) + '">' + esc(label('featureEnablement.role.', role)) + '</option>';
        }).join(''));
    }

    function openSignOffModal() {
        if (!state.catalog) return;
        const kinds = state.catalog.kinds.filter(function (kind) { return !kind.bundleKind; });
        $('#feSignOffKind').html(kinds.map(function (kind) {
            return '<option value="' + esc(kind.code) + '">' + esc(kind.code) + ' (' + esc(label('featureEnablement.kindClass.',
                kind.kindClass)) + ')</option>';
        }).join(''));
        $('#featureEnablementSignOffForm')[0].reset();
        $('#feSignOffSignedOn').val(today());
        onKindChange();
        bootstrap.Modal.getOrCreateInstance(document.getElementById('featureEnablementModal')).show();
    }

    function onKindChange() {
        const kind = kindOf($('#feSignOffKind').val());
        fillRoles('#feSignOffRole', kind);
        structuredInputs('#feSignOffStructured', kind, 'single');
        $('#feSignOffKindHint').text(kind ? t('featureEnablement.signOffModal.maxDays', kind.maxValidityDays) + ' / '
            + t('featureEnablement.signOffModal.requiredBy', kind.requiredBy.join(', ')) : '');
    }

    function saveSignOff(event) {
        event.preventDefault();
        const file = $('#feSignOffFile')[0].files[0];
        const form = new FormData();
        form.append('kindCode', $('#feSignOffKind').val() || '');
        form.append('signerRole', $('#feSignOffRole').val() || '');
        form.append('signerName', ($('#feSignOffName').val() || '').trim());
        form.append('reference', ($('#feSignOffReference').val() || '').trim());
        form.append('signedOn', $('#feSignOffSignedOn').val() || '');
        form.append('expiresOn', $('#feSignOffExpiresOn').val() || '');
        form.append('structuredValues', JSON.stringify(collectStructured('#feSignOffStructured')));
        if (file) form.append('file', file);
        upload(API + '/sign-offs', form).then(function () {
            bootstrap.Modal.getInstance(document.getElementById('featureEnablementModal')).hide();
            Toast.success(t('featureEnablement.toast.registered'));
            return loadSignOffs();
        }).catch(function () {});
    }

    function openBundleModal() {
        if (!state.catalog) return;
        $('#feBundleKind').html(state.catalog.bundles.map(function (bundle) {
            return '<option value="' + esc(bundle.code) + '">' + esc(bundle.code) + '</option>';
        }).join(''));
        renderBundleMembers();
        bootstrap.Modal.getOrCreateInstance(document.getElementById('featureEnablementBundleModal')).show();
    }

    function renderBundleMembers() {
        const bundle = state.catalog.bundles.find(function (b) { return b.code === $('#feBundleKind').val(); });
        if (!bundle) {
            $('#feBundleMembers').html('');
            return;
        }
        const members = bundle.requiredKinds.map(function (k) { return {kind: k, required: true}; })
            .concat(bundle.optionalKinds.map(function (k) { return {kind: k, required: false}; }));
        $('#feBundleMembers').html(members.map(function (member) {
            const confirmed = state.signOffs.find(function (s) { return s.kindCode === member.kind && s.status === 'CONFIRMED'; });
            const name = 'feBundleMode_' + member.kind;
            return '<div class="border border-secondary rounded p-2 mb-2" data-fe-member="' + esc(member.kind) + '">'
                + '<div class="d-flex flex-wrap gap-3 align-items-center mb-2"><strong><code>' + esc(member.kind) + '</code></strong>'
                + radio(name, 'new', t('featureEnablement.bundleModal.new'), true)
                + (confirmed ? radio(name, 'carry', t('featureEnablement.bundleModal.carry') + ' (#' + confirmed.id + ')', false,
                    confirmed.id) : '')
                + (member.required ? '' : radio(name, 'none', t('featureEnablement.bundleModal.none'), false)) + '</div>'
                + '<div class="row g-2" data-fe-new>'
                + '<div class="col-md-4"><select class="form-select form-select-sm bg-dark border-secondary text-light" data-fe-role></select></div>'
                + '<div class="col-md-4"><input type="text" class="form-control form-control-sm bg-dark border-secondary text-light" data-fe-name maxlength="100" autocomplete="off" placeholder="'
                + esc(t('featureEnablement.field.name')) + '"></div>'
                + '<div class="col-md-4"><input type="text" class="form-control form-control-sm bg-dark border-secondary text-light" data-fe-reference maxlength="200" autocomplete="off" placeholder="'
                + esc(t('featureEnablement.field.reference')) + '"></div>'
                + '<div class="col-md-4"><input type="date" class="form-control form-control-sm bg-dark border-secondary text-light" data-fe-signed value="' + today() + '"></div>'
                + '<div class="col-md-4"><input type="date" class="form-control form-control-sm bg-dark border-secondary text-light" data-fe-expires></div>'
                + '<div class="col-md-4"><input type="file" class="form-control form-control-sm bg-dark border-secondary text-light" data-fe-file accept="application/pdf,.pdf"></div>'
                + '<div class="col-12" data-fe-structured></div></div></div>';
        }).join(''));
        $('#feBundleMembers [data-fe-member]').each(function () {
            const kind = kindOf($(this).data('fe-member'));
            fillRoles($(this).find('[data-fe-role]'), kind);
            structuredInputs($(this).find('[data-fe-structured]'), kind, $(this).data('fe-member'));
        });
    }

    function radio(name, value, text, checked, signOffId) {
        return '<label class="form-check-label small"><input type="radio" class="form-check-input me-1" name="' + esc(name)
            + '" value="' + esc(value) + '"' + (checked ? ' checked' : '') + (signOffId ? ' data-fe-signoff="' + esc(signOffId) + '"' : '')
            + '>' + esc(text) + '</label>';
    }

    function saveBundle(event) {
        event.preventDefault();
        const form = new FormData();
        const body = {bundleKind: $('#feBundleKind').val(), members: [], carriedOver: {}};
        $('#feBundleMembers [data-fe-member]').each(function () {
            const kind = $(this).data('fe-member');
            const selected = $(this).find('input[type=radio]:checked');
            const mode = selected.val();
            if (mode === 'carry') {
                body.carriedOver[kind] = Number(selected.data('fe-signoff'));
            } else if (mode === 'new') {
                body.members.push({
                    kindCode: kind,
                    signerRole: $(this).find('[data-fe-role]').val(),
                    signerName: ($(this).find('[data-fe-name]').val() || '').trim(),
                    reference: ($(this).find('[data-fe-reference]').val() || '').trim(),
                    signedOn: $(this).find('[data-fe-signed]').val() || null,
                    expiresOn: $(this).find('[data-fe-expires]').val() || null,
                    structuredValues: collectStructured($(this).find('[data-fe-structured]'))
                });
                const file = $(this).find('[data-fe-file]')[0].files[0];
                if (file) form.append('file.' + kind, file);
            }
        });
        form.append('request', JSON.stringify(body));
        upload(API + '/sign-off-bundles', form).then(function () {
            bootstrap.Modal.getInstance(document.getElementById('featureEnablementBundleModal')).hide();
            Toast.success(t('featureEnablement.toast.registered'));
            return loadSignOffs();
        }).catch(function () {});
    }

    // ---------------------------------------------------------------- 履歴と頁

    function renderEvents(data) {
        const body = $('#featureEnablementEvents');
        const records = (data && data.records) || [];
        body.html(records.length ? records.map(function (e) {
            return '<tr><td class="ps-3 small">' + dateTime(e.occurredAt) + '</td><td>' + esc(label('featureEnablement.subject.',
                    e.subjectType)) + '</td><td><code>' + esc(e.subjectKey) + '</code>' + (e.subjectId ? ' #' + esc(e.subjectId) : '')
                + '</td><td><code>' + esc(e.eventType) + '</code></td><td class="small">' + esc(e.fromState || '-') + ' → '
                + esc(e.toState || '-') + '</td><td class="small"><code>' + esc(e.reasonCode || '') + '</code></td><td class="small">'
                + esc(e.actorType) + (e.actorUserId ? ' #' + esc(e.actorUserId) : '') + '</td></tr>';
        }).join('') : '<tr><td colspan="7" class="text-center text-muted py-4">' + esc(t('featureEnablement.empty')) + '</td></tr>');
        renderPager('#featureEnablementEventsPager', data, 'events');
    }

    function renderPager(selector, data, kind) {
        if (!data || !data.size) {
            $(selector).html('');
            return;
        }
        const pages = Math.max(1, Math.ceil(data.total / data.size));
        const current = data.current;
        $(selector).html('<div class="d-flex justify-content-between align-items-center small text-muted"><span>'
            + esc(t('featureEnablement.pager', data.total, current, pages)) + '</span><div class="btn-group btn-group-sm">'
            + '<button type="button" class="btn btn-outline-secondary" data-fe-page="' + kind + '" data-page="' + (current - 1) + '"'
            + (current <= 1 ? ' disabled' : '') + '>&lsaquo;</button>'
            + '<button type="button" class="btn btn-outline-secondary" data-fe-page="' + kind + '" data-page="' + (current + 1) + '"'
            + (current >= pages ? ' disabled' : '') + '>&rsaquo;</button></div></div>');
    }

    // ---------------------------------------------------------------- 配線

    function bind() {
        $('#featureEnablementReload').on('click', loadAll);
        $('#featureEnablementRegisterSignOff').on('click', openSignOffModal);
        $('#featureEnablementRegisterBundle').on('click', openBundleModal);
        $('#feSignOffKind').on('change', onKindChange);
        $('#feBundleKind').on('change', renderBundleMembers);
        $('#featureEnablementSignOffForm').on('submit', saveSignOff);
        $('#featureEnablementBundleForm').on('submit', saveBundle);
        $('#featureEnablementPage').on('click', '[data-fe-page]', function () {
            const page = Number($(this).data('page'));
            if ($(this).data('fe-page') === 'requests') loadRequests(page); else loadEvents(page);
        });
        $('#featureEnablementPage').on('click', '[data-fe-action]', function () {
            const action = $(this).data('fe-action');
            const key = $(this).data('key');
            const id = $(this).data('id');
            const version = Number($(this).data('version'));
            switch (action) {
                case 'detail': showDetail(key); break;
                case 'evaluate': evaluate(key); break;
                case 'request': requestOpen(key); break;
                case 'close': closeUnit(key); break;
                case 'approve': approve(id, version); break;
                case 'reject': reject(id, version); break;
                case 'withdraw': withdraw(id, version); break;
                case 'signOffDetail': signOffDetail(id); break;
                case 'document': SES.download(API + '/sign-offs/' + id + '/document', 'sign-off-' + id + '.pdf'); break;
                case 'signOffExtend': extend(id); break;
                default: decideSignOff(action, id, version);
            }
        });
    }

    $(function () {
        if (!document.getElementById('featureEnablementPage')) return;
        bind();
        loadAll();
    });

    return {loadEvents: loadEvents, reload: loadAll};
})();
