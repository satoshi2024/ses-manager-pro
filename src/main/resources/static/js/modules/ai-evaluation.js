$(document).ready(function() {
    loadDashboard();
});

function loadDashboard() {
    $.ajax({
        url: '/api/ai/evaluations/dashboard',
        method: 'GET',
        success: function(res) {
            if (res.code !== 200 || !res.data) {
                Toast.error(res.message || SES.i18n.t('common.msg.networkError'));
                return;
            }
            renderDashboard(res.data);
        },
        error: function() {
            Toast.error(SES.i18n.t('common.msg.networkError'));
        }
    });
}

function renderDashboard(data) {
    const tbody = $('#aiEvalVersionTable tbody');
    tbody.empty();
    if (!data.costVisible) {
        $('.js-cost-col').hide();
    }
    (data.versions || []).forEach(function(row) {
        const costCell = data.costVisible
            ? '<td>' + SES.escapeHtml(String(row.costJpy == null ? '-' : row.costJpy)) + '</td>'
            : '';
        tbody.append(
            '<tr>'
            + '<td>' + SES.escapeHtml((row.useCase || '') + ' / ' + (row.promptVersion || '')) + '</td>'
            + '<td>' + SES.escapeHtml(row.status || '') + '</td>'
            + '<td>' + formatRate(row.adoptionRate) + '</td>'
            + '<td>' + formatRate(row.interviewRate) + '</td>'
            + '<td>' + formatRate(row.winRate) + '</td>'
            + '<td>' + formatRate(row.precisionAt5) + '</td>'
            + '<td>' + formatRate(row.precisionAt10) + '</td>'
            + '<td>' + SES.escapeHtml(row.latencyP95 == null ? '-' : String(row.latencyP95)) + '</td>'
            + costCell
            + '</tr>'
        );
    });
    const reasons = $('#aiEvalReasons');
    reasons.empty();
    if (!data.reasonDistribution || data.reasonDistribution.length === 0) {
        reasons.text(SES.i18n.t('ai.evaluation.empty'));
    } else {
        data.reasonDistribution.forEach(function(r) {
            reasons.append('<div class="mb-1">' + SES.escapeHtml(r.reasonCode)
                + ' : ' + SES.escapeHtml(String(r.count)) + '</div>');
        });
    }
    const segments = $('#aiEvalSegments');
    segments.empty();
    if (!data.segments || data.segments.length === 0) {
        segments.text(SES.i18n.t('ai.evaluation.empty'));
    } else {
        data.segments.forEach(function(s) {
            segments.append('<div class="mb-1">' + SES.escapeHtml(s.segment || '')
                + ' : ' + SES.escapeHtml(String(s.count)) + '</div>');
        });
    }
    const samples = $('#aiEvalSamples');
    samples.empty();
    (data.samples || []).forEach(function(s) {
        samples.append(
            '<pre class="small bg-secondary text-white p-2 rounded">'
            + SES.escapeHtml(JSON.stringify(s.summary || {}, null, 2))
            + '</pre>'
        );
    });
}

function formatRate(value) {
    if (value == null) {
        return '-';
    }
    return SES.escapeHtml(Number(value).toFixed(1) + '%');
}
