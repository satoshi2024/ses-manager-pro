// システム設定画面

$(document).ready(function() {
    loadConfigs();
});

function loadConfigs() {
    $.get('/api/system-configs', function(res) {
        if (res.code === 200 && res.data) {
            renderConfigs(res.data);
        } else {
            Toast.error(res.message || '取得に失敗しました');
        }
    }).fail(function() {
        Toast.error('通信エラーが発生しました');
    });
}

function renderConfigs(configs) {
    const $body = $('#configTableBody');
    if (!configs.length) {
        $body.html('<tr><td colspan="3" class="text-center text-muted py-4">設定がありません</td></tr>');
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
                Toast.success('システム設定を保存しました');
            } else {
                Toast.error(res.message || '保存に失敗しました');
            }
        },
        error: function(xhr) {
            const msg = (xhr.responseJSON && xhr.responseJSON.message) || '保存に失敗しました';
            Toast.error(msg);
        }
    });
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function(c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
}
