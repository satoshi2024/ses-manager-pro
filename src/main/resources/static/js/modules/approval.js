/** 承認inbox・申請一覧・詳細(A1)。更新系はSES.apiを通しCSRF headerを付与する。 */
$(function () {
    if ($('#approvalTable').length) {
        loadApprovalList(1);
        $('#approvalSearchForm').on('submit', function (e) { e.preventDefault(); loadApprovalList(1); });
        loadApprovalCreateOptions();
    }
    if ($('#approvalDetailPage').length) loadApprovalDetail($('#approvalDetailPage').data('request-id'));
});

function loadApprovalCreateOptions() {
    const targetSel = $('#approvalCreateForm [name=targetId]');
    const orgSel = $('#approvalCreateForm [name=organizationId]');
    if (!targetSel.length && !orgSel.length) return;
    $.get('/api/contracts/options', function (res) {
        if (res.code !== 200 || !targetSel.length) return;
        const placeholder = targetSel.find('option[value=""]').first().prop('outerHTML')
            || '<option value="">' + SES.escapeHtml(SES.i18n.t('approval.create.selectTarget', '対象を選択...')) + '</option>';
        targetSel.html(placeholder + (res.data || []).map(function (c) {
            return '<option value="' + c.id + '">' + SES.escapeHtml(c.name || '') + '</option>';
        }).join(''));
    });
    $.get('/api/autocomplete/organizations', function (res) {
        if (res.code !== 200 || !orgSel.length) return;
        const placeholder = orgSel.find('option[value=""]').first().prop('outerHTML')
            || '<option value="">' + SES.escapeHtml(SES.i18n.t('approval.create.selectOrganization', '組織を選択...')) + '</option>';
        orgSel.html(placeholder + (res.data || []).map(function (o) {
            return '<option value="' + o.id + '">' + SES.escapeHtml((o.code ? o.code + ' ' : '') + (o.name || '')) + '</option>';
        }).join(''));
    });
}

function approvalView() {
    const page = $('.approval-page').data('approval-view');
    return page || 'inbox';
}

async function loadApprovalList(page) {
    try {
        const data = await SES.api.get('/api/approval/requests', {
            view: approvalView(), status: $('#approvalStatus').val() || '', page: page, pageSize: 20
        });
        renderApprovalList(data);
    } catch (e) { /* SES.apiが共通トーストを表示 */ }
}

function renderApprovalList(data) {
    const body = $('#approvalTableBody').empty();
    if (!data || !data.records || data.records.length === 0) {
        body.append('<tr><td colspan="7" class="text-center py-4 text-muted">' + SES.escapeHtml(SES.i18n.t('approval.empty', '該当する申請はありません')) + '</td></tr>');
        $('#approvalPaginationInfo').text(''); $('#approvalPagination').empty(); return;
    }
    data.records.forEach(function (r) {
        const amount = r.amountSnapshot == null ? '-' : SES.util.formatCurrency(r.amountSnapshot);
        body.append('<tr><td><a href="/approval/requests/' + encodeURIComponent(r.id) + '">' + SES.escapeHtml(r.requestNo || ('#' + r.id)) + '</a></td>'
            + '<td><span class="badge bg-secondary">' + SES.escapeHtml(r.targetType || r.requestType || '-') + '</span><div class="small text-muted">ID: ' + SES.escapeHtml(r.targetId || '-') + '</div></td>'
            + (approvalView() === 'mine' ? '' : '<td>' + SES.escapeHtml(r.applicantId || '-') + '</td>')
            + '<td>' + SES.escapeHtml(amount) + '</td><td>' + approvalStatusBadge(r.status) + '</td>'
            + '<td>' + SES.escapeHtml(r.requestedAt || '-') + '</td><td class="text-end"><a class="btn btn-outline-info btn-sm" href="/approval/requests/' + encodeURIComponent(r.id) + '">' + SES.escapeHtml(SES.i18n.t('common.detail', '詳細')) + '</a></td></tr>');
    });
    $('#approvalPaginationInfo').text(SES.i18n.t('approval.pagination', { total: data.total, current: data.current, pages: data.pages }, '全 {total} 件 ({current}/{pages}ページ)'));
    SES.pagination.render('approvalPagination', data.current, data.pages, loadApprovalList);
}

function approvalStatusBadge(status) {
    const labels = { in_review: 'approval.status.inReview', returned: 'approval.status.returned', approved: 'approval.status.approved', rejected: 'approval.status.rejected', withdrawn: 'approval.status.withdrawn', conflict: 'approval.status.conflict', requested: 'approval.status.requested' };
    const key = labels[status];
    return '<span class="badge bg-' + (status === 'approved' ? 'success' : status === 'rejected' || status === 'conflict' ? 'danger' : status === 'returned' ? 'warning text-dark' : 'info') + '">' + SES.escapeHtml(key ? SES.i18n.t(key, status) : status) + '</span>';
}

async function loadApprovalDetail(id) {
    try {
        const data = await SES.api.get('/api/approval/requests/' + encodeURIComponent(id));
        renderApprovalDetail(data);
    } catch (e) { $('#approvalDetailLoading').text(SES.i18n.t('error.approval.notFound', '申請を表示できません')); }
}

