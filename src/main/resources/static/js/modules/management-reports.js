let reportTemplates = [];
let reportVersions = [];
let reportTemplateModal = null;
let reportVersionModal = null;
let reportVersionTemplateId = null;
let editingReportVersionId = null;
let lastGeneratedRunId = null;
let lastPreviewHash = null;

$(function () {
    reportTemplateModal = new bootstrap.Modal(document.getElementById('report-template-modal'));
    reportVersionModal = new bootstrap.Modal(document.getElementById('report-version-modal'));
    $('#new-template-button').on('click', () => reportTemplateModal.show());
    $('#save-report-template').on('click', createReportTemplate);
    $('#save-report-version').on('click', saveReportVersion);
    $('#report-run-form').on('submit', generateReport);
    $('#reportDeliverBtn').on('click', deliverReport);
    $('#report-run-refresh').on('click', loadRunHistory);
    loadReportTemplates();
    loadRunHistory();
});

function loadReportTemplates() {
    $.get('/api/management-reports/templates', function (res) {
        if (res.code !== 200) { Toast.error(res.message); return; }
        reportTemplates = res.data || [];
        const html = reportTemplates.length === 0
            ? '<div class="text-muted small">テンプレートがありません。作成してください。</div>'
            : reportTemplates.map(t => `<div class="d-flex gap-2 mb-2"><button class="btn btn-outline-light btn-sm flex-grow-1 text-start" onclick="loadReportVersions(${t.id})">${SES.escapeHtml(t.templateName)} <span class="text-muted">(${SES.escapeHtml(t.status)})</span></button><button class="btn btn-outline-info btn-sm" onclick="openReportVersionModal(${t.id})">version作成</button></div>`).join('');
        $('#report-template-list').html(html);
        if (reportTemplates.length > 0) loadReportVersions(reportTemplates[0].id);
    });
}

function loadReportVersions(templateId) {
    $.get(`/api/management-reports/templates/${templateId}/versions`, function (res) {
        if (res.code !== 200) { Toast.error(res.message); return; }
        reportVersions = res.data || [];
        const publishedVersions = reportVersions.filter(v => v.status === 'PUBLISHED');
        $('#report-version-list').html(reportVersions.length === 0
            ? '<div class="text-muted small">versionがありません。version作成からdraftを作成してください。</div>'
            : reportVersions.map(v => `<div class="d-flex align-items-center justify-content-between gap-2 small mb-2"><span class="text-white">v${v.versionNo} <span class="text-muted">(${SES.escapeHtml(v.status)})</span></span><span class="d-flex gap-1">${v.status === 'DRAFT' ? `<button class="btn btn-outline-light btn-sm" onclick="editReportVersion(${v.id})">編集</button><button class="btn btn-outline-success btn-sm" onclick="publishReportVersion(${v.id})">公開</button>` : ''}</span></div>`).join(''));
        $('#report-version').html(publishedVersions.length === 0
            ? '<option value="">公開済みversionなし</option>'
            : publishedVersions.map(v => `<option value="${v.id}">v${v.versionNo} (${v.status})</option>`).join(''));
    });
}

function loadRunHistory() {
    $.get('/api/management-reports/runs?limit=20', function (res) {
        if (res.code !== 200) {
            $('#report-run-history').html(`<tr><td colspan="5" class="text-danger">${SES.escapeHtml(res.message || '読み込み失敗')}</td></tr>`);
            return;
        }
        const runs = res.data || [];
        $('#report-run-history').html(runs.length === 0
            ? '<tr><td colspan="5" class="text-muted">runがありません。</td></tr>'
            : runs.map(run => `<tr>
                <td>${run.id}</td>
                <td>${SES.escapeHtml(run.periodFrom || '')}～${SES.escapeHtml(run.periodTo || '')}</td>
                <td>${SES.escapeHtml(run.cutoffKind || '')}</td>
                <td><span class="badge ${run.status === 'SUCCEEDED' ? 'bg-success' : 'bg-warning text-dark'}">${SES.escapeHtml(run.status || '')}</span></td>
                <td><div class="d-flex flex-wrap gap-1"><button class="btn btn-outline-light btn-sm" onclick="viewRun(${run.id})">表示</button>
                    ${run.status === 'SUCCEEDED' ? `<button class="btn btn-outline-success btn-sm" onclick="deliverRun(${run.id})">配布</button>` : ''}
                </div></td>
            </tr>`).join(''));
    });
}

