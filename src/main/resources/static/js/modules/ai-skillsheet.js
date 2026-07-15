function downloadSkillSheet(engineerId, type) {
    const url = `/api/engineers/${engineerId}/skill-sheet.${type}`;
    
    Swal.fire({
        title: SES.i18n.t('ai.skillsheet.generateTitle'),
        text: SES.i18n.t('ai.skillsheet.generateConfirm'),
        icon: 'question',
        showCancelButton: true,
        confirmButtonColor: '#6f42c1',
        cancelButtonColor: '#6c757d',
        confirmButtonText: '<i class="bi bi-download me-1"></i> ' + SES.i18n.t('common.download') || 'ダウンロード',
        cancelButtonText: SES.i18n.t('common.cancel')
    }).then((result) => {
        if (result.isConfirmed) {
            Swal.fire({
                title: SES.i18n.t('ai.skillsheet.generatingTitle'),
                html: SES.i18n.t('ai.skillsheet.generatingMessage') + '<br><br><div class="spinner-border text-accent-purple" role="status"></div>',
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
                Toast.success(SES.i18n.t('ai.skillsheet.downloadStarted'));
            }, 1000);
        }
    });
}