function renderApprovalDetail(data) {
    $('#approvalDetailLoading').addClass('d-none'); $('#approvalDetail').removeClass('d-none');
    $('#approvalSummary').html('<div class="col-6 col-lg-3"><div class="small text-muted">申請番号</div><div class="fw-bold">' + SES.escapeHtml(data.requestNo || '-') + '</div></div><div class="col-6 col-lg-3"><div class="small text-muted">対象</div><div>' + SES.escapeHtml(data.targetType || data.requestType || '-') + ' #' + SES.escapeHtml(data.targetId || '-') + '</div></div><div class="col-6 col-lg-3"><div class="small text-muted">状態</div><div>' + approvalStatusBadge(data.status) + '</div></div><div class="col-6 col-lg-3"><div class="small text-muted">金額</div><div>' + SES.escapeHtml(data.amountSnapshot == null ? '-' : SES.util.formatCurrency(data.amountSnapshot)) + '</div></div>');
    if (data.targetUrl) $('#approvalTargetLink').attr('href', data.targetUrl).removeClass('d-none'); else $('#approvalTargetLink').addClass('d-none');
    $('#approvalExportLink').attr('href', '/api/approval/requests/' + encodeURIComponent(data.id) + '/export');
    const diff = $('#approvalDiffBody').empty();
    (data.diff || []).forEach(function (d) { diff.append('<tr><td>' + SES.escapeHtml(d.label || d.field) + '</td><td>' + approvalValue(d.before, d.masked) + '</td><td>' + approvalValue(d.after, d.masked) + '</td><td>' + (d.masked ? '<span class="badge bg-secondary">' + SES.escapeHtml(SES.i18n.t('approval.masked', '変更あり（値非表示）')) + '</span>' : d.changed ? '<span class="badge bg-warning text-dark">変更</span>' : '-') + '</td></tr>'); });
    if (!data.diff || data.diff.length === 0) diff.append('<tr><td colspan="4" class="text-center text-muted py-3">' + SES.escapeHtml(SES.i18n.t('approval.diff.empty', '差分はありません')) + '</td></tr>');
    const history = $('#approvalHistory').empty();
    (data.actions || []).forEach(function (a) { history.append('<div class="border-bottom border-dark py-2"><div class="d-flex justify-content-between flex-wrap gap-2"><strong>' + SES.escapeHtml(a.action) + '</strong><span class="small text-muted">step ' + SES.escapeHtml(a.stepNo) + ' / ' + SES.escapeHtml(a.actedAt || '') + '</span></div><div class="small">user: ' + SES.escapeHtml(a.approverUserId || '-') + (a.delegated ? ' <span class="badge bg-warning text-dark">代理</span> (from ' + SES.escapeHtml(a.delegatedFrom) + ')' : '') + '</div><div class="mt-1 text-muted">' + SES.escapeHtml(a.comment || '') + '</div></div>'); });
    if (!data.actions || data.actions.length === 0) history.append('<div class="text-muted">' + SES.escapeHtml(SES.i18n.t('approval.history.empty', '履歴はありません')) + '</div>');
    renderApprovalActions(data);
}

function approvalValue(value, masked) { return masked ? '<span class="text-muted">—</span>' : SES.escapeHtml(value == null ? '-' : JSON.stringify(value)); }

function renderApprovalActions(data) {
    const actions = $('#approvalActions').empty();
    if (data.canApprove) actions.append(approvalActionButton('approve', 'btn-success', 'approval.action.approve'));
    if (data.canReject) actions.append(approvalActionButton('reject', 'btn-danger', 'approval.action.reject'));
    if (data.canReturn) actions.append(approvalActionButton('return', 'btn-warning', 'approval.action.return'));
    if (data.canWithdraw) actions.append(approvalActionButton('withdraw', 'btn-outline-secondary', 'approval.action.withdraw'));
    if (data.canResubmit) actions.append(approvalActionButton('resubmit', 'btn-primary', 'approval.action.resubmit'));
    if (actions.children().length) actions.find('button').on('click', function () { submitApprovalAction($(this).data('action'), data); });
}

function approvalActionButton(action, style, key) { return '<button type="button" class="btn ' + style + ' btn-sm" data-action="' + action + '">' + SES.escapeHtml(SES.i18n.t(key, action)) + '</button>'; }

async function submitApprovalAction(action, data) {
    const comment = $('#approvalComment').val() || '';
    const body = { comment: comment };
    if (action === 'resubmit') Object.assign(body, { payload: data.payload || {}, diff: (data.diff || []).reduce(function (m, d) { m[d.field] = { label: d.label, before: d.before, after: d.after, changed: d.changed }; return m; }, {}), amountSnapshot: data.amountSnapshot });
    try { await SES.api.post('/api/approval/requests/' + encodeURIComponent(data.id) + '/' + action, body); SES.toast.success(SES.i18n.t('approval.action.done', '処理しました')); window.location.reload(); } catch (e) { }
}

async function createApprovalRequest() {
    const form = $('#approvalCreateForm'); let body = {};
    form.serializeArray().forEach(function (item) { if (item.name === 'payload' || item.name === 'diff') { try { body[item.name] = item.value ? JSON.parse(item.value) : {}; } catch (e) { SES.toast.error(SES.i18n.t('approval.create.invalidJson', 'JSON形式を確認してください')); body = null; } } else if (item.value !== '') body[item.name] = item.value; });
    if (!body) return;
    ['targetId', 'targetVersion', 'organizationId'].forEach(function (k) { if (body[k]) body[k] = Number(body[k]); }); if (body.amountSnapshot) body.amountSnapshot = Number(body.amountSnapshot);
    try { const result = await SES.api.post('/api/approval/requests', body); bootstrap.Modal.getInstance(document.getElementById('approvalCreateModal')).hide(); window.location.href = '/approval/requests/' + result.id; } catch (e) { }
}