function viewRun(runId) {
    $.get(`/api/management-reports/runs/${runId}`, function (res) {
        if (res.code !== 200) { Toast.error(res.message); return; }
        lastGeneratedRunId = runId;
        $('#reportDeliverBtn').prop('disabled', res.data.run.status !== 'SUCCEEDED');
        $('#runResult').html(`<span class="badge ${res.data.run.status === 'SUCCEEDED' ? 'bg-success' : 'bg-warning text-dark'}">${SES.escapeHtml(res.data.run.status)}</span> run=${runId}`);
        renderReportSections(res.data.sections || []);
        loadDeliveries(runId);
    });
}

function isReportAdmin() {
    const raw = $('#managementReportApp').attr('data-admin');
    return raw === 'true' || raw === true;
}

function loadDeliveries(runId) {
    $.get(`/api/management-reports/runs/${runId}/deliveries`, function (res) {
        if (res.code !== 200) return;
        const deliveries = res.data || [];
        if (deliveries.length === 0) return;
        const admin = isReportAdmin();
        const rows = deliveries.map(d => `<div class="small text-muted">delivery #${d.id}: ${SES.escapeHtml(d.deliveryStatus || '')} / recipient=${d.recipientUserId}
            ${admin && d.deliveryStatus !== 'CANCELLED' ? `<button class="btn btn-outline-danger btn-sm ms-2" onclick="cancelDelivery(${d.id}, ${runId})">取消</button>` : ''}</div>`).join('');
        $('#runResult').append(`<div class="mt-2">${rows}</div>`);
    });
}

function openReportVersionModal(templateId) {
    reportVersionTemplateId = templateId;
    editingReportVersionId = null;
    $('#report-version-modal-title').text('テンプレートversionを作成');
    $('#save-report-version').text('versionを作成');
    $('#report-version-sections').val('');
    $('#report-version-recipients').val('');
    reportVersionModal.show();
}

function editReportVersion(versionId) {
    const version = reportVersions.find(v => Number(v.id) === Number(versionId));
    if (!version || version.status !== 'DRAFT') { Toast.error('公開済みversionは編集できません。'); return; }
    reportVersionTemplateId = version.templateId;
    editingReportVersionId = version.id;
    $('#report-version-modal-title').text(`v${version.versionNo}を編集`);
    $('#save-report-version').text('versionを保存');
    $('#report-version-sections').val(version.sectionConfigJson || '');
    $('#report-version-recipients').val(version.recipientConfigJson || '');
    reportVersionModal.show();
}

function saveReportVersion() {
    if (!reportVersionTemplateId) { Toast.error('テンプレートを選択してください。'); return; }
    const body = {
        sectionConfigJson: $('#report-version-sections').val(),
        recipientConfigJson: $('#report-version-recipients').val()
    };
    const isEdit = Boolean(editingReportVersionId);
    $.ajax({
        url: isEdit ? `/api/management-reports/versions/${editingReportVersionId}` : `/api/management-reports/templates/${reportVersionTemplateId}/versions`,
        method: isEdit ? 'PUT' : 'POST', contentType: 'application/json', data: JSON.stringify(body),
        success: function (res) {
            if (res.code !== 200) { Toast.error(res.message); return; }
            reportVersionModal.hide();
            Toast.success(isEdit ? 'versionを保存しました。' : 'draft versionを作成しました。公開して利用してください。');
            loadReportVersions(reportVersionTemplateId);
        }
    });
}

function publishReportVersion(versionId) {
    Swal.fire({title: 'versionを公開しますか？', text: '公開後は変更できません。', icon: 'warning', showCancelButton: true, confirmButtonText: '公開', cancelButtonText: 'キャンセル'}).then(result => {
        if (!result.isConfirmed) return;
        $.ajax({url: `/api/management-reports/versions/${versionId}/publish`, method: 'POST', success: function (res) {
            if (res.code !== 200) { Toast.error(res.message); return; }
            Toast.success('versionを公開しました。');
            loadReportVersions(res.data.templateId);
        }});
    });
}

function createReportTemplate() {
    const form = $('#report-template-form')[0];
    if (!form.checkValidity()) { form.reportValidity(); return; }
    $.ajax({
        url: '/api/management-reports/templates', method: 'POST', contentType: 'application/json',
        data: JSON.stringify({templateKey: $('#report-template-key').val(), templateName: $('#report-template-name').val()}),
        success: function (res) {
            if (res.code !== 200) { Toast.error(res.message); return; }
            reportTemplateModal.hide();
            Toast.success('テンプレートを作成しました。versionを作成して公開してください。');
            loadReportTemplates();
        }
    });
}

