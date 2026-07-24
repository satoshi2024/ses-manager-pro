function openSkillSheetModal() {
    $('#skillsheet-anonymize').prop('checked', false);
    
    const $template = $('#skillsheet-template');
    $template.html('<option value="">' + (SES.i18n.t('js.common.loading') || 'Loading...') + '</option>');
    $.get('/api/skillsheet-templates', function(res) {
        if (res.code === 200 && res.data) {
            $template.empty();
            res.data.forEach(t => $template.append(`<option value="${t.id}">${SES.escapeHtml(t.name)}</option>`));
        }
    }).fail(function() {
        $template.html(`<option value="STANDARD">自社標準</option>`);
    });

    $('#skillsheet-format').val('PDF');
    bootstrap.Modal.getOrCreateInstance(document.getElementById('skillSheetModal')).show();
}

function exportSkillSheetDirect() {
    const anonymize = $('#skillsheet-anonymize').is(':checked');
    const template = $('#skillsheet-template').val();
    const formatRaw = $('#skillsheet-format').val().toLowerCase();
    const format = formatRaw === 'excel' ? 'xlsx' : formatRaw;
    
    const url = `/api/engineers/${currentEngineerId}/skill-sheet.${format}?anonymize=${anonymize}&template=${template}`;
    
    bootstrap.Modal.getInstance(document.getElementById('skillSheetModal')).hide();
    
    Swal.fire({
        title: SES.i18n.t('ai.skillsheet.generatingTitle') || '生成中...',
        html: (SES.i18n.t('ai.skillsheet.generatingMessage') || 'スキルシートを生成しています') + '<br><br><div class="spinner-border text-accent-purple" role="status"></div>',
        allowOutsideClick: false,
        showConfirmButton: false
    });

    // Trigger file download by creating a temporary anchor tag
    const a = document.createElement('a');
    a.href = url;
    a.download = '';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    
    setTimeout(() => {
        Swal.close();
        Toast.success(SES.i18n.t('ai.skillsheet.downloadStarted') || 'ダウンロードを開始しました');
    }, 1000);
}
