// システム設定画面

// マスキング表示対象キー（漏洩すると第三者に悪用されうる機微情報。サーバー側と同じキー一覧）
const MASKED_CONFIG_KEYS = ['notification.webhook-url'];

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
        $body.html('<tr><td colspan="3" class="text-center text-muted py-4">' + SES.i18n.t('common.msg.noData') + '</td></tr>');
        return;
    }
    let html = '';
    configs.forEach(function(c) {
        const key = escapeHtml(c.configKey);
        const val = c.configValue != null ? escapeHtml(c.configValue) : '';
        const desc = c.description != null ? escapeHtml(c.description) : '';
        const isMasked = MASKED_CONFIG_KEYS.indexOf(c.configKey) !== -1;
        const inputType = isMasked ? 'password' : 'text';
        // 単位の紛らわしいキーには画面側の注記を添える(DBのdescriptionは変更しない)。
        // 注記は .cfg-desc とは別要素に置き、保存時に説明へ混入しないようにする。
        const unitNote = unitNoteFor(c.configKey);
        const noteHtml = unitNote ? `<div class="text-warning-emphasis small mt-1"><i class="bi bi-info-circle me-1"></i>${escapeHtml(unitNote)}</div>` : '';
        html += `
            <tr data-key="${key}">
                <td class="text-light small"><code class="text-info">${key}</code></td>
                <td><input type="${inputType}" autocomplete="new-password" class="form-control form-control-sm bg-dark border-secondary text-light cfg-value" value="${val}"></td>
                <td class="text-muted small"><span class="cfg-desc">${desc}</span>${noteHtml}</td>
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
            // 画面側注記(.cfg-desc の外)を除いた元の説明のみを送る
            description: $(this).find('.cfg-desc').text()
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

// 単位が紛らわしい設定キーの注記(小数/百分率)。i18n から取得。
function unitNoteFor(key) {
    if (key === 'billing.tax-rate') return SES.i18n.t('systemConfig.note.taxRate');
    if (key === 'commission.rate') return SES.i18n.t('systemConfig.note.commissionRate');
    return '';
}

function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function(c) {
        return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
}