function generateReport(event) {
    event.preventDefault();
    const versionId = $('#report-version').val();
    const period = $('#report-period').val();
    if (!versionId || !period) { Toast.error('公開済みversionと対象月を指定してください。'); return; }
    $('#runResult').html('<div class="spinner-border spinner-border-sm text-info me-2"></div>recipient previewを確認中...');
    $.ajax({
        url: `/api/management-reports/templates/${versionId}/recipient-preview`, method: 'POST', contentType: 'application/json',
        data: JSON.stringify({period: period}),
        success: function (preview) {
            if (preview.code !== 200) { $('#runResult').text(preview.message || 'recipient previewに失敗しました。'); return; }
            lastPreviewHash = preview.data.previewHash;
            const allowed = (preview.data.recipients || []).filter(r => r.scopeDecision === 'ALLOW').length;
            $('#runResult').html(`<div class="small text-muted mb-2">recipient preview: ${allowed}件を許可。snapshotを生成中...</div>`);
            $.ajax({
        url: '/api/management-reports/runs', method: 'POST', contentType: 'application/json',
        data: JSON.stringify({templateVersionId: Number(versionId), period: period, cutoffKind: $('#report-cutoff').val(), recipientPreviewHash: preview.data.previewHash}),
        success: function (res) {
            if (res.code !== 200) { $('#runResult').text(res.message || '生成に失敗しました。'); return; }
            const run = res.data.run;
            lastGeneratedRunId = run.id;
            $('#reportDeliverBtn').prop('disabled', run.status !== 'SUCCEEDED');
            $('#runResult').html(`<span class="badge ${run.status === 'SUCCEEDED' ? 'bg-success' : 'bg-warning text-dark'}">${SES.escapeHtml(run.status)}</span> run=${run.id} / period=${SES.escapeHtml(run.periodFrom || '')}～${SES.escapeHtml(run.periodTo || '')} / cutoff=${SES.escapeHtml(run.cutoffKind || '')} / timezone=${SES.escapeHtml(run.timezoneId)} / dataAsOf=${SES.escapeHtml(run.dataAsOfAt || '')}`);
            renderReportSections(res.data.sections || []);
            loadRunHistory();
        },
        error: function () { $('#runResult').text('snapshot生成の通信に失敗しました。'); }
            });
        },
        error: function () { $('#runResult').text('recipient previewの通信に失敗しました。'); }
    });
}

function deliverReport() {
    if (!lastGeneratedRunId) { Toast.error('先にSUCCEEDEDのrunを生成してください。'); return; }
    deliverRun(lastGeneratedRunId, lastPreviewHash);
}

function deliverRun(runId, previewHash) {
    const query = previewHash ? `?previewHash=${encodeURIComponent(previewHash)}` : '';
    $.ajax({
        url: `/api/management-reports/runs/${runId}/deliver${query}`,
        method: 'POST',
        success: function (res) {
            if (res.code !== 200) { Toast.error(res.message); return; }
            Toast.success(`配布を開始しました（${(res.data.deliveries || []).length}件）。`);
            viewRun(runId);
        }
    });
}

function cancelDelivery(deliveryId, runId) {
    Swal.fire({title: '配布を取消しますか？', text: 'linkは直ちに失効します。', icon: 'warning', showCancelButton: true, confirmButtonText: '取消', cancelButtonText: '戻る'}).then(result => {
        if (!result.isConfirmed) return;
        $.ajax({
            url: `/api/management-reports/deliveries/${deliveryId}/cancel`,
            method: 'POST',
            success: function (res) {
                if (res.code !== 200) { Toast.error(res.message); return; }
                Toast.success('配布を取消しました。');
                viewRun(runId);
            }
        });
    });
}

function renderReportSections(sections) {
    $('#report-section-table').html(sections.map(s => `<tr><td>${SES.escapeHtml(s.sectionKey)}</td><td>${SES.escapeHtml(s.sectionStatus)}</td><td>${SES.escapeHtml(s.factType)} / ${SES.escapeHtml(s.confirmation)}</td><td>${SES.escapeHtml(s.dataAsOfAt || '')}</td><td>${SES.escapeHtml(s.freshnessStatus || 'UNKNOWN')}</td><td class="font-monospace small">${SES.escapeHtml(s.snapshotHash || '')}</td><td class="small text-break">${SES.escapeHtml(s.valueJson || '')}</td></tr>`).join('') || '<tr><td colspan="7" class="text-muted">sectionなし</td></tr>');
}
