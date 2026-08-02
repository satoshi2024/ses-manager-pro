$(function () {
    if (!$('#approvalRoutesPage').length) return;
    loadApprovalRoutes();
    loadApprovalDelegations();
    $('#approvalRouteForm').on('submit', function (e) { e.preventDefault(); saveApprovalRoute(); });
    $('#approvalPreviewForm').on('submit', function (e) { e.preventDefault(); previewApprovalRoute(); });
    $('#approvalDelegationForm').on('submit', function (e) { e.preventDefault(); saveApprovalDelegation(); });
});

function routeNumber(value) { return value === '' ? null : Number(value); }
function routeDate(value) { return value === '' ? null : value; }
function routeJson(value) { try { return JSON.parse(value); } catch (e) { SES.toast.error(SES.i18n.t('approval.routes.invalidJson', 'steps JSONを確認してください')); return null; } }

async function loadApprovalRoutes() {
    try { renderApprovalRoutes(await SES.api.get('/api/approval/routes')); } catch (e) { }
}
function renderApprovalRoutes(routes) {
    const body = $('#approvalRoutesBody').empty();
    (routes || []).forEach(function (r) {
        const period = (r.validFrom || '-') + '〜' + (r.validTo || SES.i18n.t('approval.routes.openEnded', '無期限'));
        const steps = (r.steps || []).map(function (s) { return 'step ' + SES.escapeHtml(s.stepNo) + ': ' + SES.escapeHtml(s.approverType) + ' ' + SES.escapeHtml(s.approverValue || ''); }).join('<br>');
        body.append('<tr><td>' + SES.escapeHtml(r.id) + '</td><td>v' + SES.escapeHtml(r.versionNo) + '</td><td>' + SES.escapeHtml(r.requestType) + '</td><td>' + SES.escapeHtml(period) + '</td><td>' + steps + '</td></tr>');
    });
    if (!routes || !routes.length) body.append('<tr><td colspan="5" class="text-center text-muted py-3">' + SES.escapeHtml(SES.i18n.t('approval.routes.empty', 'routeがありません')) + '</td></tr>');
}
async function saveApprovalRoute() {
    const f = $('#approvalRouteForm'); const steps = routeJson(f.find('[name=steps]').val()); if (!steps) return;
    const body = { routeId: routeNumber(f.find('[name=routeId]').val()), requestType: f.find('[name=requestType]').val(), organizationId: routeNumber(f.find('[name=organizationId]').val()), minAmount: routeNumber(f.find('[name=minAmount]').val()), maxAmount: routeNumber(f.find('[name=maxAmount]').val()), validFrom: f.find('[name=validFrom]').val(), validTo: routeDate(f.find('[name=validTo]').val()), steps: steps };
    try { await SES.api.post('/api/approval/routes', body); SES.toast.success(SES.i18n.t('approval.routes.saved', 'route versionを登録しました')); f[0].reset(); loadApprovalRoutes(); } catch (e) { }
}
async function previewApprovalRoute() {
    const f = $('#approvalPreviewForm'); const body = { requestType: f.find('[name=requestType]').val(), organizationId: routeNumber(f.find('[name=organizationId]').val()), amountSnapshot: routeNumber(f.find('[name=amountSnapshot]').val()), applicantId: routeNumber(f.find('[name=applicantId]').val()), asOf: routeDate(f.find('[name=asOf]').val()) };
    try { const result = await SES.api.post('/api/approval/routes/preview', body); $('#approvalRoutePreview').text(JSON.stringify(result, null, 2)); } catch (e) { $('#approvalRoutePreview').text(SES.i18n.t('approval.routes.previewFailed', 'approverを解決できません')); }
}
async function saveApprovalDelegation() {
    const f = $('#approvalDelegationForm'); const raw = f.find('[name=requestTypes]').val().split(',').map(function (v) { return v.trim(); }).filter(Boolean);
    const body = { fromUserId: routeNumber(f.find('[name=fromUserId]').val()), toUserId: routeNumber(f.find('[name=toUserId]').val()), validFrom: f.find('[name=validFrom]').val(), validTo: routeDate(f.find('[name=validTo]').val()), requestTypes: raw, reason: f.find('[name=reason]').val() };
    try { await SES.api.post('/api/approval/delegations', body); SES.toast.success(SES.i18n.t('approval.routes.delegationSaved', '代理を登録しました')); f[0].reset(); } catch (e) { }
}

async function loadApprovalDelegations() {
    try { renderApprovalDelegations(await SES.api.get('/api/approval/delegations')); } catch (e) { }
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
    try { await SES.api.delete('/api/approval/delegations/' + encodeURIComponent(id)); loadApprovalDelegations(); } catch (e) { }
}
