/**
 * 経営コパイロット chat（A1）。数値は typed result の metrics からのみ描画する。
 */
(function () {
    const API_QUERY = '/api/copilot/query';
    const API_CITATION = '/api/copilot/citations';

    const I18N = {
        nullValue: '未設定',
        zeroValue: '0',
        unconfirmed: '未確認',
        forecast: '予測',
        actual: '実績',
        mixed: '実績/予測',
        citationUnavailable: '出典を表示できません',
        summaryLabel: '要約',
        errorGeneric: '処理に失敗しました。',
        sending: '分析中...'
    };

    function t(key) {
        if (window.SES && SES.i18n && typeof SES.i18n.t === 'function') {
            return SES.i18n.t(key);
        }
        return I18N[key] || key;
    }

    function formatMetric(metric) {
        if (!metric) {
            return t('copilot.metric.unconfirmed');
        }
        if (metric.state === 'NULL') {
            return t('copilot.metric.null');
        }
        if (metric.state === 'UNCONFIRMED' || metric.state === 'NOT_APPLICABLE') {
            return t('copilot.metric.unconfirmed');
        }
        const unit = metric.unit || '';
        let valueText;
        if (unit === 'YEN' && metric.longValue != null) {
            valueText = '¥' + Number(metric.longValue).toLocaleString('ja-JP');
        } else if (unit === 'PERCENT' && metric.numericValue != null) {
            valueText = Number(metric.numericValue).toFixed(metric.displayScale != null ? metric.displayScale : 1) + '%';
        } else if (unit === 'COUNT' && metric.longValue != null) {
            valueText = Number(metric.longValue).toLocaleString('ja-JP');
        } else if (metric.numericValue != null) {
            valueText = String(metric.numericValue);
        } else if (metric.state === 'ZERO') {
            return unit === 'YEN' ? '¥0' : t('copilot.metric.zero');
        } else {
            return t('copilot.metric.unconfirmed');
        }
        const basis = metric.basis === 'FORECAST' ? t('copilot.metric.forecast')
            : metric.basis === 'MIXED' ? t('copilot.metric.mixed') : t('copilot.metric.actual');
        const period = metric.period ? ' <span class="text-muted small">(' + SES.escapeHtml(metric.period) + ' · ' + basis + ')</span>' : '';
        return '<span class="metric-value fw-semibold text-white">' + SES.escapeHtml(valueText) + '</span>' + period;
    }

    function renderSummary(summary) {
        if (!summary || !summary.available || !summary.text) {
            return '';
        }
        return '<div class="copilot-summary mb-3 p-2 rounded border border-secondary-subtle">'
            + '<div class="text-muted small mb-1">' + SES.escapeHtml(t('copilot.summary.label')) + '</div>'
            + '<div class="text-light">' + SES.escapeHtml(summary.text) + '</div>'
            + '</div>';
    }

    function renderMetrics(values) {
        if (!values || !values.length) {
            return '<p class="text-muted small mb-0">' + SES.escapeHtml(t('copilot.metric.unconfirmed')) + '</p>';
        }
        return values.map(function (metric) {
            return '<div class="copilot-metric-card rounded p-2 mb-2">'
                + '<div class="text-muted small">' + SES.escapeHtml(metric.key) + '</div>'
                + '<div>' + formatMetric(metric) + '</div>'
                + '</div>';
        }).join('');
    }

    function renderCitations(citations) {
        if (!citations || !citations.length) {
            return '';
        }
        const items = citations.map(function (c) {
            if (!c.available || !c.route) {
                return '<span class="badge bg-secondary-subtle text-muted me-1">'
                    + SES.escapeHtml(t('copilot.citation.unavailable')) + '</span>';
            }
            return '<a class="badge bg-primary-subtle text-primary text-decoration-none me-1" href="'
                + SES.escapeHtml(c.route) + '">'
                + SES.escapeHtml(c.label || c.key) + '</a>';
        }).join('');
        return '<div class="mt-2 pt-2 border-top border-secondary-subtle small">' + items + '</div>';
    }

    function appendMessage(role, html) {
        const thread = document.getElementById('copilot-thread');
        const empty = document.getElementById('copilot-empty');
        if (empty) {
            empty.remove();
        }
        const wrap = document.createElement('div');
        wrap.className = 'rounded p-3 ' + (role === 'user' ? 'copilot-msg-user align-self-end' : 'copilot-msg-assistant align-self-start');
        wrap.style.maxWidth = '100%';
        wrap.innerHTML = html;
        thread.appendChild(wrap);
        thread.scrollTop = thread.scrollHeight;
    }

    function sendQuestion() {
        const page = document.getElementById('copilot-page');
        if (!page || page.getAttribute('data-enabled') !== 'true') {
            return;
        }
        const input = document.getElementById('copilot-question');
        const question = (input.value || '').trim();
        if (!question) {
            return;
        }
        appendMessage('user', '<div class="text-light">' + SES.escapeHtml(question) + '</div>');
        input.value = '';
        appendMessage('assistant', '<div class="text-muted small"><span class="spinner-border spinner-border-sm me-1"></span>'
            + SES.escapeHtml(t('copilot.msg.sending')) + '</div>');

        $.ajax({
            url: API_QUERY,
            method: 'POST',
            contentType: 'application/json',
            headers: Object.assign({}, SES.csrf.header()),
            data: JSON.stringify({ question: question }),
            success: function (res) {
                const thread = document.getElementById('copilot-thread');
                thread.removeChild(thread.lastChild);
                if (!res || res.code !== 200 || !res.data) {
                    appendMessage('assistant', '<div class="text-danger">' + SES.escapeHtml(t('copilot.msg.error')) + '</div>');
                    return;
                }
                const data = res.data;
                if (data.status !== 'SUCCEEDED' || !data.result) {
                    appendMessage('assistant', '<div class="text-warning">'
                        + SES.escapeHtml(data.message || t('copilot.msg.error')) + '</div>');
                    return;
                }
                let html = '<div class="text-muted small mb-2">' + SES.escapeHtml(data.queryId) + '</div>';
                html += renderSummary(data.summary);
                html += renderMetrics(data.result.values);
                html += renderCitations(data.citations);
                if (data.result.limit && data.result.limit.truncated) {
                    html += '<div class="text-warning small mt-2">truncated</div>';
                }
                appendMessage('assistant', html);
            },
            error: function (xhr) {
                const thread = document.getElementById('copilot-thread');
                if (thread.lastChild) {
                    thread.removeChild(thread.lastChild);
                }
                const msg = xhr.responseJSON && xhr.responseJSON.message
                    ? xhr.responseJSON.message : t('copilot.msg.error');
                appendMessage('assistant', '<div class="text-danger">' + SES.escapeHtml(msg) + '</div>');
            }
        });
    }

    function bindEvents() {
        const sendBtn = document.getElementById('copilot-send');
        if (sendBtn) {
            sendBtn.addEventListener('click', sendQuestion);
        }
        const form = document.getElementById('copilot-form');
        if (form) {
            form.addEventListener('keydown', function (e) {
                if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    sendQuestion();
                }
            });
        }
    }

    document.addEventListener('DOMContentLoaded', bindEvents);
})();
