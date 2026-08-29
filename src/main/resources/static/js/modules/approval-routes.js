$(function () {
    if (!$('#approvalRoutesPage').length) return;
    loadApprovalRouteSelectOptions();
    loadApprovalRoutes();
    loadApprovalResponsibilities();
    loadApprovalDelegations();
    $('#approvalRouteForm').on('submit', function (e) { e.preventDefault(); saveApprovalRoute(); });
    $('#approvalPreviewForm').on('submit', function (e) { e.preventDefault(); previewApprovalRoute(); });
    $('#approvalResponsibilityForm').on('submit', function (e) { e.preventDefault(); saveApprovalResponsibility(); });
    $('#approvalDelegationForm').on('submit', function (e) { e.preventDefault(); saveApprovalDelegation(); });
});

function approvalUserLabel(u) {
    return (u.realName || u.username || '') + (u.role ? ' (' + u.role + ')' : '');
}

function loadApprovalRouteSelectOptions() {
    $.get('/api/autocomplete/organizations', function (res) {
        if (res.code !== 200) return;
        const options = (res.data || []).map(function (o) {
            return '<option value="' + o.id + '">' + SES.escapeHtml((o.code ? o.code + ' ' : '') + (o.name || '')) + '</option>';
        }).join('');
        $('.approval-org-select').each(function () {
            const placeholder = $(this).find('option[value=""]').first().prop('outerHTML')
                || '<option value="">' + SES.escapeHtml(SES.i18n.t('approval.routes.selectOrganization', '組織を選択...')) + '</option>';
            $(this).html(placeholder + options);
        });
    });
    $.get('/api/autocomplete/assignable-users', function (res) {
        if (res.code !== 200) return;
        const options = (res.data || []).map(function (u) {
            return '<option value="' + u.id + '">' + SES.escapeHtml(approvalUserLabel(u)) + '</option>';
        }).join('');
        $('.approval-user-select').each(function () {
            const placeholder = $(this).find('option[value=""]').first().prop('outerHTML')
                || '<option value="">' + SES.escapeHtml(SES.i18n.t('approval.routes.selectUser', 'ユーザーを選択...')) + '</option>';
            $(this).html(placeholder + options);
        });
    });
}

function routeNumber(value) { return value === '' ? null : Number(value); }
function routeDate(value) { return value === '' ? null : value; }
function routeJson(value) { try { return JSON.parse(value); } catch (e) { SES.toast.error(SES.i18n.t('approval.routes.invalidJson', 'steps JSONを確認してください')); return null; } }
function showApprovalRoutesApiError(error, fallback) { console.error(error); console.error(error && error.message ? error.message : fallback); }

