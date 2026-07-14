// システム設定画面

$(document).ready(function() {
    loadConfigs();
});

function loadConfigs() {
    $.get('/api/system-configs', function(res) {
        if (res.code === 200 && res.data) {
            renderConfigs(res.data);
        } else {
            Toast.error(res.message || SES.i18n.t('common.msg.fetchFail'));
        }
    }).fail(function() {
        Toast.error(SES.i18n.t('common.msg.networkError'));
    });
}

function renderConfigs(configs) {
    const $body = $('#configTableBody');
    if (!configs.length) {
        $body.html('<tr><td colspan="3" class="text-center text-muted py-4">`${SES.i18n.t('common.msg.noData')}</td></tr>');
        return;
    }
    let html = '';
    configs.forEach(function(c) {
        const key = escapeHtml(c.configKey);
        const val = c.configValue != null ? escapeHtml(c.configValue) : '';
        const desc = c.description != null ? escapeHtml(c.description) : '';
        html += `
            <tr data-key="${key}">
                <td class="text-light small"><code class="text-info">${key}</code></td>
                <td><input type="text" class="form-control form-control-sm bg-dark border-secondary text-light cfg-value" value="${val}"></td>
                <td class="text-muted small">${desc}</td>
            </tr>`;
    });
    $body.html(html);
}

function saveConfigs() {
    const configs = [];
    $('#configTableBody tr[data-key]').each(function() {
        configs.push({
            configKey: $(this).data('key'),
            configValue: $(this).find('.cfg-value').val(),
            description: $(this).find('td:last').text()
        });
    });

    $.ajax({
        url: '/api/system-configs',
        method: 'PUT',
        contentType: 'application/json',
        data: JSON.stringify(configs),
        success: function(res) {
            if (res.code === 200) {
                Toast.success(SES.i18n.t('systemConfig.msg.saveSuccess'));
            } else {
                Toast.error(res.message || SES.i18n.t('common.msg.saveFail'));
            }
        },
        error: function(xhr) {
            const msg = (xhr.responseJSON && xhr.responseJSON.message) || SES.i18n.t('common.msg.saveFail');
            Toast.error(msg);
        }
    });
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function(c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
}
