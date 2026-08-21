$(document).ready(function() {
    loadDashboard();
});

const SAMPLE_YEN_FIELDS = {
    'project.unitPriceMin': true,
    'project.unitPriceMax': true,
    'engineer.expectedUnitPrice': true,
    'bp.unitPrice': true
};

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
        const runCount = Number(row.runCount || 0);
        const costCell = data.costVisible
            ? '<td>' + formatCost(row.costJpy, runCount) + '</td>'
            : '';
        tbody.append(
            '<tr>'
            + '<td>' + SES.escapeHtml(formatUseCase(row.useCase) + ' / ' + (row.promptVersion || '')) + '</td>'
            + '<td>' + SES.escapeHtml(row.status || '') + '</td>'
            + '<td>' + formatRate(row.adoptionRate, runCount) + '</td>'
            + '<td>' + formatRate(row.interviewRate, runCount) + '</td>'
            + '<td>' + formatRate(row.winRate, runCount) + '</td>'
            + '<td>' + formatRate(row.precisionAt5, runCount) + '</td>'
            + '<td>' + formatRate(row.precisionAt10, runCount) + '</td>'
            + '<td>' + SES.escapeHtml(row.latencyP95 == null || runCount === 0 ? '—' : String(row.latencyP95)) + '</td>'
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
    renderSamples(data.samples);
}

function renderSamples(list) {
    const samples = $('#aiEvalSamples');
    samples.empty();
    if (!list || list.length === 0) {
        samples.text(SES.i18n.t('ai.evaluation.empty'));
        return;
    }
    list.forEach(function(s) {
        const summary = s.summary || {};
        const keys = Object.keys(summary);
        let rows = '';
        if (keys.length === 0) {
            rows = '<div class="text-muted small">' + SES.escapeHtml(SES.i18n.t('ai.evaluation.empty')) + '</div>';
        } else {
            keys.forEach(function(key) {
                rows += '<div class="d-flex justify-content-between align-items-start gap-2 py-1 border-bottom border-dark">'
                    + '<span class="text-muted small flex-shrink-0">' + SES.escapeHtml(sampleFieldLabel(key)) + '</span>'
                    + '<span class="small text-break text-end">' + SES.escapeHtml(formatSampleValue(key, summary[key])) + '</span>'
                    + '</div>';
            });
        }
        const useCase = s.useCase ? formatUseCase(s.useCase) : '';
        samples.append(
            '<div class="mb-3">'
            + (useCase ? '<div class="badge bg-secondary mb-2">' + SES.escapeHtml(useCase) + '</div>' : '')
            + rows
            + '</div>'
        );
    });
}

function formatUseCase(useCase) {
    if (!useCase) {
        return '';
    }
    return SES.i18n.t('ai.evaluation.useCase.' + useCase, useCase);
}

function formatRate(value, runCount) {
    if (runCount === 0 || value == null) {
        return '—';
    }
    return SES.escapeHtml(Number(value).toFixed(1) + '%');
}

function formatCost(value, runCount) {
    if (runCount === 0 || value == null) {
        return '—';
    }
    return SES.escapeHtml(String(value));
}

function sampleFieldLabel(key) {
    return SES.i18n.t('ai.evaluation.field.' + key, key.split('.').pop());
}

function formatSampleValue(key, value) {
    if (value == null || value === '') {
        return '—';
    }
    if (SAMPLE_YEN_FIELDS[key]) {
        const n = Number(value);
        if (!isNaN(n)) {
            return '¥' + n.toLocaleString('ja-JP');
        }
    }
    return String(value);
}