async function loadApprovalRoutes() {
    try { renderApprovalRoutes(await SES.api.get('/api/approval/routes')); } catch (e) { showApprovalRoutesApiError(e, SES.i18n.t('common.msg.fetchFail', 'routeの取得に失敗しました')); }
}
function renderApprovalRoutes(routes) {
    const body = $('#approvalRoutesBody').empty();
    const routeSelect = $('#approvalRouteIdSelect');
    const current = routeSelect.val();
    if (routeSelect.length) {
        routeSelect.html('<option value="">—</option>' + (routes || []).map(function (r) {
            return '<option value="' + SES.escapeHtml(r.id) + '">#' + SES.escapeHtml(r.id) + ' v' + SES.escapeHtml(r.versionNo) + ' ' + SES.escapeHtml(r.requestType || '') + '</option>';
        }).join(''));
        if (current) routeSelect.val(current);
    }
    (routes || []).forEach(function (r) {
        const period = (r.validFrom || '-') + '〜' + (r.validTo || SES.i18n.t('approval.routes.openEnded', '無期限'));
        const steps = (r.steps || []).map(function (s) { return 'step ' + SES.escapeHtml(s.stepNo) + ': ' + SES.escapeHtml(s.approverType) + ' ' + SES.escapeHtml(s.approverValue || ''); }).join('<br>');
        body.append('<tr><td>' + SES.escapeHtml(r.id) + '</td><td>v' + SES.escapeHtml(r.versionNo) + '</td><td>' + SES.escapeHtml(r.requestType) + '<div class="small text-muted">role: ' + SES.escapeHtml(r.applicantRoleCondition || '全role') + '</div></td><td>' + SES.escapeHtml(period) + '</td><td>' + steps + '</td></tr>');
    });
    if (!routes || !routes.length) body.append('<tr><td colspan="5" class="text-center text-muted py-3">' + SES.escapeHtml(SES.i18n.t('approval.routes.empty', 'routeがありません')) + '</td></tr>');
}
async function saveApprovalRoute() {
    const f = $('#approvalRouteForm'); const steps = routeJson(f.find('[name=steps]').val()); if (!steps) return;
    const body = { routeId: routeNumber(f.find('[name=routeId]').val()), requestType: f.find('[name=requestType]').val(), applicantRoleCondition: f.find('[name=applicantRoleCondition]').val().trim() || null, organizationId: routeNumber(f.find('[name=organizationId]').val()), minAmount: routeNumber(f.find('[name=minAmount]').val()), maxAmount: routeNumber(f.find('[name=maxAmount]').val()), validFrom: f.find('[name=validFrom]').val(), validTo: routeDate(f.find('[name=validTo]').val()), steps: steps };
    try { await SES.api.post('/api/approval/routes', body); SES.toast.success(SES.i18n.t('approval.routes.saved', 'route versionを登録しました')); f[0].reset(); loadApprovalRoutes(); } catch (e) { showApprovalRoutesApiError(e, SES.i18n.t('common.msg.saveFail', 'routeの保存に失敗しました')); }
}
async function previewApprovalRoute() {
    const f = $('#approvalPreviewForm'); const body = { requestType: f.find('[name=requestType]').val(), organizationId: routeNumber(f.find('[name=organizationId]').val()), amountSnapshot: routeNumber(f.find('[name=amountSnapshot]').val()), applicantId: routeNumber(f.find('[name=applicantId]').val()), asOf: routeDate(f.find('[name=asOf]').val()) };
    try { const result = await SES.api.post('/api/approval/routes/preview', body); $('#approvalRoutePreview').text(JSON.stringify(result, null, 2)); } catch (e) { $('#approvalRoutePreview').text(SES.i18n.t('approval.routes.previewFailed', 'approverを解決できません')); }
}
async function saveApprovalDelegation() {
    const f = $('#approvalDelegationForm'); const raw = f.find('[name=requestTypes]').val().split(',').map(function (v) { return v.trim(); }).filter(Boolean);
    const body = { fromUserId: routeNumber(f.find('[name=fromUserId]').val()), toUserId: routeNumber(f.find('[name=toUserId]').val()), validFrom: f.find('[name=validFrom]').val(), validTo: routeDate(f.find('[name=validTo]').val()), requestTypes: raw, reason: f.find('[name=reason]').val() };
    try { await SES.api.post('/api/approval/delegations', body); SES.toast.success(SES.i18n.t('approval.routes.delegationSaved', '代理を登録しました')); f[0].reset(); } catch (e) { showApprovalRoutesApiError(e, SES.i18n.t('common.msg.saveFail', '代理の保存に失敗しました')); }
}

