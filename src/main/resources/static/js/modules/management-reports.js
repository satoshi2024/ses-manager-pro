let reportTemplates = [];
let reportVersions = [];
let reportTemplateModal = null;

$(function () {
    reportTemplateModal = new bootstrap.Modal(document.getElementById('report-template-modal'));
    $('#new-template-button').on('click', () => reportTemplateModal.show());
    $('#save-report-template').on('click', createReportTemplate);
    $('#report-run-form').on('submit', generateReport);
    loadReportTemplates();
});

function loadReportTemplates() {
    $.get('/api/management-reports/templates', function (res) {
        if (res.code !== 200) { Toast.error(res.message); return; }
        reportTemplates = res.data || [];
        const html = reportTemplates.length === 0
            ? '<div class="text-muted small">テンプレートがありません。作成してください。</div>'
            : reportTemplates.map(t => `<button class="btn btn-outline-light btn-sm w-100 text-start mb-2" onclick="loadReportVersions(${t.id})">${SES.escapeHtml(t.templateName)} <span class="text-muted">(${SES.escapeHtml(t.status)})</span></button>`).join('');
        $('#report-template-list').html(html);
        if (reportTemplates.length > 0) loadReportVersions(reportTemplates[0].id);
    });
}

function loadReportVersions(templateId) {
    $.get(`/api/management-reports/templates/${templateId}/versions`, function (res) {
        if (res.code !== 200) { Toast.error(res.message); return; }
        reportVersions = (res.data || []).filter(v => v.status === 'PUBLISHED');
        $('#report-version').html(reportVersions.length === 0
            ? '<option value="">公開済みversionなし</option>'
            : reportVersions.map(v => `<option value="${v.id}">v${v.versionNo} (${v.status})</option>`).join(''));
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
    $('#report-run-result').html('<div class="spinner-border spinner-border-sm text-info me-2"></div>recipient previewを確認中...');
    $.ajax({
        url: `/api/management-reports/templates/${versionId}/recipient-preview`, method: 'POST', contentType: 'application/json',
        data: JSON.stringify({period: period}),
        success: function (preview) {
            if (preview.code !== 200) { $('#report-run-result').text(preview.message || 'recipient previewに失敗しました。'); return; }
            const allowed = (preview.data.recipients || []).filter(r => r.scopeDecision === 'ALLOW').length;
            $('#report-run-result').html(`<div class="small text-muted mb-2">recipient preview: ${allowed}件を許可。snapshotを生成中...</div>`);
            $.ajax({
        url: '/api/management-reports/runs', method: 'POST', contentType: 'application/json',
        data: JSON.stringify({templateVersionId: Number(versionId), period: period, cutoffKind: $('#report-cutoff').val(), recipientPreviewHash: preview.data.previewHash}),
        success: function (res) {
            if (res.code !== 200) { $('#report-run-result').text(res.message || '生成に失敗しました。'); return; }
            const run = res.data.run;
            $('#report-run-result').html(`<span class="badge ${run.status === 'SUCCEEDED' ? 'bg-success' : 'bg-warning text-dark'}">${SES.escapeHtml(run.status)}</span> run=${run.id} / timezone=${SES.escapeHtml(run.timezoneId)} / dataAsOf=${SES.escapeHtml(run.dataAsOfAt || '')}`);
            renderReportSections(res.data.sections || []);
        },
        error: function () { $('#report-run-result').text('snapshot生成の通信に失敗しました。'); }
            });
        },
        error: function () { $('#report-run-result').text('recipient previewの通信に失敗しました。'); }
    });
}

function renderReportSections(sections) {
    $('#report-section-table').html(sections.map(s => `<tr><td>${SES.escapeHtml(s.sectionKey)}</td><td>${SES.escapeHtml(s.sectionStatus)}</td><td>${SES.escapeHtml(s.factType)} / ${SES.escapeHtml(s.confirmation)}</td><td>${SES.escapeHtml(s.dataAsOfAt || '')}</td><td>${SES.escapeHtml(s.freshnessStatus || 'UNKNOWN')}</td><td class="font-monospace small">${SES.escapeHtml(s.snapshotHash || '')}</td></tr>`).join('') || '<tr><td colspan="6" class="text-muted">sectionなし</td></tr>');
}