async function loadApprovalResponsibilities() {
    try { renderApprovalResponsibilities(await SES.api.get('/api/approval/responsibilities')); } catch (e) { showApprovalRoutesApiError(e, SES.i18n.t('common.msg.fetchFail', '責任者一覧の取得に失敗しました')); }
}
function renderApprovalResponsibilities(rows) {
    const body = $('#approvalResponsibilitiesBody').empty();
    (rows || []).forEach(function (r) {
        const period = (r.validFrom || '-') + '〜' + (r.validTo || SES.i18n.t('approval.routes.openEnded', '無期限'));
        const user = (r.userName || '-') + ' (' + (r.userId || '-') + ')';
        body.append('<tr><td>' + SES.escapeHtml(r.id) + '</td><td>' + SES.escapeHtml(r.responsibilityType) + '</td><td>' + SES.escapeHtml(r.organizationId || '全社') + '</td><td>' + SES.escapeHtml(user) + '</td><td>' + SES.escapeHtml(period) + '</td><td><button class="btn btn-outline-danger btn-sm" data-responsibility-id="' + SES.escapeHtml(r.id) + '">削除</button></td></tr>');
    });
    if (!rows || !rows.length) body.append('<tr><td colspan="6" class="text-center text-muted py-3">責任者assignmentがありません</td></tr>');
    body.find('button[data-responsibility-id]').on('click', function () { deleteApprovalResponsibility($(this).data('responsibility-id')); });
}
async function saveApprovalResponsibility() {
    const f = $('#approvalResponsibilityForm');
    const body = { responsibilityType: f.find('[name=responsibilityType]').val(), organizationId: routeNumber(f.find('[name=organizationId]').val()), userId: routeNumber(f.find('[name=userId]').val()), validFrom: f.find('[name=validFrom]').val(), validTo: routeDate(f.find('[name=validTo]').val()) };
    try { await SES.api.post('/api/approval/responsibilities', body); SES.toast.success('責任者assignmentを登録しました'); f[0].reset(); loadApprovalResponsibilities(); } catch (e) { showApprovalRoutesApiError(e, SES.i18n.t('common.msg.saveFail', '責任者の保存に失敗しました')); }
}
async function deleteApprovalResponsibility(id) {
    try { await SES.api.delete('/api/approval/responsibilities/' + encodeURIComponent(id)); loadApprovalResponsibilities(); } catch (e) { showApprovalRoutesApiError(e, SES.i18n.t('common.msg.deleteFail', '責任者の削除に失敗しました')); }
}

async function loadApprovalDelegations() {
    try { renderApprovalDelegations(await SES.api.get('/api/approval/delegations')); } catch (e) { showApprovalRoutesApiError(e, SES.i18n.t('common.msg.fetchFail', '代理一覧の取得に失敗しました')); }
}
function renderApprovalDelegations(rows) {
    const body = $('#approvalDelegationsBody').empty();
    (rows || []).forEach(function (d) {
        const period = (d.validFrom || '-') + '〜' + (d.validTo || SES.i18n.t('approval.routes.openEnded', '無期限'));
        const target = d.requestTypes && d.requestTypes.length ? d.requestTypes.join(', ') : SES.i18n.t('common.all', '全種別');
        body.append('<tr><td>' + SES.escapeHtml(d.id) + '</td><td>' + SES.escapeHtml(d.fromUserName || d.fromUserId) + '</td><td>' + SES.escapeHtml(d.toUserName || d.toUserId) + '</td><td>' + SES.escapeHtml(period) + '</td><td>' + SES.escapeHtml(d.reason || '-') + '<div class="small text-muted">' + SES.escapeHtml(target) + '</div></td><td>' + SES.escapeHtml(d.approvedBy || '-') + '</td><td><button class="btn btn-outline-danger btn-sm" data-delegation-id="' + SES.escapeHtml(d.id) + '">' + SES.escapeHtml(SES.i18n.t('common.delete', '削除')) + '</button></td></tr>');
    });
    body.find('button[data-delegation-id]').on('click', function () { deleteApprovalDelegation($(this).data('delegation-id')); });
}
async function deleteApprovalDelegation(id) {
    try { await SES.api.delete('/api/approval/delegations/' + encodeURIComponent(id)); loadApprovalDelegations(); } catch (e) { showApprovalRoutesApiError(e, SES.i18n.t('common.msg.deleteFail', '代理の削除に失敗しました')); }
}
